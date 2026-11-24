/* Samsung Galaxy S25+ / Snapdragon 8 Elite (SM8750 / Adreno 830) runtime auto-tuner.
 * Runs right after settings.env and totk.env using setenv(..., overwrite=0) so every
 * explicit menu choice or totk.env override always wins. Set TOTK_S25_PROFILE=0 in
 * totk.env to disable all automatic tuning for A/B benchmarking. */
#pragma once

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

static inline int totk_s25_starts_with_ci(const char *s, const char *prefix) {
    if (!s || !prefix) return 0;
    while (*prefix) {
        if (toupper((unsigned char)*s) != toupper((unsigned char)*prefix)) return 0;
        s++;
        prefix++;
    }
    return 1;
}

static inline int totk_s25_soc_match(const char *soc) {
    if (!soc || !*soc) return 0;
    while (isspace((unsigned char)*soc)) soc++;
    return (totk_s25_starts_with_ci(soc, "SM8750") && (!soc[6] || soc[6] == '-' || isspace((unsigned char)soc[6]))) ||
           (totk_s25_starts_with_ci(soc, "CQ8725S") && (!soc[7] || soc[7] == '-' || isspace((unsigned char)soc[7])));
}

static inline int totk_s25_model_match(const char *model) {
    if (!model || !*model) return 0;
    while (isspace((unsigned char)*model)) model++;
    /* Samsung Galaxy S25 (SM-S931*), S25+ (SM-S936*), S25 Edge (SM-S937*), S25 Ultra (SM-S938*). */
    return totk_s25_starts_with_ci(model, "SM-S931") ||
           totk_s25_starts_with_ci(model, "SM-S936") ||
           totk_s25_starts_with_ci(model, "SM-S937") ||
           totk_s25_starts_with_ci(model, "SM-S938");
}

static inline int totk_s25_is_target(const char *soc, const char *model) {
    return totk_s25_soc_match(soc) || totk_s25_model_match(model);
}

static inline int totk_s25_framegen_active(void) {
    const char *fg = getenv("TOTK_FRAMEGEN");
    if (!fg || !*fg || !strcmp(fg, "0") || !strcmp(fg, "off")) return 0;
    return 1;
}

static inline int totk_s25_set_default(const char *key, const char *val, char *applied, size_t cap, size_t *used) {
    const char *cur = getenv(key);
    if (cur && *cur) return 0;
    if (setenv(key, val, 0) != 0) return 0;
    if (applied && *used < cap) {
        int n = snprintf(applied + *used, cap - *used, "%s%s=%s", *used ? " " : "", key, val);
        if (n > 0) *used += (size_t)n < cap - *used ? (size_t)n : cap - *used - 1;
    }
    return 1;
}

static inline int totk_s25_profile_apply_for(const char *soc, const char *model) {
    const char *override = getenv("TOTK_S25_PROFILE");
    if (override && !strcmp(override, "0")) {
        fprintf(stderr, "[totk-s25] disabled by TOTK_S25_PROFILE=0\n");
        return 0;
    }
    int forced = override && !strcmp(override, "1");
    if (!forced && !totk_s25_is_target(soc, model)) return 0;

    int fg = totk_s25_framegen_active();
    const char *fps60_env = getenv("TOTK_FPS60");
    int fps60 = fps60_env && !strcmp(fps60_env, "1");

    char applied[1024];
    applied[0] = '\0';
    size_t used = 0;
    int count = 0;

    /* 1. Anti-2.2GHz clock-cap ADPF (API 35 CPU/GPU split + API 36 workload-increase hint). */
    count += totk_s25_set_default("TOTK_ADPF", "full", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_ADPF_GPU", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_ADPF_CPU_SPLIT", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_ADPF_LOAD_HINT", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_ADPF_TARGET_PCT", "85", applied, sizeof applied, &used);

    /* 2. Passive-cooling thermal headroom stepping (1 Hz AThermal_getThermalHeadroom). */
    count += totk_s25_set_default("TOTK_THERMAL_STEP", "1", applied, sizeof applied, &used);

    /* 3. Tight frame-pacing wakeups and priority for render/command-walking threads. */
    count += totk_s25_set_default("TOTK_TIMER_SLACK_NS", "1000", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_PRIO_RENDER", "-4", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_PRIO_WALK", "-8", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_PRESENT_PREROTATE", "1", applied, sizeof applied, &used);

    /* 4. Adreno 830 Vulkan GMEM, descriptor stock, and Oryon v2 worker thread scaling. */
    count += totk_s25_set_default("TOTK_VK_LOAD_ELIDE", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_VK_STORE_CLEAR", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_PSO_PREDICT", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_VK_DESC_STOCK", "1", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_VK_UPLOAD_THREADS", "2", applied, sizeof applied, &used);
    count += totk_s25_set_default("TOTK_VK_COMPILE_THREADS", "4", applied, sizeof applied, &used);
    if (!fg) {
        /* Pass splitting cannot run alongside frame-generation extraction. */
        count += totk_s25_set_default("TOTK_VK_SPLIT_PASS_DRAWS", "512", applied, sizeof applied, &used);
    }

    /* 5. Recompiled TotK v1.4.3 game-code patches:
     *    - Without Frame Gen: cut 4th distant shadow cascade (+352), cap point-light shadows (+384), and enable DRS60 (+320).
     *    - With Frame Gen: enable DRS_FRAMEGEN (+352..+416) without colliding on the +352 veneer slot. */
    if (!fg) {
        count += totk_s25_set_default("TOTK_SHADOW_CASCADES", "3", applied, sizeof applied, &used);
        count += totk_s25_set_default("TOTK_POINT_SHADOWS", "2", applied, sizeof applied, &used);
        if (fps60) {
            count += totk_s25_set_default("TOTK_DRS60", "1", applied, sizeof applied, &used);
        }
    } else if (!fps60) {
        count += totk_s25_set_default("TOTK_DRS_FRAMEGEN", "1", applied, sizeof applied, &used);
    }

    fprintf(stderr, "[totk-s25] active (soc=%s model=%s forced=%d fg=%d fps60=%d): applied %d defaults: %s\n",
            soc && *soc ? soc : "?", model && *model ? model : "?", forced, fg, fps60, count,
            used ? applied : "(all overridden)");
    return count;
}

static inline void totk_s25_profile_apply(void) {
    char soc[128] = {0}, model[128] = {0};
#if defined(__ANDROID__)
    __system_property_get("ro.soc.model", soc);
    __system_property_get("ro.product.model", model);
#endif
    totk_s25_profile_apply_for(soc, model);
}
