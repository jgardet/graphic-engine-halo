# Graphic Engine Halo — Agent Guide

## What this project is

A Kotlin-first / Python-reference graphics engine for the Brilliant Labs Halo smart glasses. It turns a high-level, JSON "Halo Scene Description" (HSD) into Lua or binary render packets and sends them to the glasses over BLE.

## How to work with it

1. **Read the instruction set** at `docs/INSTRUCTION_SET.md` before generating HSD.
2. **Use the Python reference** for fast iteration and emulator tests.
3. **Use the Kotlin engine** as the production host runtime.
4. **Use the Devin skill** (`/halo-engine`) to get the prompt template for rendering a scene.

## Common commands

```bash
# Python (run from the `python/` directory after installing the package)
cd python
python -m halo_engine.compile ../scenes/running_hud.json --out /tmp/hud.lua
python -m halo_engine.preview /tmp/hud.lua --out /tmp/hud.png
python -m halo_engine.hrp_compile ../scenes/running_hud.json --out /tmp/hud.hrp
python -m halo_engine.mcp_server        # stdio MCP server
cd ..

# Kotlin
gradle :kotlin:build
gradle :kotlin:run --args="compile scenes/running_hud.json -o /tmp/hud.lua"

# Tests
python -m pytest -v                     # Python
gradle :kotlin:test                     # Kotlin
```

## Project rules

- HSD is **0-indexed** top-left; the engine adds 1 before emitting Lua.
- Colors are `#RRGGBB` hex strings, `0xRRGGBB` integers, or a named color.
- Fonts: `0` = Dogica, `1` = DogicaBold; `size` must be a multiple of 8.
- For `runtime` mode, the device-side `lua/he_runtime.lua` must be uploaded first.
- Always verify generated Lua in the `halo_emulator` before shipping to hardware.
- The `brilliant_sdk/` and `halo-firmware/` directories are reference vendored repos.
- Non-visual streaming (mic, photo, battery, etc.) uses the engine-level
  `HaloMessage` and `HaloSession` abstractions in `halo.engine`. Transports
  expose a `messages: Flow<HaloMessage>` stream; `HaloSession.collect()`
  handles chunk collection, timeout, and cancellation for any streaming
  capability that follows the same start/chunk/final pattern.
