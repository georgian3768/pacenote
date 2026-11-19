# Changelog

## 1.0 — 2026-11-19

First public release. Renamed from the working name *Pocket Mechanic*; the
install id is `com.pacenote.app`.

**App**

- Live overlay: speed, rpm, gear, throttle, brake, eco score, coolant trend.
- Navigation-aware HUD — attaches to the navigation notification, returns to a
  bubble when navigation ends.
- Trip recording with frame history, distance, idle time and a 0–100
  smoothness score.
- CSV export of trips and raw frames to `Downloads/Pacenote/`.
- Per-car calibration: throttle range, gear ratios, redline, speed scale, units.
- ESP32 over USB (GVRET at 1 Mbaud) or Wi-Fi.

**Firmware**

- ESP32RET fork, listen-only, CAN0 (TWAI) at 500 kbps on GPIO 4/5.
- GVRET over USB serial and a TCP server; Wi-Fi AP with OTA.
- Merged single-file image published so flashing is one command at `0x0`.

**Decode**

- Validated DBC for the 2016 Maruti Suzuki Celerio (K10B): rpm on `0x124` and
  `0x13F`, throttle on `0x120`, brake and engine state on `0x318`/`0x120`.
- Documented the signals the car does **not** broadcast (clutch, gear position).

**Known limits**

- Decode is validated for one vehicle model.
- No fuel-rate signal, so no fuel economy.
- No OTA for the firmware: the partition layout has no slot.
