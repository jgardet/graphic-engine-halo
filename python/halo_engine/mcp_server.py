"""MCP server for the Halo Graphic Engine.

Exposes agent tools to compile HSD scenes to Lua, preview them in the emulator,
and pack sprites.
"""

from __future__ import annotations

import base64
import json
import os
import tempfile
from pathlib import Path

from mcp.server.mcpserver import MCPServer

from .compiler import compile_scene
from .hrp_compiler import compile_scene_hrp
from .preview import preview_lua
from .sprite import pack_sprite, sprite_to_lua_args

ALLOWED_ASSET_ROOT = Path(__file__).resolve().parents[2]


def _validate_asset_source(src: str) -> None:
    """Reject MCP asset paths outside this checkout; data URIs are in-memory."""
    if src.startswith("data:"):
        return
    path = Path(src).resolve()
    try:
        path.relative_to(ALLOWED_ASSET_ROOT)
    except ValueError as exc:
        raise ValueError("asset paths must stay inside the Halo Engine checkout") from exc
    if not path.is_file():
        raise FileNotFoundError(f"asset source not found: {src}")


def _validate_scene_assets(value: object) -> None:
    if isinstance(value, dict):
        if value.get("type") == "sprite":
            src = value.get("src")
            if not isinstance(src, str):
                raise ValueError("sprite src must be a string")
            _validate_asset_source(src)
        for child in value.values():
            _validate_scene_assets(child)
    elif isinstance(value, list):
        for child in value:
            _validate_scene_assets(child)


server = MCPServer(
    name="halo-engine",
    title="Halo Graphic Engine",
    description="Compile and preview visuals for Brilliant Labs Halo smart glasses.",
    version="0.1.0",
)


@server.tool()
def compile_hsd(hsd_json: str) -> str:
    """Compile a Halo Scene Description (HSD) JSON string to Lua.

    Args:
        hsd_json: A JSON string containing the scene description.

    Returns:
        The Lua code that can be sent to a Halo device.
    """
    scene = json.loads(hsd_json)
    _validate_scene_assets(scene)
    return compile_scene(scene)


@server.tool()
def compile_hrp_hsd(hsd_json: str) -> str:
    """Compile HSD JSON to a base64-encoded hardware-valid HRP payload."""
    scene = json.loads(hsd_json)
    _validate_scene_assets(scene)
    payload = compile_scene_hrp(scene)
    return base64.b64encode(payload).decode("ascii")


@server.tool()
def preview_hsd(hsd_json: str) -> str:
    """Compile and render an HSD scene in the Halo emulator.

    Args:
        hsd_json: A JSON string containing the scene description.

    Returns:
        A base64-encoded PNG of the rendered framebuffer.
    """
    scene = json.loads(hsd_json)
    _validate_scene_assets(scene)
    lua = compile_scene(scene)
    img, _ = preview_lua(lua)
    if img is None:
        raise RuntimeError("Emulator produced no framebuffer")

    import io

    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


@server.tool()
def pack_sprite_tool(src: str, width: int | None = None, height: int | None = None, bpp: int = 4) -> str:
    """Pack an image into a Halo indexed sprite.

    Args:
        src: Image file path or base64 data URI.
        width: Optional target width.
        height: Optional target height.
        bpp: Bits per pixel (1, 2, or 4). Default 4 (16 colors).

    Returns:
        A JSON object with the packed sprite metadata and a base64 data URI.
    """
    _validate_asset_source(src)
    sprite = pack_sprite(src, width, height, bpp)
    packed = sprite.packed()
    return json.dumps(
        {
            "width": sprite.width,
            "height": sprite.height,
            "bpp": sprite.bpp,
            "num_colors": sprite.num_colors,
            "palette_data_b64": base64.b64encode(sprite.palette_data).decode("ascii"),
            "packed_b64": base64.b64encode(packed).decode("ascii"),
            "lua_args": sprite_to_lua_args(sprite),
        }
    )


@server.tool()
def list_examples() -> str:
    """Return a list of built-in example scene files."""
    repo_root = Path(__file__).resolve().parents[2]
    scene_dir = repo_root / "scenes"
    if not scene_dir.exists():
        return json.dumps([])
    files = [str(p.relative_to(repo_root)) for p in scene_dir.glob("*.json")]
    return json.dumps(files)


def main() -> None:
    server.run(transport="stdio")


if __name__ == "__main__":
    main()
