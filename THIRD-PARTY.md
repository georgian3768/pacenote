# Third-party software

## Firmware (MIT)

The firmware is a fork of **[ESP32RET](https://github.com/collin80/ESP32RET)** by
Collin Kidder. The GVRET protocol, the CAN layer and most of `firmware/src/`
come from that project. Upstream copyright headers are kept in place.

| Project | Licence |
|---|---|
| [ESP32RET](https://github.com/collin80/ESP32RET) — Collin Kidder | MIT |
| [can_common](https://github.com/collin80/can_common) — Collin Kidder | MIT |
| [esp32_can](https://github.com/collin80/esp32_can) — Collin Kidder | MIT |
| [esp32_mcp2517fd](https://github.com/collin80/esp32_mcp2517fd) — Collin Kidder | MIT |
| [FastLED](https://github.com/FastLED/FastLED) | MIT |
| [AsyncTCP](https://github.com/ESP32Async/AsyncTCP) | LGPL-3.0 |
| [ESPAsyncWebServer](https://github.com/ESP32Async/ESPAsyncWebServer) | LGPL-3.0 |

Both LGPL libraries are pulled in as unmodified dependencies through
PlatformIO's `lib_deps`; they are not vendored into this repository, and the
exact versions are pinned in `firmware/platformio.ini`. Because the released
firmware image links them, its complete corresponding source — including those
libraries at the pinned versions and the build recipe — is available here, so
the image can be rebuilt and relinked.

## Android app (Apache-2.0)

| Dependency | Licence |
|---|---|
| AndroidX Core, Lifecycle, SavedState | Apache-2.0 |
| Jetpack Compose (BOM 2023.10.01) | Apache-2.0 |
| Room 2.5.2 | Apache-2.0 |
| Kotlin, kotlinx.coroutines | Apache-2.0 |
| OkHttp 4.12.0 (incl. `okhttp-sse`) | Apache-2.0 |
| Android Gradle Plugin, Gradle | Apache-2.0 |

## Data

`can/celerio.dbc` and the decode notes are original work, produced by
reverse-engineering a vehicle this project's author owns. They contain no
manufacturer documentation or proprietary material.
