"""Stable-ID scene diffing for immediate-mode Halo rendering."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class SceneChange:
    element_id: str
    kind: str  # added, changed, removed
    element: dict[str, Any] | None


def _flatten(elements: list[dict[str, Any]], parent: str = "") -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for index, element in enumerate(elements):
        element_id = str(element.get("id", f"{parent}/{index}"))
        result[element_id] = element
        children = element.get("children", [])
        if children:
            result.update(_flatten(children, element_id))
    return result


def scene_changes(previous: dict[str, Any] | None, current: dict[str, Any]) -> list[SceneChange]:
    old = _flatten((previous or {}).get("scene", {}).get("children", []))
    new = _flatten(current.get("scene", {}).get("children", []))
    changes: list[SceneChange] = []
    for element_id in sorted(old.keys() - new.keys()):
        changes.append(SceneChange(element_id, "removed", None))
    for element_id in sorted(new.keys() - old.keys()):
        changes.append(SceneChange(element_id, "added", new[element_id]))
    for element_id in sorted(old.keys() & new.keys()):
        if _canonical(old[element_id]) != _canonical(new[element_id]):
            changes.append(SceneChange(element_id, "changed", new[element_id]))
    return changes


def scene_hash(scene: dict[str, Any]) -> str:
    encoded = json.dumps(scene, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def _canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))
