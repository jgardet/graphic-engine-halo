"""HRP wire-format and hardware-limit tests."""

import pytest

from halo_engine.hrp import HrpBuilder, decode_frame
from halo_engine.hrp_compiler import compile_scene_hrp
from halo_engine.limits import HardwareLimitError, validate_lua_size


def test_hrp_round_trip():
    payload = HrpBuilder().clear("#000000").set_font(1, 16).text(10, 20, "Hi").end_frame().build()
    commands = decode_frame(payload)
    assert payload[:5] == b"HRP1\x00"
    assert [opcode for opcode, _ in commands] == [0x01, 0x08, 0x09, 0x0E]
    assert commands[2][1][-2:] == b"Hi"


def test_hrp_matches_big_endian_layout():
    payload = HrpBuilder().pixel(1, 2, "#123456").build()
    commands = decode_frame(payload)
    assert commands == [(0x03, b"\x00\x01\x00\x02\x12\x34\x56")]


def test_polygon_limit():
    with pytest.raises(ValueError, match="64"):
        HrpBuilder().polygon([(0, 0)] * 65, "#fff")


def test_lua_source_is_hardware_bounded():
    with pytest.raises(HardwareLimitError):
        validate_lua_size("x" * 4097)


def test_hrp_row_offsets_are_applied_once():
    scene = {"scene": {"children": [{"type": "row", "x": 10, "y": 20, "children": [{"type": "point", "x": 1, "y": 2}]}]}}
    commands = decode_frame(compile_scene_hrp(scene))
    assert (0x03, b"\x00\x0b\x00\x16\xff\xff\xff") in commands


def test_scene_compiles_to_binary_hrp():
    scene = {"scene": {"bg": "#000000", "children": [{"type": "rect", "x": 1, "y": 2, "w": 3, "h": 4, "filled": True}]}}
    payload = compile_scene_hrp(scene)
    assert decode_frame(payload)[-1][0] == 0x0E
