---
name: halo-engine
description: Compile and preview a Halo smart-glasses scene from a JSON scene description using the Halo Engine SDK (HSD/HRP/Lua runtime).
argument-hint: "[render request]"
triggers:
  - user
  - model
---

# Halo Engine Skill

Use this skill when the user wants to generate or preview visuals for the Brilliant Labs Halo smart glasses, or when asked to create a HUD / UI for a small circular display. The Halo Engine is the host-device runtime SDK that owns HSD compilation, HRP binary protocol, BLE transport, and the device-side Lua runtime.

## What it does

The Halo is a 256×256 circular RGB OLED smart glass. This skill compiles a JSON *Halo Scene Description* (HSD) into Lua commands for the Halo and can preview the result in the official `halo-emulator`.

## How to respond

1. Ask the user for the content they want displayed (or infer it from the request).
2. Write a HSD JSON file in `scenes/<name>.json` following the schema in `docs/INSTRUCTION_SET.md`.
3. Compile it with `python -m halo_engine.compile <scene>.json -o /tmp/<name>.lua`.
4. Preview it with `python -m halo_engine.preview /tmp/<name>.lua -o /tmp/<name>.png`.
5. Show the user the PNG and the generated Lua (or an excerpt).

## HSD conventions

- 0-indexed top-left coordinates.
- Colors are `#RRGGBB` or `0xRRGGBB`.
- Primitives: `text`, `rect`, `circle`, `line`, `polygon`, `point`, `sprite`, `group`, `row`, `column`.
- Fonts: `0` = Dogica, `1` = DogicaBold; `size` must be a multiple of 8.
- Display is circular; any pixel outside the 128 px radius will be clipped by the emulator/optics.

## Example

```json
{
  "version": "1.0",
  "device": "halo",
  "mode": "repl",
  "scene": {
    "width": 256,
    "height": 256,
    "bg": "#000000",
    "children": [
      { "type": "circle", "cx": 128, "cy": 128, "r": 120, "color": "#0050A0", "filled": false },
      { "type": "text", "x": 80, "y": 120, "text": "Hello", "font": 1, "size": 16, "color": "#FFFFFF" }
    ]
  }
}
```

## Available tools

- `python -m halo_engine.compile <scene>.json -o <out>.lua`
- `python -m halo_engine.preview <out>.lua -o <out>.png`
- `python -m halo_engine.mcp_server` (stdio MCP server for agent harnesses)
- `gradle :kotlin:run --args="compile <scene>.json -o <out>.lua"` (Kotlin engine)

## Capabilities and limitations

See `docs/CAPABILITIES.md` and `docs/FIRMWARE_NOTES.md` for the full display API and what can be boosted with or without firmware changes.
