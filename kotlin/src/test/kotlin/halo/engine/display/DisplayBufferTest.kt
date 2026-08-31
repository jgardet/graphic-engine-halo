package halo.engine.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DisplayBufferTest {

    @Test
    fun freshBufferIsOpaqueBlack() {
        val buf = DisplayBuffer()
        val px = buf.snapshot()
        assertEquals(DisplayBuffer.WIDTH * DisplayBuffer.HEIGHT, px.size)
        for (i in px.indices) {
            assertEquals(0xFF000000.toInt(), px[i], "pixel $i should be opaque black")
        }
    }

    @Test
    fun clearFillsWithColor() {
        val buf = DisplayBuffer()
        buf.clear(0xFF0000)
        val px = buf.snapshot()
        assertEquals(0xFFFF0000.toInt(), px[0])
        assertEquals(0xFFFF0000.toInt(), px[px.size - 1])
    }

    @Test
    fun setPixelPlacesPixelAtClampedCoordinates() {
        val buf = DisplayBuffer()
        // HRP coordinates are 0-indexed, DisplayBuffer expects 1-based (low-clamped to 1)
        // setPixel(1, 1) → pixel at (0, 0)
        buf.setPixel(1, 1, 0xFFFFFF)
        val px = buf.snapshot()
        assertEquals(0xFFFFFFFF.toInt(), px[0])

        // setPixel(0, 0) → low-clamped to (1, 1) → pixel at (0, 0)
        buf.clear()
        buf.setPixel(0, 0, 0xFF0000)
        assertEquals(0xFFFF0000.toInt(), buf.snapshot()[0])
    }

    @Test
    fun setPixelClipsOutsideDisplay() {
        val buf = DisplayBuffer()
        // setPixel(257, 257) → lowClamp(257)-1 = 256, which is out of bounds (0..255)
        buf.setPixel(257, 257, 0xFFFFFF)
        val px = buf.snapshot()
        for (i in px.indices) {
            assertEquals(0xFF000000.toInt(), px[i], "no pixel should be set")
        }
    }

    @Test
    fun lineDrawsBresenham() {
        val buf = DisplayBuffer()
        buf.line(1, 1, 5, 1, 0xFFFFFF)  // horizontal line, 5 pixels
        val px = buf.snapshot()
        for (x in 0..4) {
            assertEquals(0xFFFFFFFF.toInt(), px[x], "pixel ($x,0) should be white")
        }
        assertEquals(0xFF000000.toInt(), px[5], "pixel (5,0) should be black")
    }

    @Test
    fun rectFilledCoversAllPixels() {
        val buf = DisplayBuffer()
        buf.rect(1, 1, 3, 2, 0xFF0000, filled = true)
        val px = buf.snapshot()
        for (y in 0..1) {
            for (x in 0..2) {
                assertEquals(0xFFFF0000.toInt(), px[y * DisplayBuffer.WIDTH + x], "($x,$y) should be red")
            }
        }
        assertEquals(0xFF000000.toInt(), px[2 * DisplayBuffer.WIDTH], "(0,2) should be black")
    }

    @Test
    fun rectOutlineDrawsEdges() {
        val buf = DisplayBuffer()
        buf.rect(1, 1, 3, 3, 0xFFFFFF, filled = false)
        val px = buf.snapshot()
        // corners
        assertEquals(0xFFFFFFFF.toInt(), px[0])               // (0,0)
        assertEquals(0xFFFFFFFF.toInt(), px[2])               // (2,0)
        assertEquals(0xFFFFFFFF.toInt(), px[2 * DisplayBuffer.WIDTH])       // (0,2)
        assertEquals(0xFFFFFFFF.toInt(), px[2 * DisplayBuffer.WIDTH + 2])   // (2,2)
        // center should be black
        assertEquals(0xFF000000.toInt(), px[DisplayBuffer.WIDTH + 1])
    }

    @Test
    fun circleFilledDrawsDisk() {
        val buf = DisplayBuffer()
        buf.circle(5, 5, 3, 0xFF0000, filled = true)
        val px = buf.snapshot()
        // center should be red
        assertEquals(0xFFFF0000.toInt(), px[4 * DisplayBuffer.WIDTH + 4])
        // corner far away should be black
        assertEquals(0xFF000000.toInt(), px[0])
    }

    @Test
    fun polygonRejectsTooFewPoints() {
        val buf = DisplayBuffer()
        assertFailsWith<IllegalArgumentException> {
            buf.polygon(listOf(0 to 0, 1 to 1), 0xFFFFFF)
        }
    }

    @Test
    fun polygonRejectsTooManyPoints() {
        val buf = DisplayBuffer()
        val points = (1..65).map { it to it }
        assertFailsWith<IllegalArgumentException> {
            buf.polygon(points, 0xFFFFFF)
        }
    }

    @Test
    fun paletteDefaultsMatchFirmware() {
        val p = DisplayBuffer.DEFAULT_PALETTE
        assertEquals(16, p.size)
        // VOID (0) = black
        assertEquals(0, p[0][0]); assertEquals(0, p[0][1]); assertEquals(0, p[0][2])
        // WHITE (1) = white
        assertEquals(255, p[1][0]); assertEquals(255, p[1][1]); assertEquals(255, p[1][2])
        // RED (3)
        assertEquals(255, p[3][0]); assertEquals(0, p[3][1]); assertEquals(0, p[3][2])
        // SKYBLUE (14)
        assertEquals(135, p[14][0]); assertEquals(206, p[14][1]); assertEquals(235, p[14][2])
    }

    @Test
    fun assignColorUpdatesPalette() {
        val buf = DisplayBuffer()
        buf.assignColor(0, 10, 20, 30)
        // Draw a 4-bpp bitmap (colorFormat=16): 0x10 → pixel 0 = index 1, pixel 1 = index 0
        val data = byteArrayOf(0x10)
        buf.bitmap(1, 1, 2, 16, 0, data)
        val px = buf.snapshot()
        // pixel 0 = index 1 = WHITE (unchanged)
        assertEquals(0xFFFFFFFF.toInt(), px[0])
        // pixel 1 = index 0 = transparent (black background)
        assertEquals(0xFF000000.toInt(), px[1])
    }

    @Test
    fun ycbcrConversionMatchesFirmwareFormula() {
        // Y=15, Cb=4, Cr=4 → neutral high luminance (white-ish)
        // Y_scaled = 15*219/15+16 = 235, Cb_scaled = 4*224/7+16 = 144, Cr_scaled = 144
        // R = 235 + (91881*(144-128))>>16 = 235 + 22 = 257 → clamped to 255
        val rgb = DisplayBuffer.ycbcrToRgb(15, 4, 4)
        assertTrue(rgb[0] > 200, "Y=15,Cb=4,Cr=4 should produce high R, got ${rgb[0]}")
        assertTrue(rgb[1] > 200, "Y=15,Cb=4,Cr=4 should produce high G, got ${rgb[1]}")
        assertTrue(rgb[2] > 200, "Y=15,Cb=4,Cr=4 should produce high B, got ${rgb[2]}")
    }

    @Test
    fun brightnessLevelMapping() {
        val buf = DisplayBuffer()
        buf.setBrightness(2)
        assertEquals(100, buf.brightnessPercent())
        buf.setBrightness(-2)
        assertEquals(10, buf.brightnessPercent())
        assertFailsWith<IllegalArgumentException> { buf.setBrightness(3) }
    }

    @Test
    fun powerSaveTogglesState() {
        val buf = DisplayBuffer()
        assertEquals(false, buf.powerSave())
        buf.powerSave(true)
        assertEquals(true, buf.powerSave())
        buf.powerSave(false)
        assertEquals(false, buf.powerSave())
    }

    @Test
    fun panClampsToRange() {
        val buf = DisplayBuffer()
        buf.setPan(100, -100)
        val (x, y) = buf.getPan()
        assertEquals(50, x)
        assertEquals(-50, y)
    }
}
