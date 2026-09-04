# Third-party notices

This repository is distributed under the MIT License. The following dependencies
are resolved by the Python and Kotlin builds and remain subject to their upstream
terms:

- `halo-emulator`
- Pillow
- NumPy
- lz4
- MCP Python SDK
- Kotlin/JVM and Kotlin test libraries
- AndroidX and Android BLE support artifacts used by the optional Android module

The Python dependency metadata is in `python/pyproject.toml`; Gradle dependency
metadata is in the module build files. The Dogica font data in
`kotlin/src/main/kotlin/halo/engine/display/HaloFonts.kt` is attributed there to
Roberto Mocci and the SIL Open Font License 1.1. The optional Brilliant SDK and
Halo firmware reference repositories are not committed, and no proprietary
firmware is included. The example Venus image is identified in `README.md` and is
not a device binary.

Before publishing a binary distribution, generate a notice bundle from the resolved
Python and Gradle environments and include any required upstream notices.
