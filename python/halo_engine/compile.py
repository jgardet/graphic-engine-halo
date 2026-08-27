"""CLI to compile an HSD scene to Lua."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .compiler import compile_scene


def main() -> None:
    parser = argparse.ArgumentParser(description="Compile HSD scene to Halo Lua")
    parser.add_argument("scene", help="Path to HSD JSON file")
    parser.add_argument("--out", "-o", required=True, help="Output Lua file path")
    args = parser.parse_args()

    scene_path = Path(args.scene)
    with open(scene_path, "r", encoding="utf-8") as f:
        scene = json.load(f)

    lua = compile_scene(scene)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(lua, encoding="utf-8")
    print(f"Compiled {scene_path} -> {out_path} ({len(lua)} chars)")


if __name__ == "__main__":
    main()
