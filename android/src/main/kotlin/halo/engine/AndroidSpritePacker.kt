package halo.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

class AndroidSpritePacker(
    private val resolveAsset: (String) -> ByteArray? = { null },
    private val maxSourceBytes: Int = 1_048_576,
    private val maxDimension: Int = 256,
) : SpritePacker {
    override fun pack(src: String, width: Int?, height: Int?, bpp: Int): SpritePacker.Sprite {
        require(bpp == 1 || bpp == 2 || bpp == 4) { "bpp must be 1, 2, or 4" }
        width?.let { require(it in 1..maxDimension) { "Sprite width exceeds $maxDimension" } }
        height?.let { require(it in 1..maxDimension) { "Sprite height exceeds $maxDimension" } }
        val bytes = sourceBytes(src)
        require(bytes.size <= maxSourceBytes) { "Sprite source exceeds $maxSourceBytes bytes" }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Unable to decode sprite source" }
        val targetWidth = width ?: decoded.width
        val targetHeight = height ?: decoded.height
        require(targetWidth in 1..maxDimension && targetHeight in 1..maxDimension) { "Sprite dimensions exceed ${maxDimension}x$maxDimension" }
        val bitmap = if (decoded.width == targetWidth && decoded.height == targetHeight) decoded else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also { decoded.recycle() }
        }
        return try {
            quantize(bitmap, bpp)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sourceBytes(src: String): ByteArray {
        if (src.startsWith("data:image/") && src.contains(";base64,")) {
            return Base64.decode(src.substringAfter(";base64,"), Base64.DEFAULT)
        }
        return requireNotNull(resolveAsset(src)) { "Unsupported sprite source: $src" }
    }

    private fun quantize(bitmap: Bitmap, bpp: Int): SpritePacker.Sprite {
        val maxColors = 1 shl bpp
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Opaque pixels only; index 0 is reserved for transparent pixels.
        val colorCounts = pixels.asSequence()
            .filter { (it ushr 24) >= 128 }
            .groupBy { it and 0x00ffffff }
            .map { (color, occurrences) -> color to occurrences.size }
            .sortedByDescending { it.second }

        val palette = ByteArray(maxColors * 3)
        val indices = ByteArray(pixels.size)

        if (colorCounts.isEmpty()) {
            // Fully transparent sprite.
            return SpritePacker.Sprite(bitmap.width, bitmap.height, bpp, maxColors, palette, indices)
        }

        val k = minOf(maxColors - 1, colorCounts.size)
        val paletteColors = kMeans(colorCounts, k)

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

        return SpritePacker.Sprite(bitmap.width, bitmap.height, bpp, maxColors, palette, indices)
    }

    /** Simple k-means on 24-bit RGB colors, weighted by pixel count. */
    private fun kMeans(colorCounts: List<Pair<Int, Int>>, k: Int): List<Int> {
        if (colorCounts.size <= k) return colorCounts.map { it.first }

        var centers = colorCounts.take(k).map { it.first }.toMutableList()
        val clusters = Array<MutableList<Pair<Int, Int>>>(k) { mutableListOf() }

        repeat(MAX_K_MEANS_ITERATIONS) {
            clusters.forEach { it.clear() }
            colorCounts.forEach { entry ->
                val nearest = centers.indices.minBy { index -> colorDistance(centers[index], entry.first) }
                clusters[nearest] += entry
            }

            val next = clusters.map { cluster ->
                if (cluster.isEmpty()) 0
                else {
                    val total = cluster.sumOf { it.second }
                    val r = cluster.sumOf { (it.first ushr 16 and 0xff) * it.second } / total
                    val g = cluster.sumOf { (it.first ushr 8 and 0xff) * it.second } / total
                    val b = cluster.sumOf { (it.first and 0xff) * it.second } / total
                    (r shl 16) or (g shl 8) or b
                }
            }

            if (next == centers) return centers
            centers = next.toMutableList()
        }

        return centers
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val redDelta = (a ushr 16 and 0xff) - (b ushr 16 and 0xff)
        val greenDelta = (a ushr 8 and 0xff) - (b ushr 8 and 0xff)
        val blueDelta = (a and 0xff) - (b and 0xff)
        return redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta
    }

    private fun nearestColor(color: Int, palette: List<Int>): Int {
        return palette.indices.minBy { index -> colorDistance(palette[index], color) }
    }

    companion object {
        private const val MAX_K_MEANS_ITERATIONS = 16
    }
}
