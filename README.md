# Pacenote

**The co-driver your car never had.**

Pacenote turns a small ESP32 and an OBD-II cable into a live cockpit for a car
whose dashboard tells you nothing useful: speed, rpm, gear, throttle, brake and
a per-trip smoothness score, drawn on a floating overlay over whatever is on
screen — including your navigation app. Every drive is recorded and can be
exported as CSV.

It is **read-only by construction**. Pacenote listens to the CAN bus and has no
code path that transmits on it.

Built and verified against a **2016 Maruti Suzuki Celerio (K10B, 5-speed
manual, 500 kbps CAN)**. The app is car-agnostic; the signal decode is not —
other cars need their own DBC (see [Signal decode](#signal-decode)).

---

## What it does

- **Live telemetry** — rpm, vehicle speed, gear, throttle position, brake
  state, engine-running state, coolant trend, eco score.
- **Floating overlay** — a compact bubble that expands into a full HUD. The HUD
  attaches to the navigation notification, so it sits on top of Maps while you
  drive and returns to a bubble when navigation ends.
- **Trip recording** — every drive is stored locally (Room/SQLite) with its
  frame history, distance, idle time and eco score.
- **CSV export** — trips and raw frames to `Downloads/Pacenote/`, ready for a
  spreadsheet or `can/analyze_reverse.py`.
- **Per-car calibration** — idle/full throttle raw values, gear ratios, redline,
  speed scale, units. Nothing is hard-coded to one vehicle.
- **Wireless option** — the firmware serves the same stream over Wi-Fi, so the
  phone does not need the USB cable.

## Hardware

| Part | Note |
|---|---|
| ESP32 dev board (`esp32dev`, 4 MB flash) | Powered over USB. Do **not** wire OBD pin 16 to it. |
| SN65HVD230 3.3 V CAN transceiver | 3.3 V logic, no level shifting needed |
| OBD-II male connector or cable | CAN-H = pin 6, CAN-L = pin 14 |
| USB cable + OTG adapter | Phone side |

Wiring (firmware default, `CAN0.setCANPins(GPIO_NUM_4, GPIO_NUM_5)`):

| Transceiver | ESP32 |
|---|---|
| TXD (driver input) | GPIO 5 |
| RXD (receiver output) | GPIO 4 |
| VCC | 3.3 V |
| GND | GND |

> **Check the silkscreen.** On the unit this was developed with, the TXD/RXD
> labels are swapped relative to the datasheet pinout. With the nominal wiring
> the bus was silent; swapping the two data pins produced live frames. If you
> see nothing, swap them before assuming a firmware fault.

## Install the app

Download `Pacenote-1.0.apk` from [Releases](../../releases), open it on the
phone, and allow installing from unknown sources. Android 10 or newer
(`minSdk 29`).

Grant on first run:

- **Display over other apps** — for the overlay
- **Notification listener access** — so the HUD knows when navigation starts
  and ends
- **Notifications** — the foreground service notification
- **Battery optimisation exemption** — otherwise the service gets killed on a
  long drive

To get update notifications, add the repository URL in
[Obtainium](https://obtainium.imranr.dev/). Every GitHub release becomes an
update.

## Flash the firmware

`pacenote-esp32-1.0-full.bin` is a merged image: bootloader, partition table and
application in one file. Flash it at offset `0x0`.

```bash
esptool.py --chip esp32 write_flash 0x0 pacenote-esp32-1.0-full.bin
```

Or build it yourself:

```bash
cd firmware
pio run -e stable
pio run -e stable -t upload      # 115200 baud on purpose; higher rates fail
```

The console runs at **1,000,000 baud** and speaks GVRET, which is what SavvyCAN
and Pacenote expect. The ESP32 also starts a Wi-Fi access point
(`A0RETSSID`, `192.168.4.1:3232`) with the same stream, plus OTA.

## Build the app from source

Requirements: JDK 17, Android SDK with platform 33 and build-tools 34.

```bash
cd android
echo "sdk.dir=$HOME/android-sdk" > local.properties   # or point at your SDK
./gradlew :app:assembleDebug
```

A release build is signed only if `android/keystore.properties` exists:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release variant builds unsigned, which is deliberate: nobody
needs the project's private key to compile the project.

## Signal decode

`can/celerio.dbc` is the validated decode for the Celerio, cross-checked with
`cantools` against a 215,113-frame parked capture. `can/notes.md` records the
evidence, including the signals that turned out **not** to exist on the bus
(clutch position, gear position — the car does not broadcast them).

Confirmed:

| Signal | Frame | Bytes | Format |
|---|---|---|---|
| Engine rpm | `0x124` | D2–D3 | 16-bit BE, ×0.25 |
| Engine rpm (cross-check) | `0x13F` | D1–D2 | 16-bit LE, ×0.25 |
| Throttle (2 channels) | `0x120` | D3, D5 | 8-bit, idle ≈37 / WOT ≈239 |
| Brake switch | `0x318` | D1 bit 2 | 1 = pressed |
| Engine running | `0x120` D1/D4, `0x318` D2 | — | status bytes |

Gear is **computed** from engine rpm and road speed against the calibrated
ratios, because no gear signal exists on this bus. Speed comes from a wheel-speed
frame scaled by a per-car calibration factor.

## Repository layout

```
android/     Kotlin app (Compose overlay, Room trip store) + shiftassist module
firmware/    ESP32RET fork: GVRET over USB/Wi-Fi, listen-only CAN
can/         the validated DBC, decode notes, capture/analysis tools
docs/        design and rationale: overlay, coaching, eco score, reverse capture
```

The Kotlin package is still `com.pocketmechanic.spike` and the Gradle root
project is still `PocketMechanicSpike`. That is a deliberate legacy: the app was
renamed to Pacenote at 1.0, and only the install identity
(`com.pacenote.app`) and user-visible strings were changed. Renaming source
packages would have bought nothing a user can see.

## Safety and scope

- **Listen-only.** CAN transmission is disabled at init and no feature calls it.
  Nothing here can command the engine, brakes or transmission.
- The overlay is for **passengers** or for a phone in a cradle the driver does
  not touch. Do not interact with the phone while driving.
- Reading the bus through OBD-II may affect your warranty and may not be legal
  everywhere. Check before you use it on a public road.
- Not affiliated with, endorsed by or supported by Maruti Suzuki. "Celerio" is
  used descriptively.
- Calibration defaults are for one specific car. Do not assume they are right
  for yours.

## Privacy

No accounts, no analytics, no network requests. The app stores trips in a local
SQLite database (`android:allowBackup="false"`, so nothing leaves the phone
automatically). If you want your data out, export a trip as CSV.

## Limitations

- The DBC covers **one** vehicle model. Everything else needs the same reverse
  engineering, and `docs/REVERSE-CAPTURE.md` is the method.
- Fuel consumption is not available: the bus carries no fuel-rate signal.
- The firmware has no OTA path (the partition layout leaves no slot). Flash over
  USB.
- The app targets SDK 33 and has not been tested beyond that.

## Licence

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY.md](THIRD-PARTY.md) for the
upstream projects the firmware builds on.
