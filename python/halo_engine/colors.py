"""Color helpers for the Halo engine."""

from __future__ import annotations

import re

# Named colors matching the Halo firmware default palette
NAMED_PALETTE = {
    "void": 0x000000,
    "black": 0x000000,
    "white": 0xFFFFFF,
    "grey": 0x808080,
    "gray": 0x808080,
    "red": 0xFF0000,
    "pink": 0xFFC0CB,
    "darkbrown": 0x654321,
    "brown": 0x963200,
    "orange": 0xFFA500,
    "yellow": 0xFFFF00,
    "darkgreen": 0x006400,
    "green": 0x00FF00,
    "lightgreen": 0x90EE90,
    "nightblue": 0x191970,
    "seablue": 0x0000CD,
    "skyblue": 0x87CEEB,
    "cloudblue": 0xF0F8FF,
}


def parse_color(value: str | int) -> int:
    """Convert a color string or int to 0xRRGGBB integer."""
    if isinstance(value, int):
        return value & 0xFFFFFF
    if not isinstance(value, str):
        raise ValueError(f"Color must be a string or int, got {type(value)}")

    value = value.strip().lower()

    if value in NAMED_PALETTE:
        return NAMED_PALETTE[value]

    if value.startswith("#"):
        value = value[1:]

    if value.startswith("0x"):
        value = value[2:]

    if re.fullmatch(r"[0-9a-f]{6}", value):
        return int(value, 16)

    if re.fullmatch(r"[0-9a-f]{3}", value):
        return int("".join(c * 2 for c in value), 16)

    raise ValueError(f"Unrecognized color: {value}")


def color_to_hex_str(color: str | int) -> str:
    """Return a Lua-ready 0xRRGGBB string."""
    return f"0x{parse_color(color):06X}"
