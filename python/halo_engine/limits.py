"""Conservative hardware capability profiles and size validation."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class HaloLimits:
    display_width: int = 256
    display_height: int = 256
    max_message_bytes: int = 65535
    max_lua_source_bytes: int = 4096
    max_hrp_message_bytes: int = 32768
    max_asset_bytes: int = 24576
    max_retained_asset_bytes: int = 49152
    max_peak_working_set_bytes: int = 65536
    max_polygon_points: int = 64


STOCK_HALO = HaloLimits()


class HardwareLimitError(ValueError):
    """Raised when a generated artifact exceeds the selected hardware profile."""


def validate_lua_size(lua: str, limits: HaloLimits = STOCK_HALO) -> None:
    size = len(lua.encode("utf-8"))
    if size > limits.max_lua_source_bytes:
        raise HardwareLimitError(
            f"Lua source is {size} bytes; stock profile allows {limits.max_lua_source_bytes}. "
            "Use binary HRP/data-channel mode instead of inline Lua."
        )


def validate_message_size(size: int, limits: HaloLimits = STOCK_HALO) -> None:
    if size > limits.max_message_bytes:
        raise HardwareLimitError(f"Message is {size} bytes; protocol maximum is {limits.max_message_bytes}.")


def validate_hrp_size(size: int, limits: HaloLimits = STOCK_HALO) -> None:
    validate_message_size(size, limits)
    if size > limits.max_hrp_message_bytes:
        raise HardwareLimitError(f"HRP message is {size} bytes; profile allows {limits.max_hrp_message_bytes}.")


def validate_asset_size(size: int, limits: HaloLimits = STOCK_HALO) -> None:
    validate_message_size(size, limits)
    if size > limits.max_asset_bytes:
        raise HardwareLimitError(f"Asset is {size} bytes; profile allows {limits.max_asset_bytes}.")
