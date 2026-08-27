"""Halo Graphic Engine — Python reference implementation."""

from .compiler import compile_scene
from .hrp_compiler import compile_scene_hrp
from .sprite import pack_sprite
from .diff import scene_changes, scene_hash
from .atlas import SpriteAtlas

__all__ = ["compile_scene", "compile_scene_hrp", "pack_sprite", "SpriteAtlas", "scene_changes", "scene_hash"]
