package halo.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSpritePackerTest {

    private fun pngDataUri(): String {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x00000000) // transparent
        image.setRGB(1, 0, 0xFFFF0000.toInt()) // red
        image.setRGB(0, 1, 0xFF00FF00.toInt()) // green
        image.setRGB(1, 1, 0xFF0000FF.toInt()) // blue
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun packsBase64SourceIntoIndexedSprite() {
        val sprite = JvmSpritePacker().pack(pngDataUri(), null, null, bpp = 2)

        assertEquals(2, sprite.width)
        assertEquals(2, sprite.height)
        assertEquals(2, sprite.bpp)
        assertEquals(4, sprite.numColors)
        assertEquals(4, sprite.pixelData.size)
        assertEquals(12, sprite.paletteData.size)
        assertEquals(0, sprite.pixelData[0].toInt()) // transparent pixel reserves index 0
    }

    @Test
    fun rejectsInvalidBpp() {
        assertFailsWith<IllegalArgumentException> {
            JvmSpritePacker().pack(pngDataUri(), null, null, bpp = 3)
        }
    }
}
