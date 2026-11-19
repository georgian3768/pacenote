# Pacenote — Android app

Kotlin app, `minSdk 29`, `targetSdk 33`, Compose overlay + Room trip store.

## Modules

| Module | What lives there |
|---|---|
| `app` | UI, foreground service, overlay, trip storage, CSV export, calibration |
| `shiftassist` | Pure-JVM decode and coaching logic: frame parsing, gear selection, eco score, cue generation, CSV writing. Unit tested, no Android dependencies. |

`shiftassist` is a separate module so the maths can be tested on the JVM. That
suite (`./gradlew :shiftassist:test`) is the project's only automated test
coverage and it is worth running before any release.

## Build

Requirements: JDK 17, Android SDK platform 33, build-tools 34.

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew :app:assembleDebug
./gradlew :shiftassist:test
```

Or open `android/` in Android Studio.

The Gradle root project is still named `PocketMechanicSpike` and the Kotlin
package is still `com.pocketmechanic.spike`. Only the install id
(`com.pacenote.app`) and user-facing strings changed at 1.0. Renaming the source
packages would be churn with no user-visible effect.

## Release signing

`keystore.properties` in this directory (gitignored) is read if it exists:

```properties
storeFile=/absolute/path/to/pacenote-release.jks
storePassword=...
keyAlias=pacenote
keyPassword=...
```

Without the file, release builds are unsigned and still compile. `staging` is a
debuggable build type signed with the same release key: it exists only to seed a
database into a fresh install id, and must never be distributed.

## Orientation

| File | Purpose |
|---|---|
| `SpikeService.kt` | Foreground service; owns the single GVRET/USB stream |
| `OverlayController.kt` | The floating bubble and the navigation HUD |
| `Trips.kt` | Room database, trip accounting |
| `Exports.kt` | CSV export through MediaStore into `Downloads/Pacenote/` |
| `CalibrationActivity.kt` | Throttle range, gear ratios, redline, speed scale |
| `ReverseCapture.kt` | Guided neutral/reverse capture for a second car |

## Notes

- Location permission is used to read the joined Wi-Fi SSID, not to track you.
- The overlay uses a `TYPE_APPLICATION_OVERLAY` window owned by a service, so
  Compose needs its lifecycle/saved-state owners wired by hand (see
  `ComposeOverlay.kt`).
- `allowBackup="false"`: nothing leaves the phone on its own. Export is explicit.
