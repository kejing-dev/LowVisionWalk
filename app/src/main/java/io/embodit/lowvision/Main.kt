package io.embodit.lowvision

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.foundation.dsl.Form
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.Placement
import io.embodit.lowvision.ui.home.HomeWindow
import io.embodit.lowvision.ui.home.LENS_SPOT_ID
import io.embodit.lowvision.ui.home.LensSpot
import io.embodit.lowvision.ui.theme.LvTheme
import io.embodit.lowvision.ui.walk.WALK_STAGE_ID
import io.embodit.lowvision.ui.walk.WalkScreen

/**
 * App entry (design container `STAGE_MIXED`, window model `window_plus_stage`, skeleton D): the home
 * window is the default planar window in the Shared Space (AndroidManifest `HomeWindow`, 720 x 600 dp,
 * system glass); 「戴上体验」 opens the secondary Mixed Stage. The lens window (SpatialML container) is
 * opened from the home window in the Shared Space only; its container is created in front of the lens spot window, which
 * opens to the right of the home window (v0.1.3, see LvStore.spotHost).
 */
/** Id of the home window reopened after a walk (declared below, tag [HOME_AGAIN_TAG]). */
const val HOME_AGAIN_ID = "Home"

/** Id of the default home window (AndroidManifest `pico.spatial.windowcontainer.id`). */
const val HOME_WINDOW_ID = "HomeWindow"
const val HOME_AGAIN_TAG = "home"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { LvTheme { HomeWindow() } }
        // the same home window, opened by the Stage only if restoring the minimised default one fails
        // (the home window steps aside during the walk: a Shared Space window draws over Stage content)
        WindowContainer(HOME_AGAIN_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = 720.dp, height = 600.dp)) { LvTheme { HomeWindow(HOME_AGAIN_ID, HOME_AGAIN_TAG) } }
        WindowContainer(
            LENS_SPOT_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 440.dp, height = 340.dp),
            placement = { containers.firstOrNull { it.id == HOME_WINDOW_ID || it.id == HOME_AGAIN_ID }?.let { Placement.right(it) } ?: Placement.none() },
        ) { LvTheme { LensSpot() } }
        Stage(id = WALK_STAGE_ID) { LvTheme { WalkScreen() } }
    }
