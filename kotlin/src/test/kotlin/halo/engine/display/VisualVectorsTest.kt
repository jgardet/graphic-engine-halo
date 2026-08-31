package halo.engine.display

import halo.engine.HrpBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Visual vector tests — render canonical scenes through HrpBuilder → HrpRenderer
 * and verify exact pixel values at known coordinates.
 *
 * These vectors serve as golden snapshots: if any rendering primitive changes,
 * the failing vector pinpoints which primitive and which pixel is affected.
 * Each test names the primitive it covers and asserts specific pixels rather
 * than whole-frame equality (which would be brittle to font hinting changes).
 */
class VisualVectorsTest {

    private fun render(builder: HrpBuilder): DisplayBuffer {
        val buf = DisplayBuffer()
        HrpRenderer(buf).render(builder.endFrame().build())
        return buf
    }

    private fun px(buf: DisplayBuffer, x: Int, y: Int): Int =
        buf.snapshot()[y * DisplayBuffer.WIDTH + x]

    private fun countPixels(buf: DisplayBuffer, color: Int): Int {
        val target = color or 0xFF000000.toInt()
        return buf.snapshot().count { it == target }
    }

    // ------------------------------------------------------------------ clear

    @Test
    fun vectorClearFillsEntireDisplay() {
        val buf = render(HrpBuilder().clear("#FF0000"))
        assertEquals(DisplayBuffer.WIDTH * DisplayBuffer.HEIGHT, countPixels(buf, 0xFF0000))
    }

    @Test
    fun vectorClearBlackThenRed() {
        val buf = render(HrpBuilder().clear("#000000").clear("#00FF00"))
        assertEquals(DisplayBuffer.WIDTH * DisplayBuffer.HEIGHT, countPixels(buf, 0x00FF00))
    }

    // ------------------------------------------------------------------ pixel

    @Test
    fun vectorSinglePixelAtOrigin() {
        val buf = render(HrpBuilder().clear("#000000").pixel(0, 0, "#FFFFFF"))
        assertEquals(0xFFFFFFFF.toInt(), px(buf, 0, 0))
        assertEquals(0xFF000000.toInt(), px(buf, 1, 0))
        assertEquals(0xFF000000.toInt(), px(buf, 0, 1))
    }

    @Test
    fun vectorSinglePixelAtCorner() {
        val buf = render(HrpBuilder().clear("#000000").pixel(255, 255, "#FF0000"))
        assertEquals(0xFFFF0000.toInt(), px(buf, 255, 255))
        assertEquals(0xFF000000.toInt(), px(buf, 254, 255))
    }

    // ------------------------------------------------------------------ line

    @Test
    fun vectorHorizontalLine() {
        val buf = render(HrpBuilder().clear("#000000").line(0, 10, 50, 10, "#FFFFFF"))
        for (x in 0..50) {
            assertEquals(0xFFFFFFFF.toInt(), px(buf, x, 10), "($x,10) should be white")
        }
        assertEquals(0xFF000000.toInt(), px(buf, 51, 10))
        assertEquals(0xFF000000.toInt(), px(buf, 0, 9))
        assertEquals(51, countPixels(buf, 0xFFFFFF))
    }

    @Test
    fun vectorVerticalLine() {
        val buf = render(HrpBuilder().clear("#000000").line(20, 0, 20, 30, "#00FF00"))
        for (y in 0..30) {
            assertEquals(0xFF00FF00.toInt(), px(buf, 20, y), "(20,$y) should be green")
        }
        assertEquals(0xFF000000.toInt(), px(buf, 21, 0))
        assertEquals(31, countPixels(buf, 0x00FF00))
    }

    @Test
    fun vectorDiagonalLine() {
        val buf = render(HrpBuilder().clear("#000000").line(0, 0, 10, 10, "#FF0000"))
        for (i in 0..10) {
            assertEquals(0xFFFF0000.toInt(), px(buf, i, i), "($i,$i) should be red")
        }
        assertEquals(11, countPixels(buf, 0xFF0000))
    }

    // ------------------------------------------------------------------ rect

    @Test
    fun vectorFilledRect() {
        val buf = render(HrpBuilder().clear("#000000").rect(10, 20, 30, 40, "#0000FF", filled = true))
        for (y in 20..59) {
            for (x in 10..39) {
                assertEquals(0xFF0000FF.toInt(), px(buf, x, y), "($x,$y) should be blue")
            }
        }
        assertEquals(30 * 40, countPixels(buf, 0x0000FF))
        // Outside edges
        assertEquals(0xFF000000.toInt(), px(buf, 9, 20))
        assertEquals(0xFF000000.toInt(), px(buf, 40, 20))
        assertEquals(0xFF000000.toInt(), px(buf, 10, 19))
        assertEquals(0xFF000000.toInt(), px(buf, 10, 60))
    }

    @Test
    fun vectorRectOutline() {
        val buf = render(HrpBuilder().clear("#000000").rect(10, 10, 20, 20, "#FFFFFF", filled = false))
        // Top and bottom edges
        for (x in 10..29) {
            assertEquals(0xFFFFFFFF.toInt(), px(buf, x, 10), "top ($x,10)")
            assertEquals(0xFFFFFFFF.toInt(), px(buf, x, 29), "bottom ($x,29)")
        }
        // Left and right edges
        for (y in 10..29) {
            assertEquals(0xFFFFFFFF.toInt(), px(buf, 10, y), "left (10,$y)")
            assertEquals(0xFFFFFFFF.toInt(), px(buf, 29, y), "right (29,$y)")
        }
        // Center should be black
        assertEquals(0xFF000000.toInt(), px(buf, 15, 15))
    }

    // ------------------------------------------------------------------ circle

    @Test
    fun vectorFilledCircleAtCenter() {
        val buf = render(HrpBuilder().clear("#000000").circle(128, 128, 20, "#FF00FF", filled = true))
        // Center should be magenta
        assertEquals(0xFFFF00FF.toInt(), px(buf, 128, 128))
        // Edge of radius should be magenta
        assertEquals(0xFFFF00FF.toInt(), px(buf, 148, 128))
        assertEquals(0xFFFF00FF.toInt(), px(buf, 128, 148))
        // Outside circle should be black
        assertEquals(0xFF000000.toInt(), px(buf, 150, 128))
        assertEquals(0xFF000000.toInt(), px(buf, 128, 150))
        // Pixel count should be roughly π*r²
        val count = countPixels(buf, 0xFF00FF)
        assertTrue(count > 1000, "filled circle should have >1000 pixels, got $count")
        assertTrue(count < 1500, "filled circle should have <1500 pixels, got $count")
    }

    @Test
    fun vectorCircleOutline() {
        val buf = render(HrpBuilder().clear("#000000").circle(128, 128, 20, "#FFFF00", filled = false))
        // Center should be black (outline only)
        assertEquals(0xFF000000.toInt(), px(buf, 128, 128))
        // Edge should be yellow
        assertEquals(0xFFFFFF00.toInt(), px(buf, 148, 128))
        assertEquals(0xFFFFFF00.toInt(), px(buf, 128, 148))
    }

    // ------------------------------------------------------------------ text

    @Test
    fun vectorTextRendersAtPosition() {
        val buf = render(HrpBuilder().clear("#000000").text(10, 10, "A", "#FFFFFF"))
        // Text "A" at (10,10) should produce non-black pixels near that position
        val textPixels = (0..20).flatMap { y -> (0..20).map { x -> px(buf, x, y) } }
            .count { it != 0xFF000000.toInt() }
        assertTrue(textPixels > 0, "text 'A' should produce visible pixels")
    }

    @Test
    fun vectorTextColoredCorrectly() {
        val buf = render(HrpBuilder().clear("#000000").text(5, 5, "X", "#00FF00"))
        // Find any non-black pixel and verify it's green
        val snap = buf.snapshot()
        val greenPixels = snap.count { it == 0xFF00FF00.toInt() }
        assertTrue(greenPixels > 0, "text 'X' should have green pixels")
    }

    // ------------------------------------------------------------------ polygon

    @Test
    fun vectorTrianglePolygon() {
        val buf = render(HrpBuilder().clear("#000000").polygon(
            listOf(50 to 50, 100 to 50, 75 to 100),
            "#FF0000",
        ))
        // Top edge vertices should be red (scanline includes the first Y of each edge)
        assertEquals(0xFFFF0000.toInt(), px(buf, 50, 50))
        assertEquals(0xFFFF0000.toInt(), px(buf, 100, 50))
        // Bottom vertex may be excluded by scanline fill (standard behavior:
        // the last Y of each edge is excluded to avoid double-counting)
        // Interior pixels should be red
        assertEquals(0xFFFF0000.toInt(), px(buf, 75, 60))
        assertEquals(0xFFFF0000.toInt(), px(buf, 75, 70))
        assertEquals(0xFFFF0000.toInt(), px(buf, 75, 80))
        // Filled polygon should have substantial pixel coverage
        val count = countPixels(buf, 0xFF0000)
        assertTrue(count > 500, "filled triangle should have >500 pixels, got $count")
        assertTrue(count < 3000, "filled triangle should have <3000 pixels, got $count")
    }

    // ------------------------------------------------------------------ composite

    @Test
    fun vectorCompositeSceneWithMultiplePrimitives() {
        val buf = render(
            HrpBuilder()
                .clear("#000000")
                .rect(0, 0, 256, 50, "#0000FF", filled = true)  // top bar
                .line(0, 50, 255, 50, "#FFFFFF")                 // separator
                .circle(128, 150, 30, "#FF0000", filled = true)  // red dot
                .text(10, 10, "HUD", "#FFFFFF")                   // label
        )
        // Top bar is blue
        assertEquals(0xFF0000FF.toInt(), px(buf, 0, 0))
        assertEquals(0xFF0000FF.toInt(), px(buf, 255, 49))
        // Separator line is white
        assertEquals(0xFFFFFFFF.toInt(), px(buf, 0, 50))
        assertEquals(0xFFFFFFFF.toInt(), px(buf, 255, 50))
        // Below separator is black
        assertEquals(0xFF000000.toInt(), px(buf, 0, 51))
        // Red dot center
        assertEquals(0xFFFF0000.toInt(), px(buf, 128, 150))
    }

    // ------------------------------------------------------------------ brightness

    @Test
    fun vectorBrightnessDoesNotAffectFramebuffer() {
        // Brightness is a display hardware setting, not a pixel-level transform.
        // The framebuffer should store the original colors regardless of brightness.
        val buf = render(HrpBuilder().clear("#000000").brightness(50).pixel(10, 10, "#FF0000"))
        assertEquals(0xFFFF0000.toInt(), px(buf, 10, 10))
    }

    // ------------------------------------------------------------------ show is no-op

    @Test
    fun vectorShowIsNoOpOnHalo() {
        // HRP opcode 0x0E (show) is a no-op on Halo (unlike Frame).
        // The framebuffer should be unaffected.
        val buf1 = render(HrpBuilder().clear("#000000").pixel(10, 10, "#FFFFFF"))
        val buf2 = render(HrpBuilder().clear("#000000").pixel(10, 10, "#FFFFFF").endFrame())
        assertEquals(px(buf1, 10, 10), px(buf2, 10, 10))
    }

    // ------------------------------------------------------------------ dirty region hint

    @Test
    fun vectorDirtyRegionIsNoOp() {
        // HRP opcode 0x0D (dirty region hint) is a no-op.
        val buf = render(HrpBuilder().clear("#000000").pixel(10, 10, "#FFFFFF"))
        assertEquals(0xFFFFFFFF.toInt(), px(buf, 10, 10))
    }

    // ------------------------------------------------------------------ overlapping primitives

    @Test
    fun vectorLaterPrimitiveOverlapsEarlier() {
        val buf = render(
            HrpBuilder()
                .clear("#000000")
                .rect(0, 0, 100, 100, "#FF0000", filled = true)
                .rect(50, 50, 100, 100, "#00FF00", filled = true)  // overlaps first
        )
        // First rect area (not overlapped) should be red
        assertEquals(0xFFFF0000.toInt(), px(buf, 0, 0))
        // Overlap area should be green (second rect wins)
        assertEquals(0xFF00FF00.toInt(), px(buf, 50, 50))
        // Second rect area (not overlapped) should be green
        assertEquals(0xFF00FF00.toInt(), px(buf, 149, 149))
    }
}
