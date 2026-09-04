package halo.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Pure-JVM sprite packer using `javax.imageio.ImageIO` for image decoding.
 *
 * Quantization is shared with [AndroidSpritePacker] via [quantizeSprite].
 * This enables sprite rendering in JVM tests without Android or Python.
 */
class JvmSpritePacker(
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
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("Unable to decode sprite source")
        val targetWidth = width ?: image.width
        val targetHeight = height ?: image.height
        require(targetWidth in 1..maxDimension && targetHeight in 1..maxDimension) {
            "Sprite dimensions exceed ${maxDimension}x$maxDimension"
        }

        val scaled = if (image.width == targetWidth && image.height == targetHeight) {
            image
        } else {
            BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB).also { out ->
                out.createGraphics().also { g ->
                    g.drawImage(image, 0, 0, targetWidth, targetHeight, null)
                    g.dispose()
                }
            }
        }

        val pixels = scaled.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth)
        return quantizeSprite(pixels, targetWidth, targetHeight, bpp)
    }

    private fun sourceBytes(src: String): ByteArray {
        if (src.startsWith("data:image/") && src.contains(";base64,")) {
            return java.util.Base64.getDecoder().decode(src.substringAfter(";base64,"))
        }
        return requireNotNull(resolveAsset(src)) { "Unsupported sprite source: $src" }
    }
}
