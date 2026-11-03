package io.embodit.lowvision.ui.walk

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.HOME_AGAIN_ID
import io.embodit.lowvision.HOME_AGAIN_TAG
import androidx.compose.runtime.rememberCoroutineScope
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.openWindowContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.embodit.lowvision.domain.PanelIds
import io.embodit.lowvision.platform.LvLog

/** Id of the walk Stage (declared in Main.kt, opened from the home window with style Mixed). */
const val WALK_STAGE_ID = "WalkStage"

private const val HOME_REOPEN_DELAY_MS = 1_000L

/**
 * The Full Space walk (design 「戴上体验」): one full-size SpatialView with the 3D scene and the panel pool.
 * Every panel is declared once with a fixed size; [WalkScene] parents each under its holder and shows or
 * hides it. Lifecycle (design container.lifecycle): the scene starts head, plane and controller tracking
 * when the Stage opens and stops them, destroying every entity, when it closes. Going to the background
 * and back counts as an interruption (S26): the veil comes off and a running task is void.
 */
@Composable
fun WalkScreen() {
    val context = LocalContext.current
    val state by LvStore.state.collectAsStateWithLifecycle()
    val scene = remember { WalkScene() }
    val audio = LocalAudioEffectPlayer.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    DisposableEffect(scene) {
        registerSystem<WalkFrameSystem>()
        LvStore.soundHost = { s ->
            val effect =
                when (s) {
                    LvStore.Sound.FOUND -> SpatialSoundEffect.StateSuccess
                    LvStore.Sound.WRONG -> SpatialSoundEffect.StateFailure
                    LvStore.Sound.STEPPED -> SpatialSoundEffect.OpDragEnd
                }
            runCatching { audio.playSystem(effect) }
        }
        LvStore.stageCloser = {
            scope.launch(Dispatchers.Main.immediate) {
                // the home window comes back first, while the Stage is still open: closing the last container hands the
                // focus to the system launcher, and a window opened after that stays behind it (emulator, v0.1.0 #19)
                LvStore.hiddenHome?.let { (id, tag) ->
                    LvStore.hiddenHome = null
                    val reopened = runCatching { context.applicationContext.openWindowContainer(id, tag) }
                    LvLog.i("HOME reopened before closing the Stage $id/$tag: ${reopened.exceptionOrNull() ?: "ok"}")
                    delay(HOME_REOPEN_DELAY_MS)
                }
                runCatching { navigator.closeStage() }.onFailure { LvLog.w("STAGE close failed: $it") }
            }
        }
        LvStore.stageEntered()
        LvLog.i("STAGE enter")
        var paused = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> paused = true
                    Lifecycle.Event.ON_RESUME ->
                        if (paused) {
                            paused = false
                            LvStore.interrupted()
                        }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregisterSystem<WalkFrameSystem>()
            LvStore.soundHost = null
            LvStore.stageCloser = null
            scene.detach()
            LvStore.stageClosed()
            LvStore.hiddenHome?.let { (id, tag) ->
                LvStore.hiddenHome = null
                // through the application context: this Stage's navigator is already closing
                // the same window id comes back (the DSL twin "Home" opened out of view on the emulator), a moment after
                // the Stage has gone: reopened while the Stage was still closing, the focus went to the system launcher
                val app = context.applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val reopened = runCatching { app.openWindowContainer(id, tag) }
                    LvLog.i("HOME reopened $id/$tag: ${reopened.exceptionOrNull() ?: "ok"}")
                    if (reopened.isFailure) runCatching { app.openWindowContainer(HOME_AGAIN_ID, HOME_AGAIN_TAG) }
                }, HOME_REOPEN_DELAY_MS)
            }
            LvLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val bindTick = scene.bindTick.intValue
    fun shown(id: String) = if (scene.panelShown[id] == true) state else null
    SpatialView(
        modifier =
            Modifier.fillMaxSize()
                // tap and drag live in separate pointerInput blocks (Spatial Ruler finding)
                .pointerInput(Unit) { detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { scene.onSpatialTap(it) } }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value ->
                        scene.onSpatialDrag("move", value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelIds.SAFETY) { PanelBox(PanelIds.SAFETY) { SafetyCard(shown(PanelIds.SAFETY)) } }
            AttachmentPanel(PanelIds.HINT) { PanelBox(PanelIds.HINT) { HintBar(shown(PanelIds.HINT)) } }
            AttachmentPanel(PanelIds.PILL) { PanelBox(PanelIds.PILL) { Pill(shown(PanelIds.PILL)) } }
            AttachmentPanel(PanelIds.MENU) { PanelBox(PanelIds.MENU) { PillMenu(shown(PanelIds.MENU)) } }
            AttachmentPanel(PanelIds.TASKS) { PanelBox(PanelIds.TASKS) { TaskList(shown(PanelIds.TASKS)) } }
            AttachmentPanel(PanelIds.RESULT) { PanelBox(PanelIds.RESULT) { ResultCard(shown(PanelIds.RESULT)) } }
            AttachmentPanel(PanelIds.CHECKLIST) { PanelBox(PanelIds.CHECKLIST) { ChecklistCard(shown(PanelIds.CHECKLIST)) } }
            AttachmentPanel(PanelIds.CHOICES) { PanelBox(PanelIds.CHOICES) { SignChoices(shown(PanelIds.CHOICES)) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}
