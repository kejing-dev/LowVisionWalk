package io.embodit.lowvision.platform

import android.content.Context
import android.content.SharedPreferences
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.Vision
import java.util.Locale

/** Local settings and best times (design `persistence.local`). Scene layout is not kept across walks. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("low_vision", Context.MODE_PRIVATE)

    fun lang(): Lang =
        when (p.getString("lang", null)) {
            "en" -> Lang.EN
            "zh" -> Lang.ZH
            else -> if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN
        }

    fun setLang(l: Lang) = p.edit().putString("lang", if (l == Lang.EN) "en" else "zh").apply()

    fun muted() = p.getBoolean("muted", false)

    fun setMuted(m: Boolean) = p.edit().putBoolean("muted", m).apply()

    fun safetySeen() = p.getBoolean("safetySeen", false)

    fun setSafetySeen() = p.edit().putBoolean("safetySeen", true).apply()

    fun showSafetyNext() = p.getBoolean("showSafetyNext", false)

    fun setShowSafetyNext(on: Boolean) = p.edit().putBoolean("showSafetyNext", on).apply()

    fun lastVision(): Vision? {
        val d = Disease.of(p.getString("disease", null)) ?: return null
        return Vision(d, lastLevel(d), lastSide())
    }

    fun lastLevel(d: Disease) = p.getInt("level_${d.id}", Vision.DEFAULT_LEVEL)

    fun lastSide() = Side.of(p.getString("side", "left"))

    fun saveVision(v: Vision) =
        p.edit().putString("disease", v.disease.id).putInt("level_${v.disease.id}", v.level).putString("side", v.side.name.lowercase()).apply()

    fun bestTimes(): Map<String, Long> = p.all.filterKeys { it.startsWith("best:") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("best:") to it } }.toMap()

    fun saveBest(key: String, ms: Long) = p.edit().putLong("best:$key", ms).apply()

    fun clear() = p.edit().clear().apply()
}
