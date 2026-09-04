# Halo Engine — Agent Guide

## What this project is

The host-device runtime SDK for the Brilliant Labs Halo smart glasses. It owns the firmware-facing surface: HSD scene compilation, HRP binary render protocol, BLE transport, the device-side Lua runtime (`he_runtime.lua`), and streaming primitives (`HaloMessage`, `HaloSession`) for microphone, speaker, camera, battery, and input events.

The engine is deliberately unaware of higher-level agent concerns: generic sense contracts (`agent-senses/core`), agent tools and prompts (`dsh-android`), on-device models (Gemma), product templates, and chat/transcript semantics all live outside this repository.

## How to work with it

1. **Read the instruction set** at `docs/HSD_INSTRUCTION_SET.md` before generating HSD.
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
.\gradlew.bat :kotlin:build
.\gradlew.bat :kotlin:run --args="compile scenes/running_hud.json -o /tmp/hud.lua"

# Tests
python -m pytest -v                     # Python
.\gradlew.bat :kotlin:test              # Kotlin
```

## Project rules

- HSD is **0-indexed** top-left; the engine adds 1 before emitting Lua.
- Colors are `#RRGGBB` hex strings, `0xRRGGBB` integers, or a named color.
- Fonts: `0` = Dogica, `1` = DogicaBold; `size` must be a multiple of 8.
- The primary compiler currently supports `repl` mode; use `halo_engine.hrp_compile` for hardware-bounded HRP payloads.
- For the optimized HRP runtime, the device-side `lua/he_runtime.lua` must be uploaded first.
- Always verify generated Lua in the `halo_emulator` before shipping to hardware.
- Optional Brilliant SDK and firmware references may be supplied under an ignored local `vendor/` directory; they are not part of this checkout.
- Non-visual streaming (mic, photo, battery, etc.) uses the engine-level
  `HaloMessage` and `HaloSession` abstractions in `halo.engine`. Transports
  expose a `messages: Flow<HaloMessage>` stream; `HaloSession.collect()`
  handles chunk collection, timeout, and cancellation for any streaming
  capability that follows the same start/chunk/final pattern.
