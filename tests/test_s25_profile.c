/* Host unit tests for games/totk/runtime/s25_profile.h. */
#define _POSIX_C_SOURCE 200809L
#include "../games/totk/runtime/s25_profile.h"
#include <assert.h>

static void clear_all(void) {
    const char *keys[] = {
        "TOTK_S25_PROFILE", "TOTK_FRAMEGEN", "TOTK_FPS60",
        "TOTK_ADPF", "TOTK_ADPF_GPU", "TOTK_ADPF_CPU_SPLIT", "TOTK_ADPF_LOAD_HINT", "TOTK_ADPF_TARGET_PCT",
        "TOTK_THERMAL_STEP", "TOTK_TIMER_SLACK_NS", "TOTK_PRIO_RENDER", "TOTK_PRIO_WALK",
        "TOTK_VK_DESC_STOCK", "TOTK_VK_UPLOAD_THREADS", "TOTK_VK_COMPILE_THREADS",
        "TOTK_VK_SPLIT_PASS_DRAWS", "TOTK_SHADOW_CASCADES", "TOTK_POINT_SHADOWS"
    };
    for (size_t i = 0; i < sizeof keys / sizeof *keys; i++) unsetenv(keys[i]);
}

int main(void) {
    assert(totk_s25_is_target("SM8750", "SM-S936U") == 1);
    assert(totk_s25_is_target("SM8750-AB", "Generic") == 1);
    assert(totk_s25_is_target("CQ8725S", "Odin3") == 1);
    assert(totk_s25_is_target("unknown", "SM-S936B") == 1);
    assert(totk_s25_is_target("SM8550", "SM-S916U") == 0);
    assert(totk_s25_is_target("SM87500", "Other") == 0);

    clear_all();
    setenv("TOTK_FPS60", "1", 1);
    int n = totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(n == 14);
    assert(!strcmp(getenv("TOTK_ADPF"), "full"));
    assert(!strcmp(getenv("TOTK_VK_SPLIT_PASS_DRAWS"), "512"));
    assert(!strcmp(getenv("TOTK_SHADOW_CASCADES"), "3"));
    assert(!strcmp(getenv("TOTK_POINT_SHADOWS"), "2"));

    clear_all();
    setenv("TOTK_FPS60", "0", 1);
    setenv("TOTK_FRAMEGEN", "locked", 1);
    n = totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(n == 11);
    assert(getenv("TOTK_THERMAL_STEP") == NULL);
    assert(getenv("TOTK_VK_SPLIT_PASS_DRAWS") == NULL);
    assert(getenv("TOTK_SHADOW_CASCADES") == NULL);
    assert(!strcmp(getenv("TOTK_ADPF_GPU"), "1"));

    clear_all();
    setenv("TOTK_FPS60", "1", 1);
    setenv("TOTK_SHADOW_CASCADES", "0", 1);
    totk_s25_profile_apply_for("SM8750", "SM-S936U");
    assert(!strcmp(getenv("TOTK_SHADOW_CASCADES"), "0"));

    clear_all();
    setenv("TOTK_S25_PROFILE", "0", 1);
    assert(totk_s25_profile_apply_for("SM8750", "SM-S936U") == 0);

    clear_all();
    assert(totk_s25_profile_apply_for("SM8550", "Thor") == 0);

    printf("test_s25_profile: all assertions passed\n");
    return 0;
}
