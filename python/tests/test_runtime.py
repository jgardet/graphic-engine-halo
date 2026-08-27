"""Execute the binary HRP runtime against the official Halo emulator."""

from pathlib import Path
import shutil
import time

from halo_emulator import HaloEmulator
from halo_engine.hrp import HrpBuilder, HRP_CODE

PROJECT_LUA = Path(__file__).parents[2] / "lua" / "he_runtime.lua"


def _message(code: int, payload: bytes) -> bytes:
    return bytes((code, len(payload) >> 8, len(payload) & 0xFF)) + payload


def test_runtime_executes_hrp(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    lines = []
    emu = HaloEmulator(sandbox_dir=tmp_path, print_handler=lines.append)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        payload = HrpBuilder().clear("#000000").pixel(10, 12, "#00FF00").text(20, 20, "OK").end_frame().build()
        emu.inject_bluetooth_data(_message(HRP_CODE, payload))
        time.sleep(0.1)
        pixel = emu.get_framebuffer().getpixel((10, 12))
        assert pixel[1] > pixel[0]
        assert any("HRP ready" in line for line in lines)
    finally:
        emu.stop()
