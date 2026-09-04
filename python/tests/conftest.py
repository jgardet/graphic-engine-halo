"""Allow an optional local Brilliant SDK checkout during tests."""

import sys
from pathlib import Path

VENDOR_PACKAGES = Path(__file__).parents[2] / "vendor" / "brilliant_sdk" / "python" / "packages"

if VENDOR_PACKAGES.is_dir() and str(VENDOR_PACKAGES) not in sys.path:
    sys.path.insert(0, str(VENDOR_PACKAGES))
