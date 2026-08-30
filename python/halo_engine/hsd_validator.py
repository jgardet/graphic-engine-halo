"""Validate Halo Scene Description documents against hardware limits."""

from __future__ import annotations

from typing import Any

from .colors import parse_color
from .limits import STOCK_HALO, HaloLimits


class HsdValidator:
    """Validate a Python dict representation of an HSD document."""

    def __init__(
        self,
        limits: HaloLimits = STOCK_HALO,
        max_elements: int = 256,
        max_depth: int = 16,
        max_text_bytes: int = 2_048,
    ):
        self.limits = limits
        self.max_elements = max_elements
        self.max_depth = max_depth
        self.max_text_bytes = max_text_bytes

    def validate(self, document: dict[str, Any]) -> None:
        if not isinstance(document, dict):
            raise ValueError("HSD root must be an object")

        version = document.get("version", "1.0")
        if version != "1.0":
            raise ValueError(f"Unsupported HSD version: {version}")

        device = document.get("device", "halo")
        if device != "halo":
            raise ValueError(f"Unsupported HSD device: {device}")

        mode = document.get("mode", "repl")
        if mode not in ("repl", "runtime"):
            raise ValueError(f"Unsupported HSD mode: {mode}")

        scene = document.get("scene")
        if not isinstance(scene, dict):
            raise ValueError("HSD scene must be an object")

        width = scene.get("width", self.limits.display_width)
        height = scene.get("height", self.limits.display_height)
        if not (
            isinstance(width, int)
            and isinstance(height, int)
            and 1 <= width <= self.limits.display_width
            and 1 <= height <= self.limits.display_height
        ):
            raise ValueError(
                f"Scene dimensions {width}x{height} exceed "
                f"{self.limits.display_width}x{self.limits.display_height}"
            )

        if "bg" in scene:
            parse_color(scene["bg"])

        if "brightness" in scene:
            b = scene["brightness"]
            if not isinstance(b, int) or not (0 <= b <= 100):
                raise ValueError("Brightness must be 0..100")

        if "power_save" in scene:
            if not isinstance(scene["power_save"], bool):
                raise ValueError("power_save must be boolean")

        children = scene.get("children", [])
        if not isinstance(children, list):
            raise ValueError("scene.children must be an array")

        self._count = 0
        for child in children:
            self._visit(child, 1, width, height)

    def _visit(self, element: Any, depth: int, width: int, height: int) -> None:
        if depth > self.max_depth:
            raise ValueError(f"HSD nesting exceeds {self.max_depth}")

        self._count += 1
        if self._count > self.max_elements:
            raise ValueError(f"HSD contains more than {self.max_elements} elements")

        if not isinstance(element, dict):
            raise ValueError("HSD element must be an object")

        if "visible" in element and not isinstance(element["visible"], bool):
            raise ValueError("visible must be boolean")

        typ = element.get("type", "").lower()

        if typ in ("group", "row", "column"):
            self._nonnegative_int(element, "x", optional=True)
            self._nonnegative_int(element, "y", optional=True)
            self._nonnegative_int(element, "spacing", optional=True)
            nested = element.get("children", [])
            if not isinstance(nested, list):
                raise ValueError(f"{typ}.children must be an array")
            for child in nested:
                self._visit(child, depth + 1, width, height)
            return

        if typ == "text":
            self._coordinate(element, "x", width)
            self._coordinate(element, "y", height)
            font = element.get("font", 0)
            size = element.get("size", 8)
            scale = element.get("scale", 1)
            if font not in (0, 1):
                raise ValueError("Text font must be 0 or 1")
            if not (isinstance(size, int) and 8 <= size <= 255 and size % 8 == 0):
                raise ValueError("Text size must be a multiple of 8 between 8 and 255")
            if not (isinstance(scale, int) and 1 <= scale <= 255):
                raise ValueError("Text scale must be 1..255")
            text = str(element.get("text", ""))
            if len(text.encode("utf-8")) > self.max_text_bytes:
                raise ValueError(f"Text exceeds {self.max_text_bytes} bytes")
            self._color(element)
            return

        if typ == "rect":
            x = self._coordinate(element, "x", width)
            y = self._coordinate(element, "y", height)
            w = self._positive_int(element, "w")
            h = self._positive_int(element, "h")
            if x + w > width or y + h > height:
                raise ValueError("Rectangle exceeds scene bounds")
            self._optional_bool(element, "filled")
            self._color(element)
            return

        if typ == "circle":
            self._coordinate(element, "cx", width)
            self._coordinate(element, "cy", height)
            self._positive_int(element, "r")
            self._optional_bool(element, "filled")
            self._color(element)
            return

        if typ == "line":
            self._coordinate(element, "x0", width)
            self._coordinate(element, "y0", height)
            self._coordinate(element, "x1", width)
            self._coordinate(element, "y1", height)
            self._color(element)
            return

        if typ == "polygon":
            points = element.get("points")
            if not isinstance(points, list):
                raise ValueError("polygon.points must be an array")
            if not (3 <= len(points) <= self.limits.max_polygon_points):
                raise ValueError(
                    f"Polygon must contain 3..{self.limits.max_polygon_points} points"
                )
            for point in points:
                if not isinstance(point, (list, tuple)) or len(point) != 2:
                    raise ValueError("Polygon point must contain x and y")
                px, py = point
                if not (isinstance(px, int) and 0 <= px < width):
                    raise ValueError("Polygon x is outside scene")
                if not (isinstance(py, int) and 0 <= py < height):
                    raise ValueError("Polygon y is outside scene")
            self._color(element)
            return

        if typ in ("point", "pixel"):
            self._coordinate(element, "x", width)
            self._coordinate(element, "y", height)
            self._color(element)
            return

        if typ == "sprite":
            src = element.get("src")
            if not isinstance(src, str) or not src.strip():
                raise ValueError("Sprite src must not be blank")
            self._coordinate(element, "x", width)
            self._coordinate(element, "y", height)
            self._optional_positive_int(element, "w", width)
            self._optional_positive_int(element, "h", height)
            bpp = element.get("bpp", 4)
            if bpp not in (1, 2, 4):
                raise ValueError("Sprite bpp must be 1, 2, or 4")
            palette_offset = element.get("palette_offset", 0)
            if not (isinstance(palette_offset, int) and 0 <= palette_offset <= 15):
                raise ValueError("Sprite palette_offset must be 0..15")
            scale_x = element.get("scale_x", 1)
            scale_y = element.get("scale_y", 1)
            if scale_x != 1 or scale_y != 1:
                raise ValueError("runtime/HRP sprites do not support scaling (use 1 or switch to repl mode)")
            resource_id = element.get("resource_id", 1)
            if not (isinstance(resource_id, int) and 1 <= resource_id <= 0xFFFF):
                raise ValueError("Sprite resource_id must be 1..65535")
            return

        raise ValueError(f"Unknown HSD element type: {typ}")

    def _int(self, element: dict[str, Any], key: str, default: int | None = None) -> int:
        value = element.get(key, default)
        if value is None:
            raise ValueError(f"Missing {key}")
        if not isinstance(value, int):
            raise ValueError(f"{key} must be an integer")
        return value

    def _nonnegative_int(self, element: dict[str, Any], key: str, optional: bool = False) -> int | None:
        if key not in element:
            if optional:
                return None
            raise ValueError(f"Missing {key}")
        value = element[key]
        if not isinstance(value, int) or value < 0:
            raise ValueError(f"{key} must be a non-negative integer")
        return value

    def _positive_int(self, element: dict[str, Any], key: str) -> int:
        value = self._int(element, key)
        if value <= 0:
            raise ValueError(f"{key} must be positive")
        return value

    def _optional_positive_int(self, element: dict[str, Any], key: str, upper: int) -> None:
        if key not in element:
            return
        value = element[key]
        if not isinstance(value, int) or not (0 < value <= upper):
            raise ValueError(f"Invalid {key}")

    def _coordinate(self, element: dict[str, Any], key: str, upper: int) -> int:
        value = self._int(element, key)
        if not (0 <= value < upper):
            raise ValueError(f"{key} is outside scene bounds")
        return value

    def _optional_bool(self, element: dict[str, Any], key: str) -> None:
        if key in element and not isinstance(element[key], bool):
            raise ValueError(f"{key} must be boolean")

    def _color(self, element: dict[str, Any]) -> None:
        if "color" in element:
            parse_color(element["color"])
