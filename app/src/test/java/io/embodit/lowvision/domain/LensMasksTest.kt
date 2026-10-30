package io.embodit.lowvision.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensMasksTest {
    private val w = 64
    private val h = 48

    private fun idx(col: Int, row: Int, c: Int = 0) = (row * w + col) * 3 + c

    @Test
    fun identityKeepsTheImage() {
        val m = LensMasks.of(null, w, h)
        for (i in 0 until w * h * 3 step 97) assertEquals(123f, LensMasks.apply(m, i, 123f, 10f, 60f), 1e-4f)
    }

    @Test
    fun glaucomaClearCentreDarkBlurredEdge() {
        val m = LensMasks.of(Vision(Disease.GLAUCOMA, 3), w, h)
        val centre = idx(w / 2, h / 2)
        assertEquals(0f, m.blurMix[centre], 1e-3f)
        assertEquals(0.9f, m.keep[centre], 1e-3f) // only the 0.1 grey veil of level 3
        val corner = idx(0, 0)
        assertEquals(1f, m.blurMix[corner], 1e-3f)
        assertTrue(m.keep[corner] < 0.12f)
        // the corner tends to dark grey: 25 % of the picture's grey + mostly the tint
        val out = LensMasks.apply(m, corner, 200f, 150f, 150f)
        assertTrue(out in 30f..90f)
    }

    @Test
    fun amdPatchInTheMiddleOnly() {
        val m = LensMasks.of(Vision(Disease.AMD, 5), w, h)
        assertEquals(1f, m.blurMix[idx(w / 2, h / 2)], 1e-3f)
        assertEquals(0f, m.blurMix[idx(0, h / 2)], 1e-3f)
        assertTrue(m.add[idx(w / 2, h / 2)] > 10f) // darkened towards the grey-brown tint
    }

    @Test
    fun hemianopiaLeftDarkensTheLeftHalf() {
        val m = LensMasks.of(Vision(Disease.HEMIANOPIA, side = Side.LEFT), w, h)
        assertTrue(m.keep[idx(2, h / 2)] < 0.06f)
        assertTrue(m.keep[idx(w - 3, h / 2)] > 0.9f)
        val r = LensMasks.of(Vision(Disease.HEMIANOPIA, side = Side.RIGHT), w, h)
        assertTrue(r.keep[idx(w - 3, h / 2)] < 0.06f)
    }

    @Test
    fun masksAreReplicatedPerChannel() {
        val m = LensMasks.of(Vision(Disease.AMD, 3), w, h)
        val i = idx(10, 20)
        assertEquals(m.keep[i], m.keep[i + 2], 0f)
        assertEquals(m.blurMix[i], m.blurMix[i + 1], 0f)
    }
}
