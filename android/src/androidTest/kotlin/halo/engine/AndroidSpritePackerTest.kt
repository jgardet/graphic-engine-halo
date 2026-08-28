package halo.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpritePackerTest {
    @Test
    fun packsOpaqueAndTransparentPixels() {
        val bitmap = Bitmap.createBitmap(
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.TRANSPARENT),
            2,
            2,
            Bitmap.Config.ARGB_8888,
        )
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val sprite = AndroidSpritePacker(resolveAsset = { bytes }).pack("asset://test.png", 2, 2, 4)

        assertEquals(2, sprite.width)
        assertEquals(2, sprite.height)
        assertEquals(16, sprite.numColors)
        assertEquals(48, sprite.paletteData.size)
        assertEquals(4, sprite.pixelData.size)
        assertEquals(0, sprite.pixelData.last().toInt())
        assertTrue(sprite.pixelData.take(3).all { it.toInt() > 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedDimensions() {
        AndroidSpritePacker(resolveAsset = { byteArrayOf() }).pack("asset://test.png", 257, 1, 4)
    }
}
