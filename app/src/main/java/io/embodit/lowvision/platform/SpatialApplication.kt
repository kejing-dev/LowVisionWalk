package io.embodit.lowvision.platform

import android.app.Application
import android.os.Build
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk") || Build.FINGERPRINT.contains("generic")
        LvStore.init(this, Prefs(this), emulator)
        LvStore.lensHost = LensController(this)
        LvLog.i("APP start hardware=${Build.HARDWARE} product=${Build.PRODUCT} emulator=$emulator")
        launch(::mainApp)
    }
}
