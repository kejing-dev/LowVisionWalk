package io.embodit.lowvision.domain

import kotlin.math.abs
import kotlin.math.asin

/**
 * v0.1.5 (QA #3 q3-1, profile D9; factory `native-window-move.md` §7 item 9): a Planar system window opens centred on
 * the line of sight with its face square to it, and nothing can tilt it afterwards. Opened while the head is down 30°
 * it leans back 30° and keeps doing so. So the home window is only (re)opened while the head is level: the caller feeds
 * the time and the head pitch every Stage frame, and the gate says when to open.
 *
 * Pure Kotlin, unit tested (`LevelGateTest`). Pitch in degrees, + looking up; null = no valid head pose this frame.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean
        get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** The reason to open now, or null to keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            // counted from the first frame without a head, not from the ask (the Stage's first frame can come seconds later)
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) {
            levelSinceMs = -1L
            return null
        }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    /** -1 = ask to look up, +1 = ask to look down, 0 = no hint. */
    fun hint(pitchDeg: Float?): Int =
        when {
            pending == null || pitchDeg == null || !pitchDeg.isFinite() -> 0
            pitchDeg < -maxPitchDeg -> -1
            pitchDeg > maxPitchDeg -> 1
            else -> 0
        }

    companion object {
        /**
         * The level hint sits [HINT_ABOVE_DEG] above the line of sight (looking down 30°, the pill strip is right on it),
         * clamped to ±[HINT_MAX_DEG], [HINT_DIST_M] away; upright (D9).
         */
        const val HINT_MAX_DEG = 40f
        const val HINT_ABOVE_DEG = 6f
        const val HINT_DIST_M = 0.75f

        /** Head pitch in degrees (+ up), or null for no pose or the all-zero / identity pose of a tracker not yet up. */
        fun pitchDeg(head: HeadPose?): Float? {
            if (head == null) return null
            val q = head.rotation
            val identity = abs(q.x) < 1e-6f && abs(q.y) < 1e-6f && abs(q.z) < 1e-6f
            if (head.position.length < 1e-3f && identity) return null
            if (!head.position.isFinite()) return null
            val f = head.forward
            return Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        /** Where the hint goes for this pitch: just above the view's pitch (clamped), upright and turned about the vertical only. */
        fun hintSpot(pitchDeg: Float, base: PanelSpot): PanelSpot =
            base.copy(distM = HINT_DIST_M, dropDeg = -(pitchDeg + HINT_ABOVE_DEG).coerceIn(-HINT_MAX_DEG, HINT_MAX_DEG))
    }
}
