package halo.engine.display

/**
 * Platform-neutral 256×256 RGBA framebuffer.
 *
 * Backed by an [IntArray] of 0xAARRGGBB pixels (alpha always 0xFF for the
 * opaque Halo panel). No Android or AWT dependency — runs in pure JVM tests.
 *
 * Behavior mirrors Halo firmware 0.8.8 (modules/halo/src/lua_display.c and
 * modules/canvas/canvas.c): RGB global palette, non-wrapping palette_offset,
 * Dogica GFX fonts, 1-based coordinates low-clamped to 1.
 *
 * Authoritative sources:
 * - the installed `halo-emulator` package's display implementation
 * - `lua/he_runtime.lua` (device-side dispatcher)
 */
class DisplayBuffer {

    val pixels = IntArray(WIDTH * HEIGHT) { 0xFF000000.toInt() }

    private val palette = Array(DEFAULT_PALETTE.size) { DEFAULT_PALETTE[it] }
    private var fontId = 0
    private var fontMult = 1
    private var brightnessPercent = 50
    private var panX = 0
    private var panY = 0
    private var suspended = false

    /** Snapshot the current framebuffer as an immutable copy. */
    fun snapshot(): IntArray = pixels.copyOf()

    // ------------------------------------------------------------------ palette

    fun assignColor(index: Int, r: Int, g: Int, b: Int) {
        require(index in 0..15) { "color_index must be between 0 and 15" }
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB values must be between 0 and 255" }
        palette[index] = intArrayOf(r, g, b)
    }

    fun assignColorYcbcr(index: Int, y: Int, cb: Int, cr: Int) {
        require(index in 0..15) { "color_index must be between 0 and 15" }
        require(y in 0..15) { "Y value must be between 0 and 15 (4-bit)" }
        require(cb in 0..7) { "Cb value must be between 0 and 7 (3-bit)" }
        require(cr in 0..7) { "Cr value must be between 0 and 7 (3-bit)" }
        palette[index] = ycbcrToRgb(y, cb, cr)
    }

    // ------------------------------------------------------------------ clear

    fun clear(color: Int = 0) {
        val argb = 0xFF000000.toInt() or (color and 0xFFFFFF)
        java.util.Arrays.fill(pixels, argb)
    }

    // ------------------------------------------------------------------ primitives

    fun setPixel(x: Int, y: Int, color: Int) {
        val px = lowClamp(x) - 1
        val py = lowClamp(y) - 1
        if (px in 0 until WIDTH && py in 0 until HEIGHT) {
            pixels[py * WIDTH + px] = 0xFF000000.toInt() or (color and 0xFFFFFF)
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val px0 = lowClamp(x0) - 1
        val py0 = lowClamp(y0) - 1
        val px1 = lowClamp(x1) - 1
        val py1 = lowClamp(y1) - 1
        drawLine(px0, py0, px1, py1, color)
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int, filled: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val x0 = lowClamp(x) - 1
        val y0 = lowClamp(y) - 1
        val x1 = x0 + w - 1
        val y1 = y0 + h - 1
        if (filled) {
            fillRect(x0, y0, x1, y1, color)
        } else {
            drawRect(x0, y0, x1, y1, color)
        }
    }

    fun circle(cx: Int, cy: Int, r: Int, color: Int, filled: Boolean = false) {
        val cx0 = lowClamp(cx) - 1
        val cy0 = lowClamp(cy) - 1
        if (filled) {
            fillEllipse(cx0, cy0, r, r, color)
        } else {
            drawEllipse(cx0, cy0, r, r, color)
        }
    }

    fun polygon(points: List<Pair<Int, Int>>, color: Int) {
        require(points.size in 3..64) { "Halo supports 3..64 polygon points" }
        val coords = points.map { (lowClamp(it.first) - 1) to (lowClamp(it.second) - 1) }
        fillPolygon(coords, color)
    }

    // ------------------------------------------------------------------ text / font

    fun setFont(fontId: Int, size: Int = 8, scale: Int = 1) {
        require(fontId in HaloFonts.FONT_LIST.indices) { "invalid font id (must be 0-1)" }
        require(size >= 8 && size % 8 == 0) { "invalid font size (must be a multiple of 8)" }
        require(scale >= 1 && (size / 8) * scale <= 255) { "invalid scale" }
        this.fontId = fontId
        this.fontMult = (size / 8) * scale
    }

    fun text(txt: String, x: Int, y: Int, color: Int = 0xFFFFFF) {
        val font = HaloFonts.FONT_LIST[fontId].second
        var px = lowClamp(x) - 1
        val py = lowClamp(y) - 1
        for (ch in txt) {
            px += drawGfxChar(font, fontMult, ch.code, px, py, color)
        }
    }

    fun char(codepoint: Int, x: Int, y: Int, color: Int) {
        val font = HaloFonts.FONT_LIST[fontId].second
        drawGfxChar(font, fontMult, codepoint, lowClamp(x) - 1, lowClamp(y) - 1, color)
    }

    // ------------------------------------------------------------------ bitmap

    fun bitmap(
        x: Int,
        y: Int,
        width: Int,
        colorFormat: Int,
        paletteOffset: Int,
        data: ByteArray,
        xScale: Int = 1,
        yScale: Int = 1,
        customPalette: ByteArray? = null,
    ) {
        require(xScale >= 1 && yScale >= 1) { "scale factors must be positive integers" }
        require(colorFormat in setOf(0, 2, 4, 16)) { "unsupported color format: $colorFormat. Must be 0, 2, 4, or 16" }
        require(paletteOffset in 0..15) { "palette_offset must be between 0 and 15" }

        val bx = lowClamp(x) - 1
        val by = lowClamp(y) - 1
        val w = width

        if (colorFormat == 0) {
            // RGB888: 3 bytes per pixel, no palette
            val numPixels = data.size / 3
            val h = numPixels / w
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val idx = (row * w + col) * 3
                    val r = data[idx].toInt() and 0xFF
                    val g = data[idx + 1].toInt() and 0xFF
                    val b = data[idx + 2].toInt() and 0xFF
                    val rgb = (r shl 16) or (g shl 8) or b
                    for (dy in 0 until yScale) {
                        for (dx in 0 until xScale) {
                            val px = bx + col * xScale + dx
                            val py = by + row * yScale + dy
                            if (px in 0 until WIDTH && py in 0 until HEIGHT) {
                                pixels[py * WIDTH + px] = 0xFF000000.toInt() or rgb
                            }
                        }
                    }
                }
            }
            return
        }

        // Palette-indexed
        val pixelIndices = when (colorFormat) {
            2 -> unpack1Bit(data)
            4 -> unpack2Bit(data)
            else -> unpack4Bit(data)
        }
        val h = pixelIndices.size / w
        require(h > 0) { "invalid data length for given width and color format" }

        // Build the 16-entry palette for this bitmap
        val localPal = if (customPalette != null && customPalette.size >= 3 && customPalette.size % 3 == 0) {
            val numEntries = minOf(customPalette.size / 3, 16)
            Array(16) { i ->
                if (i < numEntries) {
                    intArrayOf(
                        customPalette[i * 3].toInt() and 0xFF,
                        customPalette[i * 3 + 1].toInt() and 0xFF,
                        customPalette[i * 3 + 2].toInt() and 0xFF,
                    )
                } else {
                    intArrayOf(0, 0, 0)
                }
            }
        } else {
            palette.copyOf()
        }

        for (row in 0 until h) {
            for (col in 0 until w) {
                var pidx = pixelIndices[row * w + col]
                if (pidx == 0) continue  // palette entry 0 is transparent
                pidx += paletteOffset
                if (pidx > 15) continue  // non-wrapping
                val (r, g, b) = localPal[pidx]
                val rgb = (r shl 16) or (g shl 8) or b
                for (dy in 0 until yScale) {
                    for (dx in 0 until xScale) {
                        val px = bx + col * xScale + dx
                        val py = by + row * yScale + dy
                        if (px in 0 until WIDTH && py in 0 until HEIGHT) {
                            pixels[py * WIDTH + px] = 0xFF000000.toInt() or rgb
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ brightness / pan / power

    fun setBrightness(level: Int) {
        require(level in -2..2) { "brightness level must be between -2 and 2" }
        brightnessPercent = BRIGHTNESS_LEVEL_TO_PERCENT[level]!!
    }

    fun getBrightness(): Int = BRIGHTNESS_PERCENT_TO_LEVEL[brightnessPercent] ?: 0

    fun brightnessPercent(value: Int? = null): Int? {
        if (value == null) return brightnessPercent
        require(value in 0..100) { "Brightness must be 0-100" }
        brightnessPercent = value
        return null
    }

    fun setPan(x: Int, y: Int) {
        panX = x.coerceIn(-50, 50)
        panY = y.coerceIn(-50, 50)
    }

    fun getPan(): Pair<Int, Int> = panX to panY

    fun powerSave(enable: Boolean? = null): Boolean? {
        if (enable == null) return suspended
        suspended = enable
        return null
    }

    // ------------------------------------------------------------------ internal drawing

    private fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        // Bresenham's line algorithm, matching PIL's inclusive endpoint behavior
        var dx = kotlin.math.abs(x1 - x0)
        var dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        var x = x0
        var y = y0
        while (true) {
            putPixelClipped(x, y, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    private fun drawRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        drawLine(x0, y0, x1, y0, color)
        drawLine(x0, y1, x1, y1, color)
        drawLine(x0, y0, x0, y1, color)
        drawLine(x1, y0, x1, y1, color)
    }

    private fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val xa = maxOf(0, x0)
        val xb = minOf(WIDTH - 1, x1)
        val ya = maxOf(0, y0)
        val yb = minOf(HEIGHT - 1, y1)
        for (y in ya..yb) {
            for (x in xa..xb) {
                pixels[y * WIDTH + x] = 0xFF000000.toInt() or (color and 0xFFFFFF)
            }
        }
    }

    private fun fillEllipse(cx: Int, cy: Int, rx: Int, ry: Int, color: Int) {
        if (rx <= 0 || ry <= 0) return
        for (y in -ry..ry) {
            for (x in -rx..rx) {
                val ellipseVal = (x * x * ry * ry + y * y * rx * rx).toLong()
                if (ellipseVal <= (rx * ry).toLong() * (rx * ry)) {
                    putPixelClipped(cx + x, cy + y, color)
                }
            }
        }
    }

    private fun drawEllipse(cx: Int, cy: Int, rx: Int, ry: Int, color: Int) {
        if (rx <= 0 || ry <= 0) return
        // Draw the filled ellipse boundary by checking the edge
        for (y in -ry..ry) {
            for (x in -rx..rx) {
                val inside = (x * x * ry * ry + y * y * rx * rx).toLong() <= (rx * ry).toLong() * (rx * ry)
                if (!inside) continue
                val outside = (x + 1) * (x + 1) * ry * ry + y * y * rx * rx > (rx * ry).toLong() * (rx * ry) ||
                    (x - 1) * (x - 1) * ry * ry + y * y * rx * rx > (rx * ry).toLong() * (rx * ry) ||
                    x * x * (y + 1) * (y + 1) * rx * rx > (rx * ry).toLong() * (rx * ry) ||
                    x * x * (y - 1) * (y - 1) * rx * rx > (rx * ry).toLong() * (rx * ry)
                if (outside) {
                    putPixelClipped(cx + x, cy + y, color)
                }
            }
        }
    }

    private fun fillPolygon(coords: List<Pair<Int, Int>>, color: Int) {
        if (coords.size < 3) return
        val minY = coords.minOf { it.second }
        val maxY = coords.maxOf { it.second }
        for (y in minY..maxY) {
            if (y < 0 || y >= HEIGHT) continue
            val intersections = mutableListOf<Int>()
            for (i in coords.indices) {
                val (x0, y0) = coords[i]
                val (x1, y1) = coords[(i + 1) % coords.size]
                if (y0 == y1) continue
                val yt = if (y0 < y1) y0..y1 else y1..y0
                if (y in yt && y != yt.last) {
                    val t = (y - y0).toDouble() / (y1 - y0)
                    intersections.add((x0 + t * (x1 - x0)).toInt())
                }
            }
            intersections.sort()
            var i = 0
            while (i + 1 < intersections.size) {
                val xa = maxOf(0, intersections[i])
                val xb = minOf(WIDTH - 1, intersections[i + 1])
                for (x in xa..xb) {
                    pixels[y * WIDTH + x] = 0xFF000000.toInt() or (color and 0xFFFFFF)
                }
                i += 2
            }
        }
    }

    /**
     * Port of canvas_draw_char: returns the x-advance in pixels.
     *
     * `x`, `y` are 0-based; `y` is the top of the cap-height box (the
     * firmware measures ascent from 'H' and shifts to the baseline).
     * Mirrors the firmware's inclusive-endpoint horizontal-run quirk at
     * mult == 1, which paints one extra trailing pixel per run.
     */
    private fun drawGfxChar(font: HaloFonts.GfxFont, mult: Int, c: Int, x: Int, y: Int, color: Int): Int {
        if (c < font.first || c > font.last) return 0
        val glyph = font.glyphs[c - font.first]
        val ascent = font.glyphs['H'.code - font.first].yOffset  // -7 for Dogica
        val baselineY = y - ascent * mult
        var bo = glyph.bitmapOffset
        var bits = 0
        var bit = 0
        val xo = glyph.xOffset
        val yo = glyph.yOffset
        for (yy in 0 until glyph.height) {
            var hpc = 0
            for (xx in 0 until glyph.width) {
                if (bit == 0) {
                    bits = font.bitmaps[bo].toInt() and 0xFF
                    bo++
                    bit = 0x80
                }
                if ((bits and bit) != 0) {
                    hpc++
                } else {
                    if (hpc > 0) {
                        if (mult == 1) {
                            hline(x + xo + xx - hpc, x + xo + xx, baselineY + yo + yy, color)
                        } else {
                            fillRect(
                                x + (xo + xx - hpc) * mult,
                                baselineY + (yo + yy) * mult,
                                x + (xo + xx) * mult - 1,
                                baselineY + (yo + yy) * mult + mult - 1,
                                color,
                            )
                        }
                        hpc = 0
                    }
                }
                bit = bit shr 1
            }
            if (hpc > 0) {
                val xx = glyph.width
                if (mult == 1) {
                    hline(x + xo + xx - hpc, x + xo + xx, baselineY + yo + yy, color)
                } else {
                    fillRect(
                        x + (xo + xx - hpc) * mult,
                        baselineY + (yo + yy) * mult,
                        x + (xo + xx) * mult - 1,
                        baselineY + (yo + yy) * mult + mult - 1,
                        color,
                    )
                }
            }
        }
        return glyph.xAdvance * mult
    }

    private fun hline(x0: Int, x1: Int, y: Int, color: Int) {
        if (y < 0 || y >= HEIGHT) return
        val xa = maxOf(0, x0)
        val xb = minOf(WIDTH - 1, x1)
        for (px in xa..xb) {
            pixels[y * WIDTH + px] = 0xFF000000.toInt() or (color and 0xFFFFFF)
        }
    }

    private fun putPixelClipped(x: Int, y: Int, color: Int) {
        if (x in 0 until WIDTH && y in 0 until HEIGHT) {
            pixels[y * WIDTH + x] = 0xFF000000.toInt() or (color and 0xFFFFFF)
        }
    }

    companion object {
        const val WIDTH = 256
        const val HEIGHT = 256

        /** Firmware default palette (lua_display.c, stored as RGB since 0.8.8). */
        val DEFAULT_PALETTE: Array<IntArray> = arrayOf(
            intArrayOf(0, 0, 0),        // 0 VOID
            intArrayOf(255, 255, 255),  // 1 WHITE
            intArrayOf(128, 128, 128),  // 2 GREY
            intArrayOf(255, 0, 0),      // 3 RED
            intArrayOf(255, 192, 203),  // 4 PINK
            intArrayOf(101, 67, 33),    // 5 DARKBROWN
            intArrayOf(150, 75, 0),     // 6 BROWN
            intArrayOf(255, 165, 0),    // 7 ORANGE
            intArrayOf(255, 255, 0),    // 8 YELLOW
            intArrayOf(0, 100, 0),      // 9 DARKGREEN
            intArrayOf(0, 255, 0),      // 10 GREEN
            intArrayOf(144, 238, 144),  // 11 LIGHTGREEN
            intArrayOf(25, 25, 112),    // 12 NIGHTBLUE
            intArrayOf(0, 0, 205),      // 13 SEABLUE
            intArrayOf(135, 206, 235),  // 14 SKYBLUE
            intArrayOf(240, 248, 255),  // 15 CLOUDBLUE
        )

        val BRIGHTNESS_LEVEL_TO_PERCENT = mapOf(-2 to 10, -1 to 25, 0 to 50, 1 to 75, 2 to 100)
        val BRIGHTNESS_PERCENT_TO_LEVEL = mapOf(10 to -2, 25 to -1, 50 to 0, 75 to 1, 100 to 2)

        /** Firmware clamps 1-based coordinates low to 1 (no high clamp). */
        fun lowClamp(v: Int): Int = if (v < 1) 1 else v

        /** Convert 4-bit Y, 3-bit Cb/Cr to RGB888 (firmware ycbcr_to_rgb888_fast). */
        fun ycbcrToRgb(y: Int, cb: Int, cr: Int): IntArray {
            val yScaled = (y * 219 / 15) + 16
            val cbScaled = (cb * 224 / 7) + 16
            val crScaled = (cr * 224 / 7) + 16
            val cbOffset = cbScaled - 128
            val crOffset = crScaled - 128
            val r = yScaled + ((91881 * crOffset) shr 16)
            val g = yScaled - ((22554 * cbOffset + 46788 * crOffset) shr 16)
            val b = yScaled + ((116130 * cbOffset) shr 16)
            return intArrayOf(clampU8(r), clampU8(g), clampU8(b))
        }

        private fun clampU8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

        private fun unpack1Bit(data: ByteArray): IntArray {
            val result = IntArray(data.size * 8)
            for (i in data.indices) {
                val b = data[i].toInt() and 0xFF
                result[i * 8] = (b shr 7) and 0x1
                result[i * 8 + 1] = (b shr 6) and 0x1
                result[i * 8 + 2] = (b shr 5) and 0x1
                result[i * 8 + 3] = (b shr 4) and 0x1
                result[i * 8 + 4] = (b shr 3) and 0x1
                result[i * 8 + 5] = (b shr 2) and 0x1
                result[i * 8 + 6] = (b shr 1) and 0x1
                result[i * 8 + 7] = b and 0x1
            }
            return result
        }

        private fun unpack2Bit(data: ByteArray): IntArray {
            val result = IntArray(data.size * 4)
            for (i in data.indices) {
                val b = data[i].toInt() and 0xFF
                result[i * 4] = (b shr 6) and 0x3
                result[i * 4 + 1] = (b shr 4) and 0x3
                result[i * 4 + 2] = (b shr 2) and 0x3
                result[i * 4 + 3] = b and 0x3
            }
            return result
        }

        private fun unpack4Bit(data: ByteArray): IntArray {
            val result = IntArray(data.size * 2)
            for (i in data.indices) {
                val b = data[i].toInt() and 0xFF
                result[i * 2] = (b shr 4) and 0xF
                result[i * 2 + 1] = b and 0xF
            }
            return result
        }
    }
}
