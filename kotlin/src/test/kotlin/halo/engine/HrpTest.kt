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
}
