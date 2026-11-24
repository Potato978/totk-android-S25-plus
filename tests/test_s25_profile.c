/* Host unit tests for games/totk/runtime/s25_profile.h. */
#define _POSIX_C_SOURCE 200809L
#include "../games/totk/runtime/s25_profile.h"
#include <assert.h>

static void clear_all(void) {
    const char *keys[] = {
        "TOTK_S25_PROFILE", "TOTK_FRAMEGEN", "TOTK_FPS60",
        "TOTK_ADPF", "TOTK_ADPF_GPU", "TOTK_ADPF_CPU_SPLIT", "TOTK_ADPF_LOAD_HINT", "TOTK_ADPF_TARGET_PCT",
        "TOTK_THERMAL_STEP", "TOTK_TIMER_SLACK_NS", "TOTK_PRIO_RENDER", "TOTK_PRIO_WALK",
        "TOTK_PRESENT_PREROTATE", "TOTK_VK_LOAD_ELIDE", "TOTK_VK_STORE_CLEAR", "TOTK_PSO_PREDICT", "TOTK_VK_DESC_STOCK",
        "TOTK_VK_UPLOAD_THREADS", "TOTK_VK_COMPILE_THREADS", "TOTK_VK_SPLIT_PASS_DRAWS",
        "TOTK_SHADOW_CASCADES", "TOTK_POINT_SHADOWS", "TOTK_DRS60", "TOTK_DRS_FRAMEGEN"
    };
    for (size_t i = 0; i < sizeof keys / sizeof *keys; i++) unsetenv(keys[i]);
}

int main(void) {
    /* 1. Device matching */
    assert(totk_s25_is_target("SM8750", "SM-S936U") == 1);
    assert(totk_s25_is_target("SM8750-AB", "Generic") == 1);
    assert(totk_s25_is_target("CQ8725S", "Odin3") == 1);
    assert(totk_s25_is_target("unknown", "SM-S936B") == 1);
    assert(totk_s25_is_target("SM8550", "SM-S916U") == 0);
    assert(totk_s25_is_target("SM87500", "Other") == 0);

    /* 2. 40/60 FPS mode without framegen: enables DRS60 + pass splitting */
    clear_all();
    setenv("TOTK_FPS60", "1", 1);
    int n = totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(n == 20);
    assert(!strcmp(getenv("TOTK_ADPF"), "full"));
    assert(!strcmp(getenv("TOTK_ADPF_GPU"), "1"));
    assert(!strcmp(getenv("TOTK_ADPF_CPU_SPLIT"), "1"));
    assert(!strcmp(getenv("TOTK_ADPF_LOAD_HINT"), "1"));
    assert(!strcmp(getenv("TOTK_ADPF_TARGET_PCT"), "85"));
    assert(!strcmp(getenv("TOTK_THERMAL_STEP"), "1"));
    assert(!strcmp(getenv("TOTK_TIMER_SLACK_NS"), "1000"));
    assert(!strcmp(getenv("TOTK_VK_SPLIT_PASS_DRAWS"), "512"));
    assert(!strcmp(getenv("TOTK_SHADOW_CASCADES"), "3"));
    assert(!strcmp(getenv("TOTK_POINT_SHADOWS"), "2"));
    assert(!strcmp(getenv("TOTK_DRS60"), "1"));
    assert(getenv("TOTK_DRS_FRAMEGEN") == NULL);

    /* 3. Framegen mode (30 FPS + LSFG/locked): skips pass splitting, enables DRS_FRAMEGEN */
    clear_all();
    setenv("TOTK_FPS60", "0", 1);
    setenv("TOTK_FRAMEGEN", "locked", 1);
    n = totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(n == 17);
    assert(getenv("TOTK_VK_SPLIT_PASS_DRAWS") == NULL);
    assert(getenv("TOTK_DRS60") == NULL);
    assert(!strcmp(getenv("TOTK_DRS_FRAMEGEN"), "1"));
    assert(!strcmp(getenv("TOTK_ADPF_GPU"), "1"));

    /* 4. Explicit totk.env override always wins */
    clear_all();
    setenv("TOTK_FPS60", "1", 1);
    setenv("TOTK_SHADOW_CASCADES", "0", 1);
    setenv("TOTK_ADPF_TARGET_PCT", "90", 1);
    totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(!strcmp(getenv("TOTK_SHADOW_CASCADES"), "0"));
    assert(!strcmp(getenv("TOTK_ADPF_TARGET_PCT"), "90"));

    /* 5. Kill switch */
    clear_all();
    setenv("TOTK_S25_PROFILE", "0", 1);
    assert(totk_s25_profile_apply_for("SM8750", "SM-S936U") == 0);
    assert(getenv("TOTK_ADPF") == NULL);

    /* 6. Non-target device stays untouched */
    clear_all();
    assert(totk_s25_profile_apply_for("SM8550", "Thor") == 0);
    assert(getenv("TOTK_ADPF") == NULL);

    printf("test_s25_profile: all assertions passed\n");
    return 0;
}
