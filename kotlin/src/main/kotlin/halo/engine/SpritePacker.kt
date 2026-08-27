package halo.engine

/**
 * Sprite packing interface. The default JVM implementation delegates to the
 * Python companion because robust color quantization is much easier in Python
 * (Pillow/numpy). Android projects can inject their own implementation.
 */
interface SpritePacker {
    data class Sprite(
        val width: Int,
        val height: Int,
        val bpp: Int,
        val numColors: Int,
        val paletteData: ByteArray,
        val pixelData: ByteArray,
    ) {
        val colorFormat: Int
            get() = when (bpp) {
                1 -> 2
                2 -> 4
                else -> 16
            }
    }

    fun pack(src: String, width: Int?, height: Int?, bpp: Int = 4): Sprite
}

class PythonSpritePacker(
    private val pythonExe: String = "python",
    private val pythonPath: String? = null,
) : SpritePacker {
    override fun pack(src: String, width: Int?, height: Int?, bpp: Int): SpritePacker.Sprite {
        val args = mutableListOf(
            "-m", "halo_engine.sprite",
            "--src", src,
            "--bpp", bpp.toString(),
        )
        width?.let { args += listOf("--width", it.toString()) }
        height?.let { args += listOf("--height", it.toString()) }

        val pb = ProcessBuilder(pythonExe, *args.toTypedArray())
            .redirectErrorStream(true)
        pythonPath?.let { pb.environment()["PYTHONPATH"] = it }

        val process = pb.start()

        var output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw RuntimeException("Sprite packing failed: $output")
        }

        // Filter out any Python/Runpy warnings that may appear on stdout
        val lines = output.lines().filter { it.isNotBlank() && !it.startsWith("<") }
        if (lines.isEmpty()) {
            throw RuntimeException("Sprite packer produced no output")
        }

        // Expected output from the CLI is:
        // width height bpp numColors palette_b64 pixel_b64
        val parts = lines.first().trim().split(" ")
        if (parts.size != 6) {
            throw RuntimeException("Unexpected sprite packer output: ${lines.first()}")
        }

        return SpritePacker.Sprite(
            width = parts[0].toInt(),
            height = parts[1].toInt(),
            bpp = parts[2].toInt(),
            numColors = parts[3].toInt(),
            paletteData = java.util.Base64.getDecoder().decode(parts[4]),
            pixelData = java.util.Base64.getDecoder().decode(parts[5]),
        )
    }
}

class StubSpritePacker : SpritePacker {
    override fun pack(src: String, width: Int?, height: Int?, bpp: Int): SpritePacker.Sprite {
        throw NotImplementedError("Sprite packing on this platform requires a concrete SpritePacker implementation or pre-packed data.")
    }
}
