package halo.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Android sprite packer using `BitmapFactory` for image decoding.
 *
 * Quantization is shared with [JvmSpritePacker] via [quantizeSprite].
 */
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
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            quantizeSprite(pixels, bitmap.width, bitmap.height, bpp)
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
}
