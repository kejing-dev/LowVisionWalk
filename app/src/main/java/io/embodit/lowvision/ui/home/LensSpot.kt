package io.embodit.lowvision.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import io.embodit.lowvision.app.LensRules
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.domain.Copy
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.ui.theme.LvType
import kotlinx.coroutines.delay

const val LENS_SPOT_ID = "LensSpot"

/** 340 dp up to v0.1.5 (caption above the picture); + 2 x 60 dp so the hold button's strip below clears the picture. */
const val SPOT_HEIGHT_DP = 460

/**
 * The lens spot window (v0.1.3, QA #2 q2-2 / profile D20): a small planar window to the right of the home window,
 * only there so the SpatialML container is created in front of it instead of in front of the home window (the system
 * centres the container on the focused window and has no placement parameter). All lens controls stay in the home
 * window, which no longer overlaps the container. Its glass is the bezel around the lens picture; the caption sits
 * above the container's 0.6 x 0.45 m.
 *
 * v0.1.6: 「按住看原样」 moved here from the home window, in the strip under the picture (user: 「或者应该就在那个窗口里点」),
 * so it exists only while the lens window does; [LensRules.canHold] hides it while the session is still starting or
 * has failed. The window is taller by the same strip above and below, so the system still centres the container on it.
 */
@Composable
fun LensSpot() {
    val state by LvStore.state.collectAsStateWithLifecycle()
    val focused by LocalSpatialContainerStateManager.current.isFocused
    val gen = remember { LvStore.spotGen }
    LaunchedEffect(focused) {
        LvLog.i("LENS spot focused=$focused")
        if (focused && gen == LvStore.spotGen) {
            // let the window settle where the system put it before the container is placed on it
            delay(SETTLE_MS)
            LvStore.lensSpotReady("spot focused")
        }
    }
    DisposableEffect(Unit) { onDispose { LvStore.lensSpotGone(gen) } }
    val c = Copy(state.lang)
    Box(Modifier.fillMaxSize()) {
        Text(c.lensTitle, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        if (LensRules.canHold(state.lens)) {
            HoldButton(c.compare, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).width(240.dp)) { LvStore.lensCompare(it) }
        }
    }
}

private const val SETTLE_MS = 400L
