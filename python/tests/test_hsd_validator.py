"""Tests for the HSD validator."""

import pytest

from halo_engine.hsd_validator import HsdValidator


def test_minimal_scene_is_valid():
    scene = {"scene": {"children": []}}
    HsdValidator().validate(scene)  # should not raise


def test_rejects_unknown_version():
    with pytest.raises(ValueError, match="version"):
        HsdValidator().validate({"version": "2.0", "scene": {"children": []}})


def test_rejects_unknown_device():
    with pytest.raises(ValueError, match="device"):
        HsdValidator().validate({"device": "frame", "scene": {"children": []}})


def test_rejects_unknown_mode():
    with pytest.raises(ValueError, match="mode"):
        HsdValidator().validate({"mode": "live", "scene": {"children": []}})


def test_rejects_missing_scene():
    with pytest.raises(ValueError, match="scene"):
        HsdValidator().validate({})


def test_rejects_oversized_scene():
    with pytest.raises(ValueError, match="dimensions"):
        HsdValidator().validate({"scene": {"width": 512, "height": 256, "children": []}})


def test_rejects_out_of_bounds_point():
    with pytest.raises(ValueError, match="bounds"):
        HsdValidator().validate({"scene": {"children": [{"type": "point", "x": 256, "y": 0}]}})


def test_rejects_unknown_element_type():
    with pytest.raises(ValueError, match="Unknown"):
        HsdValidator().validate({"scene": {"children": [{"type": "video"}]}})


def test_rejects_invalid_text_size():
    with pytest.raises(ValueError, match="Text size"):
        HsdValidator().validate({"scene": {"children": [{"type": "text", "x": 0, "y": 0, "text": "hi", "size": 12}]}})


def test_rejects_too_many_points():
    points = [[i, i] for i in range(65)]
    with pytest.raises(ValueError, match="64"):
        HsdValidator().validate({"scene": {"children": [{"type": "polygon", "points": points}]}})


def test_rejects_excessive_nesting():
    scene = {"scene": {"children": []}}
    child = scene["scene"]["children"]
    for _ in range(17):
        child.append({"type": "group", "children": []})
        child = child[0]["children"]
    with pytest.raises(ValueError, match="nesting"):
        HsdValidator().validate(scene)


def test_rejects_too_many_elements():
    children = [{"type": "point", "x": 0, "y": 0} for _ in range(257)]
    with pytest.raises(ValueError, match="256"):
        HsdValidator().validate({"scene": {"children": children}})


def test_sprite_must_be_1_2_or_4_bpp():
    with pytest.raises(ValueError, match="bpp"):
        HsdValidator().validate({"scene": {"children": [{"type": "sprite", "x": 0, "y": 0, "src": "x", "bpp": 8}]}})


def test_sprite_scale_must_be_one():
    with pytest.raises(ValueError, match="scaling"):
        HsdValidator().validate({"scene": {"children": [{"type": "sprite", "x": 0, "y": 0, "src": "x", "scale_x": 2}]}})
