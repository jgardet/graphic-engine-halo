"""Make the vendored `halo_emulator` package importable during tests."""

import sys
from pathlib import Path

VENDOR_PACKAGES = Path(__file__).parents[2] / "vendor" / "brilliant_sdk" / "python" / "packages"

if str(VENDOR_PACKAGES) not in sys.path:
    sys.path.insert(0, str(VENDOR_PACKAGES))
