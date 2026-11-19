# Releasing Pacenote

One person, one machine, no CI. This is the whole procedure.

## Version numbers

`versionCode` must strictly increase; `versionName` is what users see. Both live
in `android/app/build.gradle.kts`. The firmware carries no version number yet —
the release tag is the version.

## 1. Prepare

```bash
cd android
./gradlew :shiftassist:test :app:assembleStaging :app:assembleDebug
```

The JVM tests in `shiftassist` are the only automated suite. Run them; they
cover the decode math, gear selection, eco scoring and the CSV writer.

Bump `versionCode`/`versionName`, update `CHANGELOG.md`, commit.

## 2. Build the release APK

Release signing needs `android/keystore.properties` (gitignored, never commit it):

```properties
storeFile=/absolute/path/to/pacenote-release.jks
storePassword=...
keyAlias=pacenote
keyPassword=...
```

```bash
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
cp app/build/outputs/apk/release/app-release.apk ../dist/Pacenote-<version>.apk
```

`-x lintVital*` is only needed in offline builds where `lint-gradle` is not in
the local cache; in a normal build let lint run.

Verify before shipping:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs dist/Pacenote-<version>.apk
$ANDROID_HOME/build-tools/34.0.0/aapt dump badging dist/Pacenote-<version>.apk | head -2
```

The certificate digest must match the previous release. **Losing the keystore
means no existing install can ever be updated** — back it up in two places.

## 3. Build the firmware image

```bash
cd firmware
pio run -e stable
esptool.py --chip esp32 merge_bin \
  -o ../dist/pacenote-esp32-<version>-full.bin \
  --flash_mode dio --flash_freq 40m --flash_size 4MB \
  0x1000 .pio/build/stable/bootloader.bin \
  0x8000 .pio/build/stable/partitions.bin \
  0x10000 .pio/build/stable/firmware.bin
```

The partition table (`huge_app.csv`) is single-app, so there is no `otadata` or
`boot_app0` entry to merge.

## 4. Checksums

```bash
cd dist
sha256sum Pacenote-<version>.apk pacenote-esp32-<version>-full.bin > SHA256SUMS.txt
```

## 5. Publish

```bash
git tag -a v<version> -m "Pacenote <version>"
git push origin main v<version>
gh release create v<version> --title "Pacenote <version>" \
  --notes-file RELEASE-NOTES.md \
  dist/Pacenote-<version>.apk \
  dist/pacenote-esp32-<version>-full.bin \
  dist/SHA256SUMS.txt
```

Release notes must always state, in plain words: **listen-only**, ESP32 is
powered over USB (not from the car), the wiring caveat, and anything that was
not verified.

## 6. What users do

Releases page → APK, or add the repository to
[Obtainium](https://obtainium.imranr.dev/) to be notified of updates.

Never publish a `staging` or `debug` build: the former is debuggable and exists
only to move data between installs.
