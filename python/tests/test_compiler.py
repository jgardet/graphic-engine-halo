"""Tests for the HSD compiler."""

import json
from pathlib import Path

import pytest
from halo_engine.compiler import compile_scene
from halo_engine.preview import preview_lua

SCENE = {
    "version": "1.0",
    "device": "halo",
    "mode": "repl",
    "scene": {
        "width": 256,
        "height": 256,
        "bg": "#000000",
        "children": [
            {"type": "circle", "cx": 128, "cy": 128, "r": 125, "color": "#0050A0", "filled": False},
            {"type": "text", "x": 78, "y": 90, "text": "5:30", "font": 1, "size": 32, "color": "#FFFFFF"},
        ],
    },
}


def test_compile_repl_contains_expected_calls():
    lua = compile_scene(SCENE)
    assert "frame.display.clear(0x000000)" in lua
    assert "frame.display.power_save(false)" in lua
    assert "frame.display.circle(129,129,125,0x0050A0,false)" in lua
    assert "frame.display.set_font(1,32,1)" in lua
    assert "frame.display.text('5:30',79,91,0xFFFFFF)" in lua
    assert "print('ok')" in lua


def test_row_offsets_are_applied_once():
    scene = {
        "scene": {
            "children": [
                {"type": "row", "x": 10, "y": 20, "children": [{"type": "point", "x": 1, "y": 2, "color": "#FFFFFF"}]}
            ]
        }
    }
    assert "frame.display.set_pixel(12,23,0xFFFFFF)" in compile_scene(scene)


def test_compile_preview_renders():
    lua = compile_scene(SCENE)
    img, lines = preview_lua(lua)
    assert img is not None
    assert any("ok" in line for line in lines)


def test_running_hud_scene_renders():
    repo = Path(__file__).resolve().parents[2]
    with open(repo / "scenes" / "running_hud.json", "r", encoding="utf-8") as f:
        scene = json.load(f)
    lua = compile_scene(scene)
    img, _ = preview_lua(lua)
    assert img is not None
