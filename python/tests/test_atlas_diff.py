import pytest

from halo_engine.atlas import SpriteAtlas
from halo_engine.diff import scene_changes, scene_hash
from halo_engine.hrp import HrpBuilder, decode_frame
from halo_engine.sprite import SpriteAsset


def _sprite(w=2, h=2, bpp=4) -> SpriteAsset:
    return SpriteAsset(
        width=w,
        height=h,
        bpp=bpp,
        num_colors=2**bpp,
        palette_data=bytes(3 * 2**bpp),
        pixel_data=bytes(w * h),
    )


def test_sprite_atlas_assigns_stable_resource_ids():
    atlas = SpriteAtlas()
    first = atlas.add("icon", _sprite())
    second = atlas.add("logo", _sprite())
    assert (first.resource_id, second.resource_id) == (1, 2)
    assert atlas.add("icon", _sprite()) is first
    assert atlas.total_bytes == sum(e.packed_size for e in atlas.entries)


def test_sprite_atlas_budget_and_release():
    with pytest.raises(ValueError, match="budget"):
        SpriteAtlas(max_total_bytes=1).add("icon", _sprite())
    atlas = SpriteAtlas()
    entry = atlas.add("icon", _sprite())
    assert atlas.release("icon") is entry
    with pytest.raises(KeyError):
        atlas.get("icon")


def test_sprite_atlas_emit_definitions():
    atlas = SpriteAtlas()
    entry = atlas.add("icon", _sprite())
    builder = HrpBuilder()
    atlas.emit_definitions(builder)
    commands = decode_frame(builder.build())
    assert commands[0][0] == 0x0A
    assert commands[0][1][:2] == entry.resource_id.to_bytes(2, "big")
    assert commands[0][1][2:] == entry.sprite.packed()


def test_scene_diff_uses_stable_ids():
    old = {"scene": {"children": [{"id": "price", "type": "text", "text": "1"}]}}
    new = {"scene": {"children": [{"id": "price", "type": "text", "text": "2"}, {"id": "delta", "type": "text", "text": "+1"}]}}
    changes = scene_changes(old, new)
    assert [(c.element_id, c.kind) for c in changes] == [("delta", "added"), ("price", "changed")]


def test_scene_hash_is_deterministic():
    a = {"b": 2, "a": 1}
    b = {"a": 1, "b": 2}
    assert scene_hash(a) == scene_hash(b)
