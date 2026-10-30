package io.embodit.lowvision.platform

import android.util.Log
import io.embodit.lowvision.domain.Quat4
import io.embodit.lowvision.domain.Vec3
import java.util.Locale

/**
 * One line per event under the tag `LowVision`, with stable prefixes the QA scripts grep for:
 * `STATE`, `STAGE`, `PLANES`, `MODEL`, `TAP`, `DRAG`, `PANEL`, `CMD`, `DUMP`, `POSE`
 */
object LvLog {
    const val TAG = "LowVision"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    fun quat(q: Quat4?): String = if (q == null) "null" else String.format(Locale.US, "q(%.4f, %.4f, %.4f, %.4f)", q.x, q.y, q.z, q.w)
}
