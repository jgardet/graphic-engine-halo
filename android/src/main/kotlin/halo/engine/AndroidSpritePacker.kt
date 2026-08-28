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
        val histogram = linkedMapOf<Int, Int>()
        pixels.forEach { color ->
            if ((color ushr 24) >= 128) {
                val red = (color ushr 16 and 0xff) and 0xf8
                val green = (color ushr 8 and 0xff) and 0xfc
                val blue = (color and 0xff) and 0xf8
                val reduced = (red shl 16) or (green shl 8) or blue
                histogram[reduced] = (histogram[reduced] ?: 0) + 1
            }
        }
        val paletteColors = histogram.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(maxColors - 1)
            .map { it.key }
        val palette = ByteArray(maxColors * 3)
        paletteColors.forEachIndexed { index, color ->
            val offset = (index + 1) * 3
            palette[offset] = (color ushr 16).toByte()
            palette[offset + 1] = (color ushr 8).toByte()
            palette[offset + 2] = color.toByte()
        }
        val indices = ByteArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            indices[index] = if ((color ushr 24) < 128 || paletteColors.isEmpty()) 0 else {
                (nearestColor(color, paletteColors) + 1).toByte()
            }
        }
        return SpritePacker.Sprite(bitmap.width, bitmap.height, bpp, maxColors, palette, indices)
    }

    private fun nearestColor(color: Int, palette: List<Int>): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return palette.indices.minBy { index ->
            val candidate = palette[index]
            val redDelta = red - (candidate ushr 16 and 0xff)
            val greenDelta = green - (candidate ushr 8 and 0xff)
            val blueDelta = blue - (candidate and 0xff)
            redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta
        }
    }
}
