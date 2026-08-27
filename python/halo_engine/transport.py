"""Transport adapters using the official Brilliant SDK message framing."""

from __future__ import annotations

from typing import Any

from .hrp import HRP_CODE
from .limits import STOCK_HALO, HaloLimits, validate_hrp_size


def frame_message(code: int, payload: bytes) -> bytes:
    """Build the first official data-message packet for tests/adapters.

    The SDK adds the BLE data marker separately. This helper only represents
    the message framing consumed by ``data.lua``.
    """
    if not 0 <= code <= 255:
        raise ValueError("message code must be 0..255")
    if len(payload) > 65535:
        raise ValueError("message payload exceeds uint16 limit")
    return bytes((code, len(payload) >> 8, len(payload) & 0xFF)) + payload


class BrilliantSdkTransport:
    """Thin adapter around ``brilliant_msg.BrilliantMsg``.

    The official SDK owns BLE MTU negotiation, message chunking, and receiver-paced
    ACK handling. This class deliberately does not duplicate that wire protocol.
    """

    def __init__(self, frame: Any | None = None, limits: HaloLimits = STOCK_HALO):
        if frame is None:
            from brilliant_msg import BrilliantMsg

            frame = BrilliantMsg()
        self.frame = frame
        self.limits = limits

    async def connect(self, name: str | None = None) -> None:
        await self.frame.connect(name=name) if name is not None else await self.frame.connect()

    async def disconnect(self) -> None:
        await self.frame.disconnect()

    async def upload_lua_app(self, local_filename: str, frame_filename: str, libs: list[str]) -> None:
        await self.frame.upload_stdlua_libs(lib_names=libs)
        await self.frame.upload_frame_app(local_filename=local_filename, frame_filename=frame_filename)

    async def start_app(self, app_name: str) -> None:
        self.frame.attach_print_response_handler()
        await self.frame.start_frame_app(frame_app_name=app_name, await_print=True)

    async def send_hrp(self, payload: bytes, code: int = HRP_CODE) -> None:
        validate_hrp_size(len(payload), self.limits)
        await self.frame.send_message(code, payload)

    async def stop_app(self) -> None:
        await self.frame.stop_frame_app()

    @property
    def negotiated_data_payload(self) -> int:
        return int(self.frame.max_data_payload())
