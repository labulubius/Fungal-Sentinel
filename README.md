# Fungal Sentinel

Fungal Sentinel is an offline Android Camera2 RAW capture and fluorescence spectral-analysis app for devices that expose manual camera controls and RAW output.

## Features

- Live camera preview.
- Real-time manual controls for exposure time, ISO, focus distance, white balance mode, noise reduction, edge enhancement, and hot pixel correction.
- Device capability detection with unsupported controls disabled.
- DNG capture saved through Android MediaStore.
- Four-step on-device FSSA workflow: wavelength calibration, true-SPD response calibration, sample analysis, and concentration regression.
- Direct, memory-efficient extraction of one-dimensional R/G/B profiles from `RAW_SENSOR` frames.
- Displays calibration residuals and records RAW capture metadata for review.
- Support for Ypet, EGFP, mCherry, CFP, and mTurquoise2.
- Fungal Sentinel launcher icon and app name.

## Compatibility

Full functionality requires a device whose Camera2 implementation exposes `MANUAL_SENSOR` and `RAW` capabilities. Devices with partial Camera2 support can still open the app, but RAW capture or individual controls may be disabled.

A measured two-column SPD CSV can be imported for response calibration. As in the FSSA v1.3.2 reference program, the app uses a simulated broad-spectrum SPD when no CSV is selected. For comparable quantitative results, users should keep the camera, ISO, exposure time, focus, and optical setup unchanged.

## FSSA workflow

1. Open **Analyze** and capture an R/G/B positioning source. The app fits B and R and validates against G.
2. Import the standard source's true-SPD CSV and capture that source.
3. Select a fluorophore and capture the unknown sample.
4. Enter and capture 2–5 standards, then build the concentration curve. At least three standards are recommended.

Every capture is saved as DNG even if quality control rejects its analysis. Computation remains on device.

## Install

Download the APK from the repository's GitHub Releases page and install it on an Android phone. Android may require allowing installation from unknown sources.

Do not clone the repository if you only want to use the app. Cloning is for developers who want the source code.

## Build

Use Android Studio, or run:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease
```

The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

For long-term distribution, create a private `keystore.properties` file based on `keystore.properties.example`. The real keystore and passwords must not be committed.

## Package ID

The Android application ID is `org.fungalsentinel.app`. Keep this stable after public distribution so future APKs install as updates instead of separate apps.
