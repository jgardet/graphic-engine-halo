"""HSD -> Lua / HRP compiler."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .colors import color_to_hex_str
from .sprite import pack_sprite, pack_bits
from .hrp import HrpBuilder
from .hsd_validator import HsdValidator
from .limits import STOCK_HALO, HaloLimits, validate_lua_size


class LuaBuilder:
    """Accumulates Lua commands."""

    def __init__(self):
        self.parts: list[str] = []

    def append(self, cmd: str) -> None:
        self.parts.append(cmd)

    def __str__(self) -> str:
        # No semicolons needed; the Lua REPL executes the string as a chunk.
        # We do not add trailing semicolon so the line is a single statement.
        return " ".join(self.parts)


def _lua_string_literal(s: str) -> str:
    """Escape a string for Lua single-quoted string."""
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t")
    return f"'{s}'"


def _lua_bool(b: Any) -> str:
    return "true" if b else "false"


def _hex_bytes(data: bytes) -> str:
    r"""Encode bytes as a Lua string of \xNN escapes."""
    return "".join(f"\\x{b:02X}" for b in data)


def compile_scene(scene: dict[str, Any], limits: HaloLimits = STOCK_HALO) -> str:
    """Compile an HSD scene to a hardware-bounded Lua REPL command."""
    HsdValidator(limits=limits).validate(scene)
    root = scene.get("scene", {})
    mode = scene.get("mode", "repl")
    if mode != "repl":
        raise NotImplementedError(f"mode '{mode}' is not implemented yet in this reference")

    lua = LuaBuilder()

    # Background
    bg = root.get("bg", "#000000")
    lua.append(f"frame.display.clear({color_to_hex_str(bg)})")

    # Power save
    if root.get("power_save", False) is False:
        lua.append("frame.display.power_save(false)")

    # Brightness
    brightness = root.get("brightness")
    if brightness is not None:
        lua.append(f"frame.display.brightness({int(brightness)})")

    # Pan
    pan = root.get("pan")
    if pan:
        lua.append(f"frame.display.set_pan({int(pan[0])},{int(pan[1])})")

    # Children
    for child in root.get("children", []):
        _compile_element(child, lua, dx=0, dy=0)

    # Optional trailing print for ack
    lua.append("print('ok')")
    result = str(lua)
    validate_lua_size(result, limits)
    return result


def _compile_element(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    if not el.get("visible", True):
        return

    etype = el.get("type", "").lower()

    # Layout helpers
    if etype == "group":
        _compile_group(el, lua, dx, dy)
    elif etype == "row":
        _compile_row(el, lua, dx, dy)
    elif etype == "column":
        _compile_column(el, lua, dx, dy)
    elif etype == "text":
        _compile_text(el, lua, dx, dy)
    elif etype == "rect":
        _compile_rect(el, lua, dx, dy)
    elif etype == "circle":
        _compile_circle(el, lua, dx, dy)
    elif etype == "line":
        _compile_line(el, lua, dx, dy)
    elif etype == "polygon":
        _compile_polygon(el, lua, dx, dy)
    elif etype in ("point", "pixel"):
        _compile_point(el, lua, dx, dy)
    elif etype == "sprite":
        _compile_sprite(el, lua, dx, dy)
    else:
        raise ValueError(f"Unknown element type: {etype}")


def _compile_text(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    x = int(el.get("x", 0)) + dx + 1
    y = int(el.get("y", 0)) + dy + 1
    font = int(el.get("font", 0))
    size = int(el.get("size", 8))
    scale = int(el.get("scale", 1))
    text = el.get("text", "")
    color = color_to_hex_str(el.get("color", "#FFFFFF"))

    lua.append(f"frame.display.set_font({font},{size},{scale})")
    lua.append(f"frame.display.text({_lua_string_literal(text)},{x},{y},{color})")


def _compile_rect(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    x = int(el.get("x", 0)) + dx + 1
    y = int(el.get("y", 0)) + dy + 1
    w = int(el.get("w", 0))
    h = int(el.get("h", 0))
    color = color_to_hex_str(el.get("color", "#FFFFFF"))
    filled = _lua_bool(el.get("filled", False))
    lua.append(f"frame.display.rect({x},{y},{w},{h},{color},{filled})")


def _compile_circle(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    cx = int(el.get("cx", 0)) + dx + 1
    cy = int(el.get("cy", 0)) + dy + 1
    r = int(el.get("r", 0))
    color = color_to_hex_str(el.get("color", "#FFFFFF"))
    filled = _lua_bool(el.get("filled", False))
    lua.append(f"frame.display.circle({cx},{cy},{r},{color},{filled})")


def _compile_line(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    x0 = int(el.get("x0", 0)) + dx + 1
    y0 = int(el.get("y0", 0)) + dy + 1
    x1 = int(el.get("x1", 0)) + dx + 1
    y1 = int(el.get("y1", 0)) + dy + 1
    color = color_to_hex_str(el.get("color", "#FFFFFF"))
    lua.append(f"frame.display.line({x0},{y0},{x1},{y1},{color})")


def _compile_polygon(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    points = el.get("points", [])
    if not points:
        return
    flat = []
    for p in points:
        flat.append(int(p[0]) + dx + 1)
        flat.append(int(p[1]) + dy + 1)
    color = color_to_hex_str(el.get("color", "#FFFFFF"))
    coords = ",".join(str(v) for v in flat)
    lua.append(f"frame.display.polygon({{{coords}}},{color})")


def _compile_point(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    x = int(el.get("x", 0)) + dx + 1
    y = int(el.get("y", 0)) + dy + 1
    color = color_to_hex_str(el.get("color", "#FFFFFF"))
    lua.append(f"frame.display.set_pixel({x},{y},{color})")


def _compile_sprite(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    src = el.get("src")
    x = int(el.get("x", 0)) + dx
    y = int(el.get("y", 0)) + dy
    w = el.get("w")
    h = el.get("h")
    bpp = int(el.get("bpp", 4))
    palette_offset = int(el.get("palette_offset", 0))
    x_scale = int(el.get("scale_x", 1))
    y_scale = int(el.get("scale_y", 1))

    if src is None:
        raise ValueError("sprite element must have a 'src' field")

    # Pack the sprite (or use precomputed data if present)
    if "pixel_data" in el and "palette_data" in el:
        sprite = _sprite_from_inline(el)
    else:
        sprite = pack_sprite(src, w, h, bpp)

    packed = pack_bits(sprite.pixel_data, sprite.bpp)
    pixel_str = _hex_bytes(packed)
    palette_str = _hex_bytes(sprite.palette_data)
    fmt = sprite.color_format

    lua.append(
        f"frame.display.bitmap({x + 1},{y + 1},{sprite.width},{fmt},{palette_offset},"
        f'"{pixel_str}",{{palette_data="{palette_str}",x_scale={x_scale},y_scale={y_scale}}})'
    )


def _sprite_from_inline(el: dict[str, Any]):
    from .sprite import SpriteAsset

    return SpriteAsset(
        width=int(el["w"]),
        height=int(el["h"]),
        bpp=int(el.get("bpp", 4)),
        num_colors=int(el.get("num_colors", 2 ** int(el.get("bpp", 4)))),
        palette_data=bytes(el["palette_data"]),
        pixel_data=bytes(el["pixel_data"]),
    )


def _compile_group(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    gdx = dx + int(el.get("x", 0))
    gdy = dy + int(el.get("y", 0))
    for child in el.get("children", []):
        _compile_element(child, lua, gdx, gdy)


def _compile_row(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    start_x = dx + int(el.get("x", 0))
    y = dy + int(el.get("y", 0))
    spacing = int(el.get("spacing", 0))
    current_x = start_x
    for child in el.get("children", []):
        _compile_element(child, lua, current_x, y)
        current_x += _estimated_width(child) + spacing


def _compile_column(el: dict[str, Any], lua: LuaBuilder, dx: int, dy: int) -> None:
    x = dx + int(el.get("x", 0))
    start_y = dy + int(el.get("y", 0))
    spacing = int(el.get("spacing", 0))
    current_y = start_y
    for child in el.get("children", []):
        _compile_element(child, lua, x, current_y)
        current_y += _estimated_height(child) + spacing


def _estimated_width(el: dict[str, Any]) -> int:
    if "w" in el:
        return int(el["w"])
    if el.get("type") == "text":
        size = int(el.get("size", 8))
        return len(el.get("text", "")) * size // 2
    if el.get("type") == "circle":
        return int(el.get("r", 0)) * 2
    if el.get("type") == "rect":
        return int(el.get("w", 0))
    return 16


def _estimated_height(el: dict[str, Any]) -> int:
    if "h" in el:
        return int(el["h"])
    if el.get("type") == "text":
        return int(el.get("size", 8))
    if el.get("type") == "circle":
        return int(el.get("r", 0)) * 2
    if el.get("type") == "rect":
        return int(el.get("h", 0))
    return 16


def compile_scene_file(path: str | Path) -> str:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return compile_scene(data)
