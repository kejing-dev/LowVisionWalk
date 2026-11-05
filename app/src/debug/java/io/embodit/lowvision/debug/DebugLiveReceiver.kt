package io.embodit.lowvision.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.net.Uri

/**
 * Debug builds only. Registers [DebugCommandReceiver] at run time under `io.embodit.lowvision.DEBUG_LIVE` (same extras
 * as `DEBUG`). The headset (PICO OS 6, 2026-11-05) sometimes holds broadcasts to the manifest receiver for minutes
 * (`dumpsys activity broadcasts`: "INFINITE_DEFER … DEFERRED for manifest") after it slept unworn, although the app
 * is on top; a context-registered receiver is delivered at once. Started by this provider, so main code is untouched.
 */
class DebugLiveReceiver : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.registerReceiver(DebugCommandReceiver(), IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val ACTION = "io.embodit.lowvision.DEBUG_LIVE"
    }
}
