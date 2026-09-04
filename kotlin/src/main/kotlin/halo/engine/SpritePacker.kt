package halo.engine

import java.io.File

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
    private val allowedSourceRoot: File = File(System.getProperty("user.dir")).canonicalFile,
) : SpritePacker {
    override fun pack(src: String, width: Int?, height: Int?, bpp: Int): SpritePacker.Sprite {
        if (!src.startsWith("data:")) {
            val source = File(src).canonicalFile
            require(source.toPath().startsWith(allowedSourceRoot.toPath())) {
                "Sprite sources must stay inside $allowedSourceRoot"
            }
        }
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

/** Pack indexed pixels into tightly packed bytes (MSB-first) for 1/2/4 bpp. */
fun packIndexedPixels(indices: ByteArray, bpp: Int): ByteArray {
    require(bpp == 1 || bpp == 2 || bpp == 4) { "bpp must be 1, 2, or 4" }
    val out = java.io.ByteArrayOutputStream()
    val perByte = 8 / bpp
    for (start in indices.indices step perByte) {
        var value = 0
        for (j in 0 until perByte) {
            if (start + j < indices.size) {
                value = value or ((indices[start + j].toInt() and ((1 shl bpp) - 1)) shl ((perByte - j - 1) * bpp))
            }
        }
        out.write(value)
    }
    return out.toByteArray()
}

/**
 * Quantize ARGB pixels into an indexed-color sprite using k-means clustering.
 *
 * Index 0 is reserved for transparent pixels (alpha < 128). Opaque pixels are
 * mapped to palette entries 1..maxColors-1. This is platform-neutral — both
 * [JvmSpritePacker] and [AndroidSpritePacker] call this after decoding the
 * source image into an ARGB [IntArray].
 */
fun quantizeSprite(pixels: IntArray, width: Int, height: Int, bpp: Int): SpritePacker.Sprite {
    require(bpp == 1 || bpp == 2 || bpp == 4) { "bpp must be 1, 2, or 4" }
    val maxColors = 1 shl bpp

    val colorCounts = pixels.asSequence()
        .filter { (it ushr 24) >= 128 }
        .groupBy { it and 0x00FFFFFF }
        .map { (color, occurrences) -> color to occurrences.size }
        .sortedByDescending { it.second }

    val palette = ByteArray(maxColors * 3)
    val indices = ByteArray(pixels.size)

    if (colorCounts.isEmpty()) {
        return SpritePacker.Sprite(width, height, bpp, maxColors, palette, indices)
    }

    val k = minOf(maxColors - 1, colorCounts.size)
    val paletteColors = kMeansColors(colorCounts, k)

    paletteColors.forEachIndexed { index, color ->
        val offset = (index + 1) * 3
        palette[offset] = (color ushr 16).toByte()
        palette[offset + 1] = (color ushr 8).toByte()
        palette[offset + 2] = color.toByte()
    }

    pixels.forEachIndexed { index, color ->
        indices[index] = if ((color ushr 24) < 128) 0 else {
            (nearestColor(color, paletteColors) + 1).toByte()
        }
    }

    return SpritePacker.Sprite(width, height, bpp, maxColors, palette, indices)
}

/** Simple k-means on 24-bit RGB colors, weighted by pixel count. */
private fun kMeansColors(colorCounts: List<Pair<Int, Int>>, k: Int): List<Int> {
    if (colorCounts.size <= k) return colorCounts.map { it.first }

    var centers = colorCounts.take(k).map { it.first }.toMutableList()
    val clusters = Array<MutableList<Pair<Int, Int>>>(k) { mutableListOf() }

    repeat(K_MEANS_ITERATIONS) {
        clusters.forEach { it.clear() }
        colorCounts.forEach { entry ->
            val nearest = centers.indices.minBy { index -> colorDistance(centers[index], entry.first) }
            clusters[nearest] += entry
        }

        val next = clusters.map { cluster ->
            if (cluster.isEmpty()) 0
            else {
                val total = cluster.sumOf { it.second }
                val r = cluster.sumOf { (it.first ushr 16 and 0xFF) * it.second } / total
                val g = cluster.sumOf { (it.first ushr 8 and 0xFF) * it.second } / total
                val b = cluster.sumOf { (it.first and 0xFF) * it.second } / total
                (r shl 16) or (g shl 8) or b
            }
        }

        if (next == centers) return centers
        centers = next.toMutableList()
    }

    return centers
}

private fun colorDistance(a: Int, b: Int): Int {
    val dr = (a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)
    val dg = (a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return dr * dr + dg * dg + db * db
}

private fun nearestColor(color: Int, palette: List<Int>): Int =
    palette.indices.minBy { index -> colorDistance(palette[index], color) }

private const val K_MEANS_ITERATIONS = 16
