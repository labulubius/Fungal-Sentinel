# Fungal Sentinel

Fungal Sentinel is an Android Camera2 RAW capture app for devices that expose manual camera controls and RAW output.

## Features

- Live camera preview.
- Real-time manual controls for exposure time, ISO, focus distance, white balance mode, noise reduction, edge enhancement, and hot pixel correction.
- Device capability detection with unsupported controls disabled.
- DNG capture saved through Android MediaStore.
- Fungal Sentinel launcher icon and app name.

## Compatibility

Full functionality requires a device whose Camera2 implementation exposes `MANUAL_SENSOR` and `RAW` capabilities. Devices with partial Camera2 support can still open the app, but RAW capture or individual controls may be disabled.

## Install

Download the APK from `dist/Fungal-Sentinel-v1.0.apk` and install it on an Android phone. Android may require allowing installation from unknown sources.

## Build

Use Android Studio, or run:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease
```

The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.
