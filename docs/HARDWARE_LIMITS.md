# Hardware-Bounded Halo Limits

This project treats physical Halo behavior and the official Brilliant SDK protocol as the compatibility boundary. The emulator must not be used to justify a payload or API that stock Halo cannot consume.

## Hard protocol limits

| Item | Limit / rule | Source |
|---|---:|---|
| Data message payload | 65,535 bytes maximum | Official `BrilliantBle.send_message()` uses a uint16 length |
| First data packet | Message code + 2-byte big-endian length + payload | Official `data.lua` / `send_message()` |
| Subsequent data packets | Message code + payload | Official `data.lua` / `send_message()` |
| BLE write payload | Negotiated at connection time; use `max_data_payload()` | Official `brilliant_ble` implementation |
| Lua REPL payload | Must fit `max_lua_payload()` and Lua source/runtime memory | Official `send_lua()` |
| Bitmap indexed formats | 1, 2, or 4 bits per pixel; Lua format values 2, 4, or 16 | Halo firmware `lua_display.c` |
| Sprite header | `>HHBBB`: width, height, compressed, bpp, num_colors | Official `TxSprite` / `sprite.lua` |
| Polygon points | Maximum 64 points | Halo firmware `lua_display.c` |

## Display constraints

- Halo display surface exposed to Lua is 256×256.
- Halo draws immediately; there is no Frame-style `display.show()` requirement or hardware double buffer.
- The display starts in power-save and the app must call `frame.display.power_save(false)` before normal drawing.
- Text uses the built-in Dogica fonts and constrained sizes.
- Palette index 0 is transparent in bitmap rendering; palette offsets do not wrap and out-of-range indices are discarded.
- The stock Lua API does not provide alpha blending, rotation, arbitrary polygon fill, layers, or general-purpose GPU composition.

## Memory policy

The firmware contains an internal/external managed allocator, but usable memory depends on firmware configuration, concurrent services, Lua state, and message workload. Therefore the engine does not claim a universal free-Lua-memory number.

Instead, every target profile has explicit conservative budgets:

- `max_lua_source_bytes`: maximum source sent through the REPL path.
- `max_hrp_message_bytes`: maximum complete HRP message.
- `max_asset_bytes`: maximum single retained asset.
- `max_retained_asset_bytes`: maximum total retained assets.
- `max_peak_working_set_bytes`: estimated temporary memory during parse/decode/draw.

These are validation budgets, not claims about the exact allocator capacity. A physical-device calibration task must measure them against the firmware revision and app configuration before increasing them.

## Default stock profile

The initial stock profile is intentionally conservative:

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

The values above are engine safety guards. They must be configurable and validated, never silently bypassed. A 200×200 4-bit image has 20,000 packed pixel bytes before its palette/header and can fit below the default single-asset limit as binary data, but its complete Lua source representation does not.

## Required behavior

1. Large sprites use official binary message framing, not inline Lua escape strings.
2. Hosts use negotiated BLE payload sizes and wait for the official receiver-paced ACK where required.
3. The device app releases temporary buffers and runs garbage collection after large messages.
4. Any message, asset, atlas, or estimated working set above its profile is rejected before transmission.
5. Emulator tests use the same stock profile by default. An emulator-only profile must be opt-in, clearly named, and cannot be the default CI path.
