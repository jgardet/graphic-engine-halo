from halo_engine.diff import scene_changes, scene_hash


def test_scene_diff_uses_stable_ids():
    old = {"scene": {"children": [{"id": "price", "type": "text", "text": "1"}]}}
    new = {"scene": {"children": [{"id": "price", "type": "text", "text": "2"}, {"id": "delta", "type": "text", "text": "+1"}]}}
    changes = scene_changes(old, new)
    assert [(c.element_id, c.kind) for c in changes] == [("delta", "added"), ("price", "changed")]


def test_scene_hash_is_deterministic():
    a = {"b": 2, "a": 1}
    b = {"a": 1, "b": 2}
    assert scene_hash(a) == scene_hash(b)
