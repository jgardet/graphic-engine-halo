"""Run every scene through the hardware-bounded HRP runtime emulator."""
from __future__ import annotations

import json
import shutil
import time
from pathlib import Path
import os

from halo_emulator import HaloEmulator
from halo_engine.hrp_compiler import compile_scene_hrp

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tmp" / "halo-example-check"
os.chdir(ROOT)

for scene_file in sorted((ROOT / "scenes").glob("*.json")):
    sandbox = OUT / f"{scene_file.stem}_emu"
    sandbox.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "lua" / "he_runtime.lua", sandbox / "main.lua")
    lines: list[str] = []
    emulator = HaloEmulator(sandbox_dir=sandbox, print_handler=lines.append)
    emulator.start("main.lua")
    try:
        time.sleep(0.05)
        payload = compile_scene_hrp(json.loads(scene_file.read_text(encoding="utf-8")))
        packet = bytes((0x60, len(payload) >> 8, len(payload) & 0xFF)) + payload
        emulator.inject_bluetooth_data(packet)
        time.sleep(0.1)
        errors = [line for line in lines if "error" in line.lower() or "truncated" in line.lower()]
        status = "EMU_PASS" if not errors else "EMU_FAIL"
        print(status, scene_file.stem, len(payload), errors)
    finally:
        emulator.stop()
