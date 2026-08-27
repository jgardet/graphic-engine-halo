"""Run generated Lua in the halo_emulator and capture the framebuffer."""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path
from typing import Any


def preview_lua(lua_code: str, sandbox_dir: str | None = None, timeout: float = 5.0) -> Any:
    """Run Lua code in the Halo emulator and return the PIL Image framebuffer."""
    from halo_emulator import HaloEmulator

    if sandbox_dir is None:
        sandbox_dir = tempfile.mkdtemp(prefix="halo_engine_")
    else:
        os.makedirs(sandbox_dir, exist_ok=True)

    main_lua = Path(sandbox_dir) / "main.lua"
    main_lua.write_text(lua_code, encoding="utf-8")

    lines: list[str] = []
    emu = HaloEmulator(sandbox_dir=sandbox_dir, print_handler=lines.append)
    emu.start("main.lua")
    emu.wait(timeout=timeout)
    img = emu.get_framebuffer()
    emu.stop()
    return img, lines


def preview_file(lua_path: str, out_path: str | None = None, timeout: float = 5.0) -> str:
    """Run a Lua file in the emulator and save the framebuffer PNG."""
    lua_code = Path(lua_path).read_text(encoding="utf-8")
    img, lines = preview_lua(lua_code, timeout=timeout)
    if img is None:
        raise RuntimeError("Emulator did not produce a framebuffer")
    if out_path is None:
        out_path = str(Path(lua_path).with_suffix(".png"))
    img.save(out_path)
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a Lua file in the Halo emulator")
    parser.add_argument("lua", help="Path to Lua file")
    parser.add_argument("--out", "-o", default=None, help="Output PNG path")
    parser.add_argument("--timeout", "-t", type=float, default=5.0, help="Emulator timeout")
    args = parser.parse_args()

    out = preview_file(args.lua, args.out, timeout=args.timeout)
    print(f"Framebuffer saved to {out}")


if __name__ == "__main__":
    main()
