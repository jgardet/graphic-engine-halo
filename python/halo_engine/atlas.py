"""Retained indexed-sprite atlas/resource bookkeeping."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from .limits import STOCK_HALO, validate_asset_size
from .sprite import SpriteAsset, pack_sprite
from .hrp import HrpBuilder


@dataclass(frozen=True)
class AtlasEntry:
    resource_id: int
    name: str
    sprite: SpriteAsset
    packed_size: int


class SpriteAtlas:
    """Deterministic resource table; sprites stay individually addressable."""

    def __init__(self, max_total_bytes: int = STOCK_HALO.max_retained_asset_bytes):
        self.max_total_bytes = max_total_bytes
        self._entries: dict[str, AtlasEntry] = {}

    def add_file(self, name: str, src: str, width: int | None = None, height: int | None = None, bpp: int = 4) -> AtlasEntry:
        sprite = pack_sprite(src, width, height, bpp)
        validate_asset_size(len(sprite.packed()))
        return self.add(name, sprite)

    def add(self, name: str, sprite: SpriteAsset) -> AtlasEntry:
        if not name:
            raise ValueError("atlas resource name cannot be empty")
        if name in self._entries:
            return self._entries[name]
        resource_id = len(self._entries) + 1
        entry = AtlasEntry(resource_id, name, sprite, len(sprite.packed()))
        if self.total_bytes + entry.packed_size > self.max_total_bytes:
            raise ValueError("sprite atlas exceeds retained asset budget")
        self._entries[name] = entry
        return entry

    def get(self, name: str) -> AtlasEntry:
        return self._entries[name]

    @property
    def entries(self) -> tuple[AtlasEntry, ...]:
        return tuple(self._entries.values())

    @property
    def total_bytes(self) -> int:
        return sum(entry.packed_size for entry in self._entries.values())

    def release(self, name: str) -> AtlasEntry:
        return self._entries.pop(name)

    def emit_definitions(self, builder: HrpBuilder) -> None:
        """Emit each retained resource definition into an HRP builder."""
        for entry in self.entries:
            builder.sprite_define(entry.resource_id, entry.sprite.packed())
