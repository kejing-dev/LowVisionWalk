package io.embodit.lowvision.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The three kinds of vision loss of the app (design `rules.diseases`). */
enum class Disease(val id: String) {
    GLAUCOMA("glaucoma"),
    AMD("amd"),
    HEMIANOPIA("hemianopia");

    val hasLevels: Boolean
        get() = this != HEMIANOPIA

    companion object {
        fun of(id: String?): Disease? =
            when (id?.lowercase()) {
                "glaucoma", "g" -> GLAUCOMA
                "amd", "macular", "a" -> AMD
                "hemi", "hemianopia", "h" -> HEMIANOPIA
                else -> null
            }
    }
}

/** Blind side of a hemianopia: -1 left, +1 right (design `hemiSide`). */
enum class Side(val sign: Int) {
    LEFT(-1),
    RIGHT(1);

    companion object {
        fun of(id: String?): Side = if (id?.lowercase()?.startsWith("r") == true) RIGHT else LEFT
    }
}

/**
 * What the user picked: a disease, its level 1..5 (glaucoma / AMD only) and the blind side (hemianopia
 * only). The level is "示意" — it shows as degrees, never as a clinical stage.
 */
data class Vision(val disease: Disease, val level: Int = DEFAULT_LEVEL, val side: Side = Side.LEFT) {
    val params: VeilParams
        get() = VeilParams.of(this)

    /** Key for best times (disease x level, or x side for hemianopia). */
    val key: String
        get() = if (disease == Disease.HEMIANOPIA) "${disease.id}-${side.name.lowercase()}" else "${disease.id}-$level"

    fun withLevel(l: Int) = copy(level = l.coerceIn(1, LEVELS))

    companion object {
        const val LEVELS = 5
        const val DEFAULT_LEVEL = 3
    }
}

/**
 * Inputs of the veil (design `shaders.LV_Veil.inputs` + `severityTable`). Angles in degrees.
 * [tint] is ARGB without alpha (the veil colour where nothing is seen).
 */
data class VeilParams(
    val disease: Disease,
    val rClear: Float = 0f,
    val rScotoma: Float = 0f,
    val edgeSoft: Float = 0f,
    val hemiSide: Int = 0,
    val hemiOffset: Float = 0f,
    val veil: Float = 0f,
    val halo: Float = 0f,
    val tint: Int = 0,
) {
    companion object {
        private val GLAUCOMA_R = floatArrayOf(40f, 30f, 20f, 12f, 6f)
        private val GLAUCOMA_EDGE = floatArrayOf(12f, 10f, 8f, 6f, 4f)
        private val GLAUCOMA_VEIL = floatArrayOf(0f, 0.05f, 0.1f, 0.15f, 0.2f)
        private val AMD_R = floatArrayOf(2.5f, 5f, 8f, 12f, 16f)
        private val AMD_EDGE = floatArrayOf(3f, 3f, 4f, 4f, 5f)
        private val AMD_HALO = floatArrayOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        private val AMD_VEIL = floatArrayOf(0.1f, 0.15f, 0.2f, 0.25f, 0.3f)

        const val TINT_GLAUCOMA = 0x121314
        const val TINT_AMD = 0x3A3530
        const val TINT_HEMI = 0x0E0F10
        const val HEMI_OFFSET_DEG = 2f
        const val HEMI_EDGE_DEG = 2f
        const val HEMI_VEIL = 0.05f

        fun of(v: Vision): VeilParams {
            val i = (v.level - 1).coerceIn(0, Vision.LEVELS - 1)
            return when (v.disease) {
                Disease.GLAUCOMA -> VeilParams(v.disease, rClear = GLAUCOMA_R[i], edgeSoft = GLAUCOMA_EDGE[i], veil = GLAUCOMA_VEIL[i], tint = TINT_GLAUCOMA)
                Disease.AMD -> VeilParams(v.disease, rScotoma = AMD_R[i], edgeSoft = AMD_EDGE[i], halo = AMD_HALO[i], veil = AMD_VEIL[i], tint = TINT_AMD)
                Disease.HEMIANOPIA -> VeilParams(v.disease, edgeSoft = HEMI_EDGE_DEG, hemiSide = v.side.sign, hemiOffset = HEMI_OFFSET_DEG, veil = HEMI_VEIL, tint = TINT_HEMI)
            }
        }

        /** Degrees shown next to the level (design: 「剩约 20° 视野」「暗斑约 8°」). */
        fun degreesOf(v: Vision): Int? =
            when (v.disease) {
                Disease.GLAUCOMA -> GLAUCOMA_R[(v.level - 1).coerceIn(0, 4)].toInt()
                Disease.AMD -> Math.round(AMD_R[(v.level - 1).coerceIn(0, 4)])
                Disease.HEMIANOPIA -> null
            }
    }
}

/**
 * The veil math (design `shaders.LV_Veil.math`), shared by the field diagram on the home window, the
 * veil texture in the Stage and the visibility check. Angles in degrees.
 */
object VeilMath {
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How much is hidden (0 seen .. 1 not seen) at angle [theta] off the view axis, whose horizontal
     * component is [thetaX] (signed, right +). Gray veil not included.
     */
    fun alpha(p: VeilParams, theta: Float, thetaX: Float): Float =
        when (p.disease) {
            Disease.GLAUCOMA -> smoothstep(p.rClear, p.rClear + p.edgeSoft, theta)
            Disease.AMD -> {
                val core = 1f - smoothstep(p.rScotoma, p.rScotoma + p.edgeSoft, theta)
                val halo = p.halo * (1f - smoothstep(p.rScotoma, 1.6f * p.rScotoma, theta)) * 0.5f
                min(1f, core + halo)
            }
            Disease.HEMIANOPIA -> smoothstep(-p.edgeSoft / 2f, p.edgeSoft / 2f, p.hemiSide * thetaX - p.hemiOffset)
        }

    /**
     * Colour of the veil at one point as ARGB: alpha a = max(α, veil); rgb mixes mid grey towards the
     * tint by α / a (continuous, no colour ring; design R1-15).
     */
    fun argb(p: VeilParams, theta: Float, thetaX: Float): Int {
        val al = alpha(p, theta, thetaX)
        val a = max(al, p.veil)
        if (a <= 0.002f) return 0
        val k = (al / max(a, 1e-4f)).coerceIn(0f, 1f)
        fun mix(grey: Int, tint: Int) = (grey + (tint - grey) * k).toInt().coerceIn(0, 255)
        val r = mix(0x80, (p.tint shr 16) and 0xFF)
        val g = mix(0x80, (p.tint shr 8) and 0xFF)
        val b = mix(0x80, p.tint and 0xFF)
        val alpha8 = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha8 shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * The veil texture of a flat plane [distM] ahead of the eye and [halfWidthM] wide on each side,
     * [size] x [size] pixels, row 0 at the top (+Y), column 0 at the left. θ = atan(r / D) (design
     * R2-2: a flat veil turns distance into angle).
     */
    fun texture(p: VeilParams, size: Int, distM: Float = VEIL_DIST_M, halfWidthM: Float = VEIL_HALF_M): IntArray {
        val out = IntArray(size * size)
        val toDeg = (180.0 / Math.PI).toFloat()
        for (row in 0 until size) {
            val y = (0.5f - (row + 0.5f) / size) * 2f * halfWidthM
            for (col in 0 until size) {
                val x = ((col + 0.5f) / size - 0.5f) * 2f * halfWidthM
                val theta = atan(hypot(x, y) / distM) * toDeg
                val thetaX = atan(x / distM) * toDeg
                out[row * size + col] = argb(p, theta, thetaX)
            }
        }
        return out
    }

    /** Veil plane: 2 m ahead, 10 x 10 m (covers ±68°; design E1 / R1-6). */
    const val VEIL_DIST_M = 2.0f
    const val VEIL_HALF_M = 5.0f

    /**
     * Where a target lies in the view: θ (off axis) and θx (signed horizontal, right +), degrees, from
     * its position in the head frame (x right, y up, -z ahead). Behind the head θ is above 90°.
     */
    fun anglesOf(local: Vec3): Pair<Float, Float> {
        val toDeg = (180.0 / Math.PI).toFloat()
        val ahead = -local.z
        val theta = atan2(hypot(local.x, local.y), ahead) * toDeg
        val thetaX = atan2(local.x, max(ahead, 1e-4f)) * toDeg
        return theta to (if (ahead <= 0f) (if (local.x >= 0f) 90f else -90f) else thetaX)
    }

    /**
     * Visibility check (design `rules.visibilityCheck`): the target counts as seen when its centre is
     * inside the clear part of the field (thresholds sit half an edge into the soft border).
     */
    fun isVisible(p: VeilParams, theta: Float, thetaX: Float): Boolean {
        if (theta >= 90f) return false
        return when (p.disease) {
            Disease.GLAUCOMA -> theta < p.rClear + p.edgeSoft / 2f
            Disease.AMD -> theta > p.rScotoma + p.edgeSoft / 2f
            Disease.HEMIANOPIA -> p.hemiSide * thetaX < p.hemiOffset - p.edgeSoft / 2f
        }
    }

    fun isVisible(p: VeilParams, head: HeadPose, target: Vec3): Boolean {
        val local = head.rotation.conjugate().rotate(target - head.position)
        val (t, tx) = anglesOf(local)
        return isVisible(p, t, tx)
    }

    /** Degrees between two yaw angles, radians in, absolute degrees out. */
    fun yawDeltaDeg(a: Float, b: Float): Float {
        var d = a - b
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return abs(d) * (180f / Math.PI.toFloat())
    }
}
