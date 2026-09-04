package halo.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class HrpTest {
    @Test
    fun pixelUsesTheDocumentedBigEndianLayout() {
        val actual = HrpBuilder().pixel(1, 2, "#123456").build()
        val expected = byteArrayOf(
            'H'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0,
            0, 1,
            0x03, 0, 7,
            0, 1, 0, 2, 0x12, 0x34, 0x56,
        )
        assertContentEquals(expected, actual)
    }

    @Test
    fun oversizedFrameIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            HrpBuilder(maxBytes = 8).text(0, 0, "too large")
        }
    }

    @Test
    fun spriteReleaseDirtyRegionAndFeaturesUseTheDocumentedLayout() {
        val actual = HrpBuilder()
            .spriteRelease(1)
            .dirtyRegion(4, 5, 6, 7)
            .features(0xAABBCCDDL)
            .build()
        val expected = byteArrayOf(
            'H'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0,
            0, 3,
            0x0C, 0, 2, 0, 1,
            0x0D, 0, 8, 0, 4, 0, 5, 0, 6, 0, 7,
            0x0F, 0, 4, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
        )
        assertContentEquals(expected, actual)
    }
}
