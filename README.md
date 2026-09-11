# Fungal Sentinel

[简体中文使用教程](README.zh-CN.md)

Fungal Sentinel is an offline Android Camera2 RAW capture and fluorescence spectral-analysis app for phones that expose RAW output and manual camera controls.

It is not a general camera app or an AI fungal-image classifier. Combined with a fixed slit/grating optical setup, it converts RAW sensor profiles into wavelength-calibrated fluorescence measurements and can estimate concentration from standards.

## Features

- Live Camera2 preview and `RAW_SENSOR` capture.
- Manual exposure, ISO, focus, white balance, denoise, sharpening, and hot-pixel controls where supported.
- Four-step FSSA v1.3.4-aligned workflow:
  1. wavelength calibration;
  2. true-SPD camera-response calibration;
  3. paired Blank/Sample fluorescence analysis;
  4. grouped concentration regression.
- Exposure-normalized batches of 1–5 captures.
- Ypet, EGFP, mCherry, CFP, and mTurquoise2 integration ranges.
- Calibration, saturation, and regression quality warnings.
- Optional calibration-protection override for research workflow testing.
- Three DNG policies: all RAW captures, Sample captures only, or no DNG.
- Recoverable in-progress experiment drafts.
- Room-backed History with ordinary ZIP export containing versioned JSON, CSV, and optional DNG files.
- Fully offline computation.

## Compatibility and scientific requirements

Full capture functionality requires a device whose Camera2 implementation exposes RAW output. Manual controls depend on the capabilities reported by each camera.

Reliable quantitative results also require:

- a fixed phone, slit, grating, sample holder, and optical geometry;
- known R/G/B positioning wavelengths;
- a characterized standard source and its matching SPD CSV;
- suitable Blank and Sample preparation;
- known concentration standards when concentration is required.

The bundled `true_spd.csv` is valid only with the source from which it was measured. It is not a universal white-light SPD. Camera, ISO, focus, RAW dimensions, and optical geometry must remain consistent. Exposure is normalized from capture metadata, although stable exposure is still recommended. Manual controls use practical extended ranges intersected with device capabilities: 10–3000 ms, ISO 50–1600, and focus 0–5 D.

## Projects and quick workflow

Project naming is now Step 1 inside Analyze rather than a separate startup dialog. The name is locked for that run and prefixes every saved DNG, for example `EGFP_Sample_A__positioning_1_<timestamp>.dng`. A project is finished and written to History only after the concentration curve has been built.

1. **Project name:** enter a name and create the project before capturing RAW data.
2. **Wavelength calibration:** capture 1–5 R/G/B positioning frames. B and R fit the pixel-to-wavelength mapping; G independently validates it. The first accepted frame locks the X-ROI.
3. **Spectral response:** capture 1–5 frames of the characterized source using its matching SPD data.
4. **Sample analysis:** choose a fluorophore, capture 1–5 Blank frames and 1–5 unknown Sample frames, then review corrected area, replicate SD, and spectrum.
5. **Concentration:** for each of 2–10 different standards, enter concentration and capture 1–5 Blank plus 1–5 Sample frames. Add each group and build the regression curve. At least 3 concentration groups are recommended.

One frame per batch is sufficient for workflow testing. Three frames per batch are recommended for ordinary measurements. With only one Sample replicate, SD is shown as zero because variability cannot be estimated.

### Typical capture counts

| Scenario | Total RAW captures |
| --- | ---: |
| Minimum workflow through Sample analysis | 4 |
| Recommended Sample analysis, 3 captures per batch | 12 |
| Minimum concentration workflow with 2 standards | 8 |
| General quantification with 3 standards and 3 captures per batch | 30 |
| Five-point curve with 3 captures per batch | 42 |

After a validated calibration and standard curve can be safely reused under unchanged conditions, routine measurements should require only new Blank and Sample captures. The current application records calibration data in History; reusable calibration templates are a possible future workflow improvement.

For complete preparation, capture, quality-control, History, ZIP, and troubleshooting instructions, see the **[Chinese user guide](README.zh-CN.md)**.

## Calibration protection

By default, Step 3 is blocked when the Step 2 G validation error exceeds 10 nm. Under **Settings**, Calibration protection may be disabled for research workflow testing. This does not make the calibration valid: the calibration and downstream archive remain marked `FAILED` and must not be treated as validated quantitative data.

## DNG storage

Choose a mode under **Settings**:

- **All RAW captures** — recommended for traceable formal experiments.
- **Samples only** — save unknown and standard Sample DNG files.
- **No DNG** — analyze in memory and retain only profiles/results in History.

Rejected captures may remain as standalone DNG evidence in All mode but are excluded from History archives.

Settings also provides **Save diagnostic log** and **Clear diagnostic log**. The rolling log stays local, is limited to 2 MB, is never uploaded automatically, and excludes RAW images and project names.

## History and ZIP export

After the concentration curve is available, select **Finish project & save to History**. Every internal and exported archive is an ordinary `.zip` containing:

```text
manifest.json
result.json
profiles.csv
standards.csv
spd.csv
raw/                 # present only when DNG files were saved and available
```

Unfinished projects resume automatically when the app reopens. Use Reset when you want to discard one; Reset asks whether its public DNG files should be kept or deleted. Deleting a History entry deletes its internal ZIP, not DNG files already published under `Pictures/FungalSentinel`.

## Install

Download the APK from the repository's GitHub Releases page and install it on an Android phone. Android may require permission to install unknown apps.

Do not clone the repository if you only want to use the application. Cloning is intended for development.

## Build

Use Android Studio, or on Windows PowerShell run:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease
```

The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

For long-term distribution, create a private `keystore.properties` based on `keystore.properties.example`. Never commit the real keystore or passwords.

## Package ID

The Android application ID is `org.fungalsentinel.app`. Keep it stable so signed releases install as updates instead of separate applications.
