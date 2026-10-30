package io.embodit.lowvision.domain

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

/**
 * Per-pixel weights of the lens filter (design `spatialMl.filterModel`, operator-only variant; the host prototype is
 * tools/spatialml/lens_filter.py). The pipeline computes, on single-channel [H, W*3] float matrices:
 *
 *     base = src + blurMix * (blur - src)
 *     out  = keep * base + grey * greyOf(blur) + add
 *
 * keep = (1-dark)(1-veil)(1-desat), grey = (1-dark)(1-veil)desat, add = (1-dark)*veil*128 + dark*tint (0..255).
 * blur / dark / desat per disease follow the design's `derived` formulas; θ comes from an angle grid for a camera with
 * a [HFOV_DEG] horizontal field of view (the sample photos are rendered with the same). Identity (compare, S22):
 * keep = 1, everything else 0.
 */
class LensMasks(val blurMix: FloatArray, val keep: FloatArray, val grey: FloatArray, val add: FloatArray) {
    companion object {
        const val W = 640
        const val H = 480
        const val HFOV_DEG = 90f

        /** [Vision] null = identity (原样). */
        fun of(vision: Vision?, w: Int = W, h: Int = H): LensMasks {
            val n = w * h * 3
            val bm = FloatArray(n)
            val keep = FloatArray(n)
            val grey = FloatArray(n)
            val add = FloatArray(n)
            if (vision == null) {
                keep.fill(1f)
                return LensMasks(bm, keep, grey, add)
            }
            val p = vision.params
            val veil = p.veil
            val tint = intArrayOf((p.tint shr 16) and 0xFF, (p.tint shr 8) and 0xFF, p.tint and 0xFF)
            val f = (w / 2f) / tan(Math.toRadians(HFOV_DEG / 2.0)).toFloat()
            val toDeg = (180.0 / Math.PI).toFloat()
            for (row in 0 until h) {
                val y = (h / 2f - row - 0.5f) / f
                for (col in 0 until w) {
                    val x = (col + 0.5f - w / 2f) / f
                    val theta = atan(hypot(x, y)) * toDeg
                    val thetaX = atan(x) * toDeg
                    var blur: Float
                    val dark: Float
                    val desat: Float
                    when (p.disease) {
                        Disease.GLAUCOMA -> {
                            val t = smoothstep(p.rClear, p.rClear + 1.6f * p.edgeSoft, theta)
                            blur = t
                            dark = 0.75f * t
                            desat = 0.6f * t
                        }
                        Disease.AMD -> {
                            val t = 1f - smoothstep(p.rScotoma, p.rScotoma + 1.4f * p.edgeSoft, theta)
                            blur = min(1f, 1.2f * t)
                            dark = 0.55f * t
                            desat = 0.9f * t
                        }
                        Disease.HEMIANOPIA -> {
                            val t = smoothstep(-1f, 1f, p.hemiSide * thetaX - p.hemiOffset)
                            blur = t
                            dark = 0.95f * t
                            desat = 0f
                        }
                    }
                    val k = (1f - dark) * (1f - veil) * (1f - desat)
                    val g = (1f - dark) * (1f - veil) * desat
                    val base = (row * w + col) * 3
                    for (c in 0 until 3) {
                        bm[base + c] = blur
                        keep[base + c] = k
                        grey[base + c] = g
                        add[base + c] = (1f - dark) * veil * 128f + dark * tint[c]
                    }
                }
            }
            return LensMasks(bm, keep, grey, add)
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (e1 <= e0) return if (x < e0) 0f else 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Applies the masks to one RGB pixel triple the way the pipeline does (tests and the host check). */
        fun apply(m: LensMasks, i: Int, src: Float, blurred: Float, greyOfBlur: Float): Float {
            val base = src + m.blurMix[i] * (blurred - src)
            return m.keep[i] * base + m.grey[i] * greyOfBlur + m.add[i]
        }
    }
}
