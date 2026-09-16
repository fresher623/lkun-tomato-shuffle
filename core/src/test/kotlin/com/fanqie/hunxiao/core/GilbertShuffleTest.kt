package com.fanqie.hunxiao.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class GilbertShuffleTest {
    @Test fun websiteGoldenVectorsMatchBothDirections() {
        val rows = javaClass.getResourceAsStream("/website-vectors.tsv")!!.bufferedReader().readLines()
        for (row in rows) {
            val parts = row.split('\t'); val w = parts[0].toInt(); val h = parts[1].toInt()
            val input = IntArray(w*h) { it }
            for ((column, direction) in listOf(2 to GilbertShuffle.Direction.MIX, 3 to GilbertShuffle.Direction.RESTORE)) {
                assertArrayEquals("website $w x $h $direction", parts[column].split(',').map(String::toInt).toIntArray(),
                    GilbertShuffle.transform(input, w, h, direction))
            }
        }
    }

    @Test fun allSmallRectanglesAreBijections() {
        for (w in 1..50) for (h in 1..50) {
            val path = GilbertShuffle.traversal(w,h)
            assertArrayEquals("$w x $h", IntArray(w*h) { it }, path.sortedArray())
        }
    }

    @Test fun repeatedRoundTripsPreserveAllChannels() {
        val random = Random(721)
        for ((w,h) in listOf(1 to 1, 7 to 13, 128 to 97, 501 to 299)) {
            val input = IntArray(w*h) { random.nextInt() }
            var pixels = input
            repeat(3) { pixels = GilbertShuffle.transform(pixels,w,h,GilbertShuffle.Direction.MIX) }
            repeat(3) { pixels = GilbertShuffle.transform(pixels,w,h,GilbertShuffle.Direction.RESTORE) }
            assertArrayEquals(input,pixels)
        }
    }

    @Test fun cancellationStopsTraversalAndPermutation() {
        for (threshold in listOf(0.1f, 0.6f)) {
            assertThrows(CancellationException::class.java) {
                GilbertShuffle.transform(IntArray(512*512),512,512,GilbertShuffle.Direction.MIX) {
                    if (it > threshold) throw CancellationException()
                }
            }
        }
    }

    @Test fun invalidDimensionsFailBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { GilbertShuffle.traversal(0,3) }
        assertThrows(IllegalArgumentException::class.java) { GilbertShuffle.traversal(Int.MAX_VALUE,2) }
        assertThrows(IllegalArgumentException::class.java) { GilbertShuffle.transform(IntArray(6),3,3,GilbertShuffle.Direction.MIX) }
    }
}
