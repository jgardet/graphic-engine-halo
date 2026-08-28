# Halo Scene Description (HSD) — Agent Instruction Set

This is the *agent-facing* instruction set. It is intentionally familiar: it looks like a JSON SVG/GameObject scene graph. Agents produce HSD; the engine compiles it into Lua or HRP binary.

## Conventions

- Coordinates are **0-indexed**, top-left origin, with `+x` right and `+y` down.
- The engine translates to Halo's 1-indexed Lua API automatically.
- Colors may be `#RRGGBB` hex strings, `0xRRGGBB` integers, or a named color from the Halo palette.
- Angles are in degrees, but the firmware has no rotation support; the engine pre-rotates content on the host if requested.
- All numeric values are integers unless specified otherwise.

## Top-level scene

```json
{
  "version": "1.0",
  "device": "halo",
  "mode": "repl",
  "scene": {
    "width": 256,
    "height": 256,
    "bg": "#000000",
    "brightness": 50,
    "power_save": false,
    "pan": [0, 0],
    "children": []
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | string | HSD version |
| `device` | string | `"halo"` or `"frame"` (default `"halo"`) |
| `mode` | string | `"repl"` (Lua string) or `"runtime"` (HRP binary) |
| `scene.width/height` | int | Logical display size; usually 256×256 |
| `scene.bg` | color | Background color, rendered with `frame.display.clear()` |
| `scene.brightness` | int | 0–100 (optional) |
| `scene.power_save` | bool | `false` to resume display (default) |
| `scene.pan` | [int, int] | Pan offset [-50, 50] (optional) |

## Common element fields

All elements support:

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | Element kind (see below) |
| `id` | string | Optional stable ID for diff updates |
| `x`, `y` | int | Position (0-indexed) |
| `visible` | bool | Whether to emit draw calls (default `true`) |
| `clip` | bool | Clip to circular display (default `true`) |
| `alpha` | float | Not supported by firmware; ignored unless using pre-composited sprites |

## Primitives

### `text`

```json
{
  "type": "text",
  "x": 10,
  "y": 120,
  "text": "12:34",
  "font": 1,
  "size": 32,
  "scale": 1,
  "color": "#FFFFFF"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `text` | string | Content |
| `font` | int | `0` = Dogica, `1` = DogicaBold |
| `size` | int | Multiple of 8 (default `8`) |
| `scale` | int | Additional integer scale (default `1`) |
| `color` | color | Text color |

### `rect`

```json
{
  "type": "rect",
  "x": 20,
  "y": 20,
  "w": 216,
  "h": 216,
  "color": "#0050A0",
  "filled": false
}
```

### `circle`

```json
{
  "type": "circle",
  "cx": 128,
  "cy": 128,
  "r": 120,
  "color": "#FFFFFF",
  "filled": false
}
```

### `line`

```json
{
  "type": "line",
  "x0": 0,
  "y0": 128,
  "x1": 256,
  "y1": 128,
  "color": "#FF0000"
}
```

### `polygon`

```json
{
  "type": "polygon",
  "points": [[10,10], [50,10], [30,50]],
  "color": "#00FF00"
}
```

- Up to 64 points.
- 3 points are filled as a triangle; more points draw an outline.

### `pixel` / `point`

```json
{
  "type": "point",
  "x": 100,
  "y": 100,
  "color": "#FF00FF"
}
```

### `sprite`

```json
{
  "type": "sprite",
  "src": "icon.png",
  "x": 100,
  "y": 100,
  "w": 64,
  "h": 64,
  "bpp": 4,
  "palette_offset": 0,
  "scale_x": 1,
  "scale_y": 1
}
```

| Field | Type | Description |
|-------|------|-------------|
| `src` | string | Image file path or base64 PNG data |
| `w`, `h` | int | Target sprite size (optional, derived from image) |
| `bpp` | int | `1`, `2`, or `4` (default `4`) |
| `palette_offset` | int | 0–15, added to non-zero indices (default `0`) |
| `scale_x`, `scale_y` | int | Only valid for indexed sprites, integer scale |

Sprites are quantized to ≤16 colors and transmitted as a binary asset, then drawn with `frame.display.bitmap()`.

### `group`

```json
{
  "type": "group",
  "x": 10,
  "y": 10,
  "children": [
    { "type": "text", "x": 0, "y": 0, "text": "Hello" }
  ]
}
```

Children are rendered relative to the group's `x`, `y`.

## Layout helpers

### `row` and `column`

These are convenience group types that distribute children along an axis.

```json
{
  "type": "row",
  "x": 10,
  "y": 10,
  "spacing": 4,
  "children": [
    { "type": "text", "text": "A" },
    { "type": "text", "text": "B" }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `spacing` | int | Gap between children |
| `align` | string | `"start"`, `"center"`, `"end"` |

## Example: running HUD

```json
{
  "version": "1.0",
  "device": "halo",
  "scene": {
    "width": 256,
    "height": 256,
    "bg": "#000000",
    "children": [
      { "type": "circle", "cx": 128, "cy": 128, "r": 125, "color": "#0050A0", "filled": false },
      { "type": "circle", "cx": 128, "cy": 128, "r": 122, "color": "#002060", "filled": false },
      { "type": "text", "x": 108, "y": 30, "text": "145", "font": 0, "size": 16, "color": "#FF4444" },
      { "type": "circle", "cx": 100, "cy": 38, "r": 4, "color": "#FF4444", "filled": true },
      { "type": "text", "x": 78, "y": 90, "text": "5:30", "font": 1, "size": 32, "color": "#FFFFFF" },
      { "type": "text", "x": 96, "y": 130, "text": "/km", "font": 0, "size": 16, "color": "#808080" },
      { "type": "text", "x": 96, "y": 165, "text": "2.5km", "font": 0, "size": 16, "color": "#44FF44" },
      { "type": "text", "x": 96, "y": 190, "text": "12:34", "font": 0, "size": 16, "color": "#FFFFFF" },
      { "type": "line", "x0": 50, "y0": 155, "x1": 206, "y1": 155, "color": "#303030" }
    ]
  }
}
```

## Compilation modes

### `repl` (default)

The engine emits a single Lua string that can be sent to the Halo REPL. This is the fastest to iterate on but has overhead per frame and is limited by Lua string/BLE size.

### `runtime`

The engine emits a binary **Halo Render Packet** (HRP) to be sent to a small device-side runtime (`lua/he_runtime.lua`). This is more efficient for animations and sprites.
