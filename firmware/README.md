# Firmware — ESP32RET fork

A fork of [ESP32RET](https://github.com/collin80/ESP32RET) by Collin Kidder,
configured for Pacenote: one CAN channel (the ESP32's built-in TWAI
controller), 500 kbps, **listen-only**, streaming GVRET over USB serial at
1 Mbaud and over Wi-Fi.

## Build

```bash
pio run -e stable            # esp32dev, 4 MB flash
pio run -e stable -t upload  # 115200 baud deliberately; faster rates fail
pio device monitor -b 1000000
```

Platform is pinned to `espressif32@6.6.0` (Arduino core 2.0.14) on purpose:
core 3.0 removed the RMT drivers FastLED needs, and the firmware drives the
on-board LED.

`huge_app.csv` gives a 3 MB application partition. That leaves no OTA slot, so
the firmware is flashed over USB.

## Wiring

`CAN0.setCANPins(GPIO_NUM_4, GPIO_NUM_5)` → RX = GPIO 4, TX = GPIO 5, into an
SN65HVD230 at 3.3 V. CAN-H is OBD pin 6, CAN-L pin 14. The board is powered
from USB; do not take power from OBD pin 16.

Some SN65HVD230 breakouts have the TXD/RXD silkscreen swapped. If the bus looks
dead, swap those two pins before debugging anything else.

## Console

At 1 Mbaud. The ROM banner prints at 115200 for about a second before the
application starts — that is expected, not corruption.

```
Enabled CAN0 with speed 500000
Done with init
```

## Wi-Fi

Defaults (NVS empty): AP `A0RETSSID` at `192.168.4.1`, TCP server on 3232, OTA
enabled. The same GVRET stream is available there as on USB.

## Licence

MIT, inherited from ESP32RET. See `LICENSE` and `../THIRD-PARTY.md`.
