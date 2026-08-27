# Halo Graphic Engine Test Process and Findings

## Purpose

This document records the validation process used to check the example scenes against the current conservative Halo hardware profile. The goal is to ensure that the emulator is not treated as evidence for capabilities beyond what the real Halo can reasonably consume.

The profile and its rationale are defined in [`HARDWARE_LIMITS.md`](HARDWARE_LIMITS.md).

## Test environment

- Host OS: Windows
- Python: Python 3.13
- Kotlin/JVM: Gradle Kotlin/JVM project
- Emulator: official `halo-emulator` package, version 2.0.1
- SDK reference: vendored Brilliant SDK under `vendor/brilliant_sdk`
- Firmware reference: vendored Halo firmware under `vendor/halo-firmware`

## Active safety profile

```json
{
  "display_width": 256,
  "display_height": 256,
  "max_message_bytes": 65535,
  "max_lua_source_bytes": 4096,
  "max_hrp_message_bytes": 32768,
  "max_asset_bytes": 24576,
  "max_retained_asset_bytes": 49152,
  "max_peak_working_set_bytes": 65536,
  "max_polygon_points": 64
}
```

These are engine validation budgets, not claims about the exact free memory of every Halo firmware build. A physical-device measurement pass is required before changing them.

## Process

### 1. Enumerate scene inputs

The scene examples were enumerated from `scenes/*.json`:

- `bar_chart.json`
- `btc_chart.json`
- `icon_test.json`
- `navigation_hud.json`
- `results_table.json`
- `running_hud.json`
- `venus_image.json`

### 2. Check inline Lua mode

Inline Lua mode is intended only for small REPL commands. Each scene was compiled with:

```powershell
python -m halo_engine.compile scenes/<name>.json --out C:/tmp/halo-example-check/<name>.lua
```

The compiler validates the UTF-8 byte length of the complete Lua source against `max_lua_source_bytes`. It rejects oversized output before transmission.

### 3. Check binary HRP mode

Each scene was compiled to HRP v1 with:

```powershell
python -m halo_engine.hrp_compile scenes/<name>.json --out C:/tmp/halo-example-check/<name>.hrp
```

HRP output uses the official Brilliant data-message payload model and is checked against the conservative HRP and official uint16 message limits.

### 4. Execute HRP in the emulator

All scenes were executed through the project HRP runtime using the official `data.min.lua` reassembly library:

```powershell
cd python
python tools/check_hrp_examples.py
```

The checker:

1. Copies `lua/he_runtime.lua` into an isolated emulator sandbox.
2. Copies the official `data.min.lua` library into that sandbox.
3. Starts the Lua runtime.
4. Compiles each scene to HRP.
5. Wraps the HRP payload using the official message-code and uint16-length framing.
6. Injects the message into `HaloEmulator`.
7. Waits for the runtime to process it.
8. Reports runtime errors or truncation errors.

The runtime test suite can also be run with:

```powershell
cd python
python -m pytest -q
```

The Kotlin verification is:

```powershell
gradle :kotlin:build
```

## Findings

| Scene | Inline Lua result | Binary HRP result | HRP runtime result |
|---|---:|---:|---|
| `bar_chart` | Pass — 1,936 bytes | Pass — 583 bytes | Pass |
| `btc_chart` | Pass — 2,239 bytes | Pass — 689 bytes | Pass |
| `icon_test` | Rejected — 8,534 bytes | Pass — 2,134 bytes | Pass |
| `navigation_hud` | Pass — 376 bytes | Pass — 110 bytes | Pass |
| `results_table` | Pass — 2,018 bytes | Pass — 585 bytes | Pass |
| `running_hud` | Pass — 658 bytes | Pass — 183 bytes | Pass |
| `venus_image` | Rejected — 80,556 bytes | Pass — 20,152 bytes | Pass |

### Important result: inline image rejection

The Venus image is approximately 80 KB when represented as a Lua string containing escaped bytes. That path is now rejected by the compiler because it exceeds the 4 KB conservative Lua source budget.

The same image compiles to a 20,152-byte HRP payload. Its indexed sprite data is sent as binary data rather than Lua source, and it remains below the current 24,576-byte single-asset budget and 32,768-byte HRP budget.

The 64×64 icon is also rejected in inline mode because its escaped Lua representation is 8,534 bytes. It succeeds in HRP mode at 2,134 bytes.

### Runtime findings

All seven scenes produced `EMU_PASS` when delivered to `he_runtime.lua` through the official `data.min.lua` reassembly path. This includes the Venus scene and verifies the HRP parser's handling of:

- Large indexed sprite resources
- Sprite placement
- Immediate Halo drawing
- Text following a large sprite command
- Circle overlays and captions

A text-command offset bug was found during this pass: the runtime initially read the text length field one byte early. The parser was corrected and the full Venus scene was rerun successfully.

## Test suite results

Python project tests:

```text
11 passed
```

Kotlin project build and tests:

```text
BUILD SUCCESSFUL
```

The Python tests cover:

- HSD-to-Lua compilation
- Lua hardware-size rejection
- HRP wire-format round trips
- Big-endian field layout
- Polygon point limits
- Scene-to-HRP compilation
- HRP runtime execution in the official emulator
- Stable-ID scene diffs

The Kotlin tests cover:

- HRP byte-layout parity for representative commands
- Oversized HRP rejection
- HSD compiler output

## Interpretation and limitations

These tests establish that the generated data obeys the current protocol and engine safety budgets, and that the project Lua runtime can process the supported HRP subset in the official emulator.

They do **not** yet establish:

- Actual throughput on a physical Halo
- Actual peak RAM usage on every firmware revision
- Android BluetoothGatt callback/ACK behavior
- Performance under sustained animation workloads
- Correct operation after connection loss or dropped packets
- Compatibility with a custom firmware build

The emulator remains a validation tool for the device-side Lua behavior. It must not be configured with more permissive limits than the stock hardware profile for normal tests.

## Next hardware validation steps

1. Run the HRP examples on a physical Halo using stock firmware.
2. Measure negotiated MTU, data-message throughput, end-to-end latency, and ACK timing.
3. Capture device logs and peak allocation behavior while uploading and drawing the Venus asset.
4. Verify reconnect and full-redraw behavior after interrupted transfers.
5. Update the conservative profile only from measured evidence.
6. Implement and test the Android BluetoothGatt transport before claiming mobile-host readiness.
7. Defer custom firmware primitives until the stock-compatible path is stable.
