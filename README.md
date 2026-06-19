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
