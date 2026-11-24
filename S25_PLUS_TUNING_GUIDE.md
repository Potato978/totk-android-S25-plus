# TotK Native — Samsung Galaxy S25+ (Snapdragon 8 Elite / Adreno 830) Build & Tuning Guide

## 1. What Was Optimized in This Build

### A. Compiler & Oryon v2 Native ISA (`games/totk/android/Makefile`, `build_release.py`)
When built with `--s25-opt` (enabled by default in `.github/workflows/build-s25-apk.yml`), `libtotk.so` compiles with:
- `-O3 -fomit-frame-pointer` (replaces generic `-O2 -fno-omit-frame-pointer`)
- `-flto=thin -Wl,--thinlto-jobs=4 -Wl,-O2` (whole-program ThinLTO across the recompiled runtime and NVN→Vulkan backend)
- `-march=armv8.6-a+crypto+fp16+dotprod+i8mm+bf16` (native Snapdragon 8 Elite / Oryon v2 ISA: LSE atomics, dotprod, FP16, I8MM)

### B. Dedicated S25+ Runtime & Recompiled Game-Code Auto-Tuner (`games/totk/runtime/s25_profile.h`)
Automatically activates on Snapdragon 8 Elite (`SM8750` / `CQ8725S`) and Galaxy S25 series (`SM-S931*`/`SM-S936*`/`SM-S937*`/`SM-S938*`) right after `settings.env` and `totk.env` using `setenv(..., overwrite=0)` so explicit menu choices or `totk.env` overrides always win:

| Category | Setting | Effect on S25+ |
|---|---|---|
| Anti-2.2GHz Clock Cap (ADPF) | `TOTK_ADPF=full`, `TOTK_ADPF_GPU=1`, `TOTK_ADPF_CPU_SPLIT=1`, `TOTK_ADPF_LOAD_HINT=1`, `TOTK_ADPF_TARGET_PCT=85` | Uses Android 15 API 35 CPU+GPU split reporting + Android 16 API 36 workload-increase hints at 85% target margin so clocks ramp proactively before frame drops (including during Frame Gen) |
| Passive Cooling Guard | `TOTK_THERMAL_STEP=1` | 1 Hz background `AThermal_getThermalHeadroom` polling smoothly steps down before Samsung's hard thermal cliff |
| Frame Pacing & Priority | `TOTK_TIMER_SLACK_NS=1000`, `TOTK_PRIO_RENDER=-4`, `TOTK_PRIO_WALK=-8`, `TOTK_PRESENT_PREROTATE=1` | 1 µs wakeup precision (vs 50 µs Linux default), elevated priority for Vulkan command walker/render threads, and pre-rotated swapchain avoiding SurfaceFlinger rotation overhead |
| Adreno 830 Vulkan | `TOTK_VK_LOAD_ELIDE=1`, `TOTK_VK_DESC_STOCK=1`, `TOTK_VK_UPLOAD_THREADS=2`, `TOTK_VK_COMPILE_THREADS=4`, `TOTK_VK_SPLIT_PASS_DRAWS=512` (when FG off) | Elides redundant GMEM color loads, pre-allocates descriptor stocks, parallelizes texture deswizzling (2 threads) & async shader compilation (4 threads), and splits large passes for Adreno 830 |
| Recompiled TotK v1.4.3 Patches | `TOTK_SHADOW_CASCADES=3`, `TOTK_POINT_SHADOWS=2`, `TOTK_DRS60=1` (40/60 FPS) or `TOTK_DRS_FRAMEGEN=1` (30+FG) | Direct ARM64 binary patches to TotK v1.4.3: drops the 4th distant directional shadow cascade, caps point-light shadow caster pool at 2, and retargets native DRS to 60 Hz or Frame Gen GPU headroom |

**Verification:** Check `totk.log` after launching for `[totk-s25] active (...)`.
**Kill switch / overrides:** Add `TOTK_S25_PROFILE=0` (or override any individual key) in `<game>/totk.env`.

---

## 2. Cloud Build Steps (GitHub Actions)

1. **Back up saves FIRST on your S25+:** Open official TotK Native → **Saves → Export saves** and keep `TotK Native saves <date>.zip` safe.
2. **Fork & push:** Fork `AurelioB/totk-android` on GitHub and push/upload this modified tree to your fork's `main` branch.
3. *(Optional, recommended for in-place updates)* **Add a persistent signing key:**
   - Generate a `.p12` key once (`openssl`), base64-encode it, and save as repository secrets `SIGNING_KEY_BASE64` and `SIGNING_KEY_PASSWORD` under **Settings → Secrets and variables → Actions**.
   - If you skip this, the workflow automatically generates a temporary key so your first build works immediately.
4. **Run the build:** Open **Actions → Build TotK Native APK (Galaxy S25+ Optimized) → Run workflow**.
   - Keep `merge_pr_44` (black wheel fix), `merge_pr_45` (TKMM RomFS/IPS mods), `merge_pr_46` (Lossless.dll Frame Gen), `merge_pr_43` (wind white-box fix), and `s25_opt` checked.
5. **Download & install:** Download `TotK-Native-S25Plus-APK` from the completed workflow run, uninstall the official APK, install `TotK-Native-S25Plus.apk`, and restore your saves via **Saves → Import saves**.

---

## 3. Recommended In-App Settings (S25+)

### Profile A: Native Locked 40 FPS (120 Hz Container — Best Input Latency)
- **Frame rate:** `40 fps`
- **Frame generation:** `Off`
- **Render resolution:** `1152 × 648` (or `Game default` with `TOTK_DRS60=1` auto-active)
- **GPU feed:** `On`
- **Upscaler:** `Snapdragon GSR (SGSR)` — Sharpness `40–50`
- **Anti-aliasing:** `Game (FXAA)` (or `Off` for maximum GPU headroom)
- **Shadows:** `Medium` (`288`) or `Low` (`256`)
- **Second screen:** `Off`

### Profile B: 30 FPS + Lossless Scaling (`Lossless.dll` via PR #46 — Smoothest 60 FPS Visuals)
- **Frame rate:** `30 fps`
- **Frame generation:** `Lossless Scaling` (import your own `Lossless.dll` from the menu)
- **Render resolution:** `1152 × 648` (or `1280 × 720` in light areas)
- **Upscaler:** `Snapdragon GSR (SGSR)` — Sharpness `40–50`
- **Shadows:** `Medium` (`288`)
- **Second screen:** `Off`

### Custom Mesa Turnip Driver (Built-in)
- Open **Game data → Graphics driver** and pick any AdrenoTools `.zip` or ARM64 `.so` driver to test newer Adreno 830 Turnip builds without recompiling.

---

## 4. Samsung Galaxy S25+ OS Settings (Defeating the ~2.2 GHz Clock Cap)

1. **Settings → Battery → Performance profile:** Set to **Standard** (never Light).
2. **Gaming Hub / Game Booster → Game Optimization:** Set to **Performance**; disable **Auto gaming optimization** / **Low refresh rate** / **Save power during touch protection**.
3. **Game Booster → Labs:** Enable **Alternate game performance management** (bypasses Samsung's aggressive GOS frame-cap/clock-cap governor).
4. **Good Guardians (Galaxy Store) → Thermal Guardian:**
   - Raise the **Thermal threshold** slider to **+2 °C**.
   - Open **Additional settings** and **uncheck** *Limit CPU boosting when heating* and *Adjust standard refresh rate*.
5. **Display → Motion smoothness:** Set to **Adaptive (120 Hz)** (required for the 40 FPS / 120 Hz 3:1 container).

---

## 5. Xreal One AR Glasses (USB-C DP Alt Mode / Samsung DeX)

- Stereoscopic Full SBS (3840×1080) is intentionally skipped because TotK Native has no dual-eye render pass and rendering double geometry on passive cooling tanks sustained FPS.
- Connect the Xreal One in **2D 1920×1080 @ 120 Hz** (or 60 Hz for 30 FPS + Lossless Scaling), launch TotK Native full-screen, and toggle the **Xreal One's built-in on-glasses 2D-to-3D mode** when you want 3D with zero GPU penalty on the S25+.

---

## 6. Customizable On-Screen Touch UI (`TouchOverlay.java`)

- **Toggle On/Off:** Tap the draggable **`UI`** pill in the top-left corner to show or hide all on-screen Switch controls (`A/B/X/Y`, `D-Pad`, `L/R/ZL/ZR`, `+/−`, `L3/R3`, Left & Right analog sticks). Starts hidden by default so physical controllers and Xreal One stay unobstructed.
- **Edit Mode (Move, Scale, Shape, Opacity, Individual Toggle):**
  - **Long-press the `UI` pill** to open the floating Edit toolbar.
  - **Move freely:** Drag any button or stick anywhere on screen (saved as normalized ratios so it adapts cleanly between phone screen and DeX/Xreal One).
  - **Scale freely (50%–220%):** Tap a button to select it, then drag the **Scale slider** or **2-finger pinch**.
  - **Change Shape:** Tap **Shape** (selected button) or **All Shape** (all buttons) to cycle **Circle → Rounded Square → Pill → Octagon**.
  - **Individual Hide/Show & Opacity:** Tap **Hide** to hide any button you don't need, **Show All** to restore, adjust the **Opacity slider (20%–100%)**, or tap **Reset**. Tap **Done** to save to `SharedPreferences`.

## 7. Additional Modern Android / Adreno 830 Upgrades Added

- **Android 14–16 Power HAL `GAME` Mode (`build_apk.py`):** Sets `android:appCategory="game"` (`0x01010545 = 0`) in `AndroidManifest.xml` so Android's Power HAL engages full `GAME` power mode (CPU boost + foreground priority + relaxed thermal throttling).
- **Android 13–16 `GameManager.setGameState(MODE_GAMEPLAY_UNINTERRUPTIBLE)` (`TotkActivity.java`):** Signals active uninterruptible gameplay on resume and releases on pause, preventing background OS/ART jobs from causing mid-frame stutters.
- **Adreno 830 GMEM Dead-Store Elimination (`TOTK_VK_STORE_CLEAR=1`) & Same-Pass PSO Prediction (`TOTK_PSO_PREDICT=1`):** Added to `s25_profile.h` (now 20 automatic S25+ optimizations) to cut GMEM→DRAM write bandwidth and pre-compile upcoming Vulkan pipelines on 4 worker threads.
