package io.embodit.lowvision.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.lowvision.platform.LvLog

/**
 * Debug builds only. Forwards adb broadcasts (action `io.embodit.lowvision.DEBUG`) to [DebugCommands]:
 *
 *   adb shell am broadcast -a io.embodit.lowvision.DEBUG -p io.embodit.lowvision --es cmd state --es id S10 --es disease amd --ei level 3
 *
 * Every extra is read as text (`--ei` / `--ef` numbers too); `--es s <arg>` is the positional argument.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val args = HashMap<String, String>()
        val extras = intent.extras
        extras?.keySet()?.forEach { k ->
            @Suppress("DEPRECATION") val v = extras.get(k)
            if (k != "cmd" && v != null) args[k] = v.toString()
        }
        parts.getOrNull(1)?.let { args.putIfAbsent("s", it) }
        LvLog.i("CMD recv ${parts[0]} args=$args")
        DebugCommands.run(parts[0], args)
    }
}
