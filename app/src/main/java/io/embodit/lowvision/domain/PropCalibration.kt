package io.embodit.lowvision.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** An axis-aligned box (metres, or the model file's units). */
data class Box3(val min: Vec3, val max: Vec3) {
    val size: Vec3
        get() = max - min

    val center: Vec3
        get() = (min + max) * 0.5f

    val isEmpty: Boolean
        get() = !(max.x > min.x && max.y > min.y && max.z > min.z)

    fun corners(): List<Vec3> =
        listOf(
            Vec3(min.x, min.y, min.z), Vec3(max.x, min.y, min.z), Vec3(min.x, max.y, min.z), Vec3(max.x, max.y, min.z),
            Vec3(min.x, min.y, max.z), Vec3(max.x, min.y, max.z), Vec3(min.x, max.y, max.z), Vec3(max.x, max.y, max.z),
        )

    /** Largest corner difference as a share of the larger box's largest size. */
    fun diff(o: Box3): Float {
        val scale = maxOf(size.x, size.y, size.z, o.size.x, o.size.y, o.size.z, 1e-6f)
        val d = listOf(min.x - o.min.x, min.y - o.min.y, min.z - o.min.z, max.x - o.max.x, max.y - o.max.y, max.z - o.max.z).maxOf { abs(it) }
        return d / scale
    }

    /** Same box within [tol] of the larger size on every axis (corners). */
    fun near(o: Box3, tol: Float = 0.05f): Boolean = diff(o) <= tol

    companion object {
        fun of(points: Iterable<Vec3>): Box3 {
            var lo = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            var hi = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            for (p in points) {
                lo = Vec3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z))
                hi = Vec3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z))
            }
            return Box3(lo, hi)
        }
    }
}

/**
 * The local transform of a prop's calibration node (profile D25), in an entity's TRS order: per-axis [scale], then a
 * turn of [yawDeg] about +Y, then [offset]. The scene only ever moves / turns the node's parent.
 *
 * [why]: "file" = the GLB's own baked calibration came through (identity here), "raw" = the loader dropped the file's
 * root node and the raw Tripo axes show (the baked yaw and scale are applied here instead), "fit" = an unknown box,
 * stretched onto the target box without a turn.
 */
data class PropCalibration(val scale: Vec3, val yawDeg: Float, val offset: Vec3, val why: String) {
    fun apply(p: Vec3): Vec3 = turn(Vec3(p.x * scale.x, p.y * scale.y, p.z * scale.z), yawDeg) + offset

    fun apply(box: Box3): Box3 = Box3.of(box.corners().map(::apply))

    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat())

    companion object {
        /** p turned [deg] about +Y (right-handed, +Z towards +X at +90°: the same turn as [Quat4.axisAngle]). */
        fun turn(p: Vec3, deg: Float): Vec3 {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            return Vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z)
        }

        /** From the model's box as the SDK measures it after loading, relative to the model itself. */
        fun calibrate(measured: Box3?, prop: PropModel): PropCalibration {
            val target = prop.box
            if (measured == null || measured.isEmpty) return PropCalibration(Vec3(1f, 1f, 1f), 0f, Vec3.ZERO, "file")
            // the nearer of the two wins (the sign stand's raw box is within 3 % of its calibrated one)
            val dFile = measured.diff(target)
            val dRaw = measured.diff(prop.rawBox)
            if (dFile <= 0.03f && dFile <= dRaw) return PropCalibration(Vec3(1f, 1f, 1f), 0f, Vec3.ZERO, "file")
            if (dRaw <= 0.05f) {
                // the root node was dropped: turn the raw file, then stretch it onto the target box
                val turned = Box3.of(prop.rawBox.corners().map { turn(it, prop.yawDeg) })
                val after = Vec3(target.size.x / turned.size.x, target.size.y / turned.size.y, target.size.z / turned.size.z)
                // the scale acts before the turn: a quarter turn swaps x and z
                val quarter = (Math.round(prop.yawDeg / 90f) % 2) != 0
                val before = if (quarter) Vec3(after.z, after.y, after.x) else after
                val probe = PropCalibration(before, prop.yawDeg, Vec3.ZERO, "raw").apply(prop.rawBox)
                return PropCalibration(before, prop.yawDeg, target.min - probe.min, "raw")
            }
            val s = Vec3(target.size.x / measured.size.x, target.size.y / measured.size.y, target.size.z / measured.size.z)
            val probe = PropCalibration(s, 0f, Vec3.ZERO, "fit").apply(measured)
            return PropCalibration(s, 0f, target.min - probe.min, "fit")
        }
    }
}
