# Fungal Sentinel Camera Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a distributable Fungal Sentinel APK with live Camera2 manual controls, device capability degradation, and the supplied launcher icon.

**Architecture:** Move camera parameter state and support mapping into small Kotlin model files that can be unit tested. Keep Camera2 session ownership in `MainActivity`, but make it consume a single `CameraControlSettings` value and reapply repeating requests whenever settings change.

**Tech Stack:** Android Kotlin, Jetpack Compose Material 3, Camera2 API, JUnit4 unit tests, Gradle Android plugin.

---

### Task 1: Parameter Model

**Files:**
- Create: `app/src/main/java/com/example/myapplication/CameraControlSettings.kt`
- Test: `app/src/test/java/com/example/myapplication/CameraControlSettingsTest.kt`

- [ ] Write failing tests for default manual settings, automatic mode, and clamping.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.myapplication.CameraControlSettingsTest` and verify it fails because the model does not exist.
- [ ] Implement `CameraControlSettings`, `CameraControlRanges`, and `CameraControlSupport`.
- [ ] Run the same unit test and verify it passes.

### Task 2: Live Controls UI and Camera2 Integration

**Files:**
- Modify: `app/src/main/java/com/example/myapplication/MainActivity.kt`

- [ ] Add Compose state for settings panel visibility and current `CameraControlSettings`.
- [ ] Detect `MANUAL_SENSOR`, `RAW`, focus, noise reduction, edge, and hot pixel support from `CameraCharacteristics`.
- [ ] Render a gear button, bottom DNG button, and settings panel with live sliders and switches.
- [ ] Reapply preview repeating requests whenever settings change.
- [ ] Use the same settings for still DNG capture.

### Task 3: Branding and APK Packaging

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify launcher icon resources under `app/src/main/res/mipmap-*`

- [ ] Change app name to `Fungal Sentinel`.
- [ ] Generate launcher icons from `D:\102_program\Fungal Sentinel\Fungal Sentinel Logo.jpg`.
- [ ] Build debug APK and release APK.
- [ ] Install and launch on the connected device for a smoke test.
