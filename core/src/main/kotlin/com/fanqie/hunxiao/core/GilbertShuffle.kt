package com.fanqie.hunxiao.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/** Gilbert traversal adapted from Jakub Červený's BSD-2-Clause gilbert2d.
 * See THIRD_PARTY_NOTICES.md. Shift protocol independently implemented from
 * the observable website rule; no website UI or tracking code is included.
 */
object GilbertShuffle {
    enum class Direction { MIX, RESTORE }

    fun transform(
        input: IntArray, width: Int, height: Int, direction: Direction,
        checkpoint: (Float) -> Unit = {}
    ): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == input.size.toLong())
        val path = traversal(width, height) { checkpoint(it * 0.5f) }
        val output = IntArray(input.size)
        val shift = ((sqrt(5.0) - 1.0) / 2.0 * input.size).roundToInt()
        for (i in path.indices) {
            if (i % 8192 == 0) checkpoint(0.5f + i.toFloat() / input.size * 0.5f)
            val a = path[i]
            val b = path[(i + shift) % path.size]
            if (direction == Direction.MIX) output[b] = input[a] else output[a] = input[b]
        }
        checkpoint(1f)
        return output
    }

    fun traversal(width: Int, height: Int, checkpoint: (Float) -> Unit = {}): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        val result = IntArray(width * height)
        var cursor = 0
        fun emit(x: Int, y: Int) {
            if (cursor % 8192 == 0) checkpoint(cursor.toFloat() / result.size)
            result[cursor++] = y * width + x
        }
        fun walk(x0: Int, y0: Int, ax: Int, ay: Int, bx: Int, by: Int) {
            val w = abs(ax + ay); val h = abs(bx + by)
            val dx = ax.sign; val dy = ay.sign; val ex = bx.sign; val ey = by.sign
            var x = x0; var y = y0
            if (h == 1) { repeat(w) { emit(x, y); x += dx; y += dy }; return }
            if (w == 1) { repeat(h) { emit(x, y); x += ex; y += ey }; return }
            // Kotlin / truncates toward zero. The website uses Math.floor,
            // so negative components must use floorDiv for compatible odd grids.
            var ax2 = Math.floorDiv(ax, 2); var ay2 = Math.floorDiv(ay, 2)
            var bx2 = Math.floorDiv(bx, 2); var by2 = Math.floorDiv(by, 2)
            if (2 * w > 3 * h) {
                if (abs(ax2 + ay2) % 2 != 0 && w > 2) { ax2 += dx; ay2 += dy }
                walk(x, y, ax2, ay2, bx, by)
                walk(x + ax2, y + ay2, ax - ax2, ay - ay2, bx, by)
            } else {
                if (abs(bx2 + by2) % 2 != 0 && h > 2) { bx2 += ex; by2 += ey }
                walk(x, y, bx2, by2, ax2, ay2)
                walk(x + bx2, y + by2, ax, ay, bx - bx2, by - by2)
                walk(x + ax - dx + bx2 - ex, y + ay - dy + by2 - ey,
                    -bx2, -by2, -(ax - ax2), -(ay - ay2))
            }
        }
        if (width >= height) walk(0, 0, width, 0, 0, height)
        else walk(0, 0, 0, height, width, 0)
        check(cursor == result.size)
        return result
    }
}
