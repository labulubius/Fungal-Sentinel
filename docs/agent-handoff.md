# Fungal Sentinel Agent Handoff

> **最新交接请优先阅读：`docs/agent-handoff-v1.3.4-port.md`。本文件以下内容是 v1.2.2 阶段的历史记录。**
>
> Updated: 2026-09-11

## Repository state

- Branch: `main`, currently 32 commits ahead of `origin/main` at the time of this handoff.
- Current test build: versionName `1.2.2`, versionCode `5`.
- Release APK: `dist/Fungal-Sentinel-v1.2.2.apk` (the `dist/` directory is ignored and the APK is not committed).
- v1.2.2 is installed on the test phone with the existing release signing key.
- Do not overwrite or commit the user's unrelated working files:
  - modified `group/report.md`
  - untracked `group/FSSA_v1.3.4.py`
  - untracked `group/new version sheet.txt`
  - untracked `group/软件汇报0909.html`

## Test devices

- Physical phone: vivo V2303A / product PD2303, foldable with narrow and large displays.
- Wireless ADB was paired successfully. The last connection endpoint was `192.168.50.194:37537`, but wireless-debug ports can change after reconnect/reboot. Never record or reuse pairing codes.
- Emulator: `emulator-5554`.

## Work completed in this session

- `aea1566`: supported preview size plus center-crop/aspect transform.
- `81dacec`: real-wavelength response/sample charts and concentration chart.
- `fd5865f`: 400 ms default, logarithmic exposure slider, AE meter-and-lock, infinity focus.
- `4001abe`: editable R/G/B calibration wavelengths and bundled `true_spd.csv`.
- `d92c89f`: reject quantitative captures whose exposure/ISO/focus/sensor/RAW dimensions differ from positioning calibration.
- `59107b2`: narrow-screen compact navigation rail and shorter/conditional parameter UI.
- `97271c9`: automatic preview requests choose the best stable frame-rate range near 30 fps; still RAW requests are not frame-rate constrained.
- Build/version commits prepared and installed v1.2.2.

Current automated baseline:

- `testDebugUnitTest`: 32 passed, 0 failed.
- Instrumented resource tests: 2 passed when run manually with `adb shell am instrument`.
- `lintDebug`: passes with non-blocking warnings.
- Debug and release APKs build successfully.

## Physical-phone feedback

1. v1.2 installed over v1.1 successfully and preserved app data.
2. The first narrow-screen layout placed the full directory beside the detail panel and squeezed controls. v1.2.1 changed narrow screens to a 72 dp navigation rail (`A`, steps 1–4, settings, capture), hid unsupported controls, shortened labels, removed the always-visible log, and hid an empty concentration chart. Emulator narrow-screen inspection looked correct; v1.2.1 was installed successfully.
3. User reported that moving the phone still makes preview motion look choppy, including in automatic exposure mode.
   - v1.2.2 requests the phone's advertised fixed `[30,30]` AE target FPS range for preview only.
   - User reported no perceptible improvement.
   - Decision: do not keep making risky Camera2 changes solely for preview smoothness unless it obstructs alignment. It does not affect RAW data or spectral calculations.
   - Manual 400 ms exposure is physically limited to about 2.5 fps and must look choppy. Do not promise smooth preview in that mode.
   - If this is revisited, first measure actual `CaptureResult.SENSOR_TIMESTAMP`, exposure time and frame duration on the phone. Do not guess, and do not constrain still-capture AE FPS.

## Remaining priority work

### P0: full physical Camera2/RAW/DNG validation

Still not completed end-to-end. Verify on the vivo phone or another RAW-capable phone:

- DNG saves successfully and opens in common software / `exiftool`.
- Requested manual 400 ms exposure, ISO and 0 D infinity focus appear in capture metadata.
- AE meter-and-lock reaches confirmed `CONTROL_AE_STATE_LOCKED` and capture remains disabled while metering.
- Positioning, SPD, sample and standards retain identical exposure/ISO/focus/sensor/RAW dimensions.
- Pause/resume, lock-screen return, fold/unfold, narrow/large display switching and repeated capture do not crash.
- Complete all four analysis steps with real optical inputs and inspect the new charts.

### P0: confirm bundled SPD provenance

`app/src/main/res/raw/true_spd.csv` is the normalized data copied from tracked `group/CSV_Data_Cleaner/true_spd.csv` (line endings normalized). The exact characterized lamp/source and measurement conditions are not documented. The app warns that the bundled curve must only be used with its matching source. Obtain and record that provenance before calling quantitative response calibration validated.

### P1: user UI micro-adjustment

The user intends to fine-tune after this handoff. Recheck compact layout on both physical displays, especially:

- charts with real data and long tick values;
- text-field keyboard overlap;
- the 72 dp rail touch targets;
- landscape and fold/unfold transitions.

### P2 / requires product clarification

- Dedicated half-screen operating mode: visible half, orientation and trigger behavior are unspecified.
- Debug-only simulated four-step data mode: optional developer aid, not a substitute for physical validation.
- Non-blocking lint cleanup and dependency upgrades: defer and split into small commits.
- The old feedback “what is the third chart?” still needs a screenshot/page name.
- Decide whether the wavelength validation tolerance (`±2.5 nm`) should be editable.

## Important behavior and design constraints

- Keep application ID `org.fungalsentinel.app` stable.
- Keep release signing configuration/key stable so updates install over prior versions.
- Each focused change should have its own commit and validation.
- The G wavelength residual is intentionally reported rather than used as a hard workflow gate, matching the documented FSSA interpretation.
- Camera-setting changes clear existing calibration/downstream analysis.
- Later captures with mismatched RAW metadata are rejected.
- The bundled SPD is not universally valid for arbitrary standard lamps.

## Suggested first action for the next agent

Read this file and `docs/next-version-improvement-plan.md`, inspect `git status`, and ask the user whether they want (a) physical four-step validation, (b) narrow-screen UI micro-adjustment, or (c) one of the unresolved P2 features. Do not resume preview-FPS experimentation without actual frame-timestamp measurements.
