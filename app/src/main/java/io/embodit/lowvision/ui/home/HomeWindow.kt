package io.embodit.lowvision.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.lowvision.HOME_WINDOW_ID
import io.embodit.lowvision.BuildConfig
import io.embodit.lowvision.R
import io.embodit.lowvision.app.LensSource
import io.embodit.lowvision.app.LvState
import io.embodit.lowvision.app.LvStore
import io.embodit.lowvision.app.Pages
import io.embodit.lowvision.domain.Copy
import io.embodit.lowvision.domain.Disease
import io.embodit.lowvision.domain.Lang
import io.embodit.lowvision.domain.Side
import io.embodit.lowvision.domain.VeilMath
import io.embodit.lowvision.domain.Vision
import io.embodit.lowvision.platform.LvLog
import io.embodit.lowvision.ui.theme.FitText
import io.embodit.lowvision.ui.theme.LvColors
import io.embodit.lowvision.ui.theme.LvType
import io.embodit.lowvision.ui.walk.WALK_STAGE_ID
import io.embodit.lowvision.ui.walk.WalkCardPage
import io.embodit.lowvision.ui.walk.WalkMenuPage
import io.embodit.lowvision.ui.window.ExitRow
import io.embodit.lowvision.ui.window.MainWindow
import io.embodit.lowvision.ui.window.MainWindowPage
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The home window (design P1, v0.1.4 profile D8 / D21): the app's one system window (Shared Space, 720 x 600 dp, system
 * glass), built on the factory's main window template. Pages on the system TabBar: 「病种」 (three disease cards; once
 * one is picked, the field diagram, the intro and the severity; the two entries at the bottom), 「滤镜窗」 (the lens
 * controls, P9) and the template's settings (P2, quit last, D26). During the walk the same window carries the walk's
 * cards (safety, task list, result, checklist; the pill's menu as its own page) and steps aside while a task runs
 * ([LvState.windowWanted]); the system window bar under it moves and closes it, nothing here drags. The "lens
 * unavailable" card (S23) is drawn over the page. Text on the glass uses labelPrimary only (v0.1.2: labelSecondary
 * measured 3.3 : 1 on the emulator glass, below 4.5 : 1, profile D13).
 */
@Composable
fun HomeWindow(windowId: String = HOME_WINDOW_ID, windowTag: String? = null) {
    val state by LvStore.state.collectAsStateWithLifecycle()
    val c = Copy(state.lang)
    StageBridge(windowId, windowTag)
    StepAside(windowId, windowTag, state)
    val pages =
        if (state.inStage && state.walk != null) {
            listOf(
                MainWindowPage(Pages.WALK, c.tabWalk, R.drawable.ic_list, showTitle = false) { WalkCardPage(state) },
                MainWindowPage(Pages.MENU, c.more, R.drawable.ic_more, showTitle = false) { WalkMenuPage(state) },
            )
        } else {
            listOf(
                MainWindowPage(Pages.LOOK, c.tabLook, R.drawable.ic_eye, showTitle = false) { LookPage(state, c) },
                MainWindowPage(Pages.LENS, c.lensTitle, R.drawable.ic_lens, showTitle = false) { LensPage(state, c) },
            )
        }
    Box(Modifier.fillMaxSize()) {
        MainWindow(
            pages = pages,
            current = state.page,
            onSelect = { LvStore.pageSelected(it) },
            settingsIcon = R.drawable.ic_settings,
            settingsLabel = c.settings,
            settings = { SettingsRows(state, c) },
            exit = ExitRow(c.quitApp, c.quitAsk, c.quitNo, c.quitYes, asked = state.quitAsked) { if (it) LvStore.quitClicked() else LvStore.quitCancelled() },
            exitColor = LvColors.Danger,
            onExitColor = LvColors.OnDanger,
            onExit = { LvStore.quitConfirmed() },
        )
        state.lens.error?.let { LensErrorCard(c, it, Modifier.align(Alignment.Center)) }
    }
}

/** 「病种」 page (S01 – S04): title row, the three disease cards, the detail of the picked one, the two entries. */
@Composable
private fun LookPage(state: LvState, c: Copy) {
    Column(Modifier.fillMaxSize()) {
        TopBar(state, c)
        Spacer(Modifier.height(14.dp))
        DiseaseCards(state, c)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val v = state.selected
            if (v != null) Detail(v, c)
            else Text(c.pickFirst, style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(12.dp))
        BottomButtons(state, c)
    }
}

/** 「滤镜窗」 page (P9, S20 – S22): the lens controls while it is open, otherwise what it is and the button that opens it. */
@Composable
private fun LensPage(state: LvState, c: Copy) {
    val v = state.selected
    if (state.lens.open && v != null) {
        LensControls(state, v, c)
        return
    }
    Column(Modifier.fillMaxSize()) {
        FitText(c.lensTitle, where = "home/c.lensTitle", style = LvType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (v != null) {
                FieldDiagram(v, Modifier.size(180.dp))
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                FitText(c.lensNote, where = "home/c.lensNote", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                FitText(if (v == null) c.pickFirst else c.lensCameraNote, where = "home/lensPage", style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            }
        }
        Button(
            onClick = { LvStore.lensClicked() },
            enabled = v != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Icon(painter = painterResource(R.drawable.ic_lens), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(Modifier.width(8.dp))
            FitText(c.lens, where = "home/c.lens", maxLines = 1)
        }
    }
}

/** Opens / closes the walk Stage for the store; closes it when the window goes away, unless it stepped aside for the walk. */
@Composable
private fun StageBridge(windowId: String, windowTag: String?) {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(navigator, lifecycleOwner) {
        LvStore.stageHost =
            object : LvStore.StageHost {
                override fun openStage() {
                    scope.launch(Dispatchers.Main.immediate) {
                        val result = runCatching { navigator.openStage(WALK_STAGE_ID, StageStyle.Mixed) }
                        // v0.1.4: the window stays open for the walk, it carries the walk's cards (it steps aside by itself)
                        when (val r = result.getOrNull()) {
                            is OpenStageResult.Allowed -> LvStore.stageResult(true, "Allowed")
                            is OpenStageResult.NotAllowed -> LvStore.stageResult(false, "NotAllowed")
                            is OpenStageResult.Error -> LvStore.stageResult(false, "Error ${r.code} ${r.reason}")
                            null -> LvStore.stageResult(false, "threw ${result.exceptionOrNull()}")
                        }
                    }
                }

                override fun closeStage() {
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() }.onFailure { LvLog.w("STAGE close failed: $it") } }
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && LvStore.hiddenHome == null) {
                    if (LvStore.state.value.inStage) {
                        // closed with the system window bar's × during the walk: the walk goes on, the pill's 「叫回」 brings it back
                        LvStore.hiddenHome = windowId to windowTag
                        LvStore.windowDismissed()
                    } else {
                        scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() } }
                        LvStore.stageClosed()
                        LvStore.lensClosed("home destroyed")
                    }
                }
                // after hiddenHome: the Stage reopens the window from this (WalkScreen)
                LvStore.homeLife.value = event.name
                LvLog.i("WINDOW $windowId/$windowTag ${event.name}")
            }
        LvStore.spotHost =
            object : LvStore.SpotHost {
                override fun openSpot(gen: Int) = navigator.openWindowContainer(LENS_SPOT_ID, "spot$gen")

                override fun closeSpot() {
                    runCatching { navigator.closeWindowContainer(LENS_SPOT_ID) }.onFailure { LvLog.w("LENS spot close: $it") }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LvStore.stageHost = null
            LvStore.spotHost = null
        }
    }
}

/**
 * During the walk the window closes itself while a task runs ([LvState.windowWanted]) and once for the pill's 「叫回」
 * ([LvStore.homeRecall]); the Stage opens it again in front of the head (closing and opening is the only way to bring a
 * system window back in front: native-window-move §3.5). The close waits for the Stage to settle.
 */
@Composable
private fun StepAside(windowId: String, windowTag: String?, state: LvState) {
    val navigator = LocalSpatialNavigator.current
    val recall by LvStore.homeRecall.collectAsStateWithLifecycle()
    val recallSeen = remember { LvStore.homeRecall.value }
    val wanted = state.windowWanted
    LaunchedEffect(wanted, recall) {
        if (wanted && recall == recallSeen) return@LaunchedEffect
        delay(HOME_CLOSE_DELAY_MS)
        val now = LvStore.state.value
        if (!now.inStage || (now.windowWanted && LvStore.homeRecall.value == recallSeen)) return@LaunchedEffect
        LvStore.hiddenHome = windowId to windowTag
        val ok = runCatching { navigator.closeWindowContainer() }
        LvLog.i("WINDOW steps aside (${if (now.windowWanted) "recall" else "phase ${now.walk?.phase}"}): ${ok.exceptionOrNull() ?: "ok"}")
        if (ok.isFailure) LvStore.hiddenHome = null
    }
}

// ---------------------------------------------------------------------------------------------
// top bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopBar(state: LvState, c: Copy) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FitText(c.title, where = "home/c.title", style = LvType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            FitText(c.subtitle, where = "home/c.subtitle", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LabeledIcon(if (state.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (state.muted) c.unmute else c.mute) { LvStore.muteClicked() }
    }
}

/** An icon button with its word under it (B19: plain icons carry text). */
@Composable
private fun LabeledIcon(icon: Int, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, size = ButtonDefaults.Small, modifier = Modifier.height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(6.dp))
        FitText(label, where = "home/label", maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------------------------
// disease cards
// ---------------------------------------------------------------------------------------------

fun iconOf(d: Disease) =
    when (d) {
        Disease.GLAUCOMA -> R.drawable.ic_glaucoma
        Disease.AMD -> R.drawable.ic_amd
        Disease.HEMIANOPIA -> R.drawable.ic_hemianopia
    }

fun colorOf(d: Disease) =
    when (d) {
        Disease.GLAUCOMA -> LvColors.Glaucoma
        Disease.AMD -> LvColors.Amd
        Disease.HEMIANOPIA -> LvColors.Hemianopia
    }

@Composable
private fun DiseaseCards(state: LvState, c: Copy) {
    val enabled = !state.inStage
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Disease.entries.forEach { d ->
            val selected = state.selected?.disease == d
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(20.dp)
            Column(
                modifier =
                    Modifier.weight(1f)
                        .height(if (state.selected == null) 176.dp else 112.dp)
                        .alpha(if (enabled || selected) 1f else 0.4f)
                        .then(if (selected) Modifier.border(3.dp, PicoTheme.colorScheme.interaction, shape) else Modifier)
                        .background(PicoTheme.colorScheme.fillTertiary, shape)
                        .clip(shape)
                        .then(if (enabled) Modifier.spatialHoverEffect() else Modifier)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled) { LvStore.diseaseClicked(d) }
                        .controllerHapticFeedback(interactionSource = interaction)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(iconOf(d)), contentDescription = null, modifier = Modifier.size(32.dp), tint = colorOf(d))
                    Spacer(Modifier.width(10.dp))
                    FitText(c.name(d), where = "home/c.name", style = LvType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
                FitText(c.line(d), where = "home/c.line", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = if (state.selected == null) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// detail (S02 – S04)
// ---------------------------------------------------------------------------------------------

@Composable
private fun Detail(v: Vision, c: Copy) {
    Row(Modifier.fillMaxSize()) {
        FieldDiagram(v, Modifier.size(220.dp).align(Alignment.CenterVertically))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            FitText(c.intro(v.disease), where = "home/c.intro", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            SeverityControl(v, c)
            Spacer(Modifier.height(6.dp))
            FitText(if (v.disease == Disease.AMD) c.turnHead else c.notBlack, where = "home/if", style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
        }
    }
}

@Composable
private fun SeverityControl(v: Vision, c: Copy) {
    if (v.disease == Disease.HEMIANOPIA) {
        Text(c.sideLabel, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(6.dp))
        SideSegment(v.side, c) { LvStore.sideChanged(it) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.levelLabel, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
        FitText(c.degrees(v), where = "home/c.degrees", style = LvType.cardName, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
    }
    Slider(
        value = v.level.toFloat(),
        onValueChange = { LvStore.levelChanged(it.roundToInt()) },
        modifier = Modifier.fillMaxWidth(),
        valueRange = 1f..5f,
        onValueChangeFinished = {},
        colors = SliderDefaults.sliderColors(trackColor = PicoTheme.colorScheme.fillPrimary, progressColor = PicoTheme.colorScheme.interaction, progressHighColor = PicoTheme.colorScheme.interaction),
    )
    Row(Modifier.fillMaxWidth()) {
        (1..5).forEach { i ->
            Text(
                text = if (i == v.level) "●" else "○",
                style = LvType.small,
                color = if (i == v.level) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun SideSegment(side: Side, c: Copy, onSide: (Side) -> Unit) {
    SegmentControl(modifier = Modifier.width(224.dp).height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        listOf(Side.LEFT to c.left, Side.RIGHT to c.right).forEach { (s, label) ->
            SegmentItem(
                selected = side == s,
                onClick = { onSide(s) },
                modifier = Modifier.width(108.dp).height(48.dp),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
            )
        }
    }
}

/**
 * The field diagram (design P1): a round field out to 60° from the centre, the seen part light, the
 * veil of the current disease and level drawn with the same math as the Stage veil. Compose
 * foundation Canvas, no Material.
 */
@Composable
fun FieldDiagram(v: Vision, modifier: Modifier) {
    val bitmap = remember(v) { fieldBitmap(v, 220) }
    val ring = colorOf(v.disease)
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(LvColors.FieldSeen, r, center)
        drawImage(bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        drawCircle(ring, r - 2f, center, style = Stroke(width = 4f))
        drawLine(ring.copy(alpha = 0.6f), Offset(center.x - 8f, center.y), Offset(center.x + 8f, center.y), strokeWidth = 2f)
        drawLine(ring.copy(alpha = 0.6f), Offset(center.x, center.y - 8f), Offset(center.x, center.y + 8f), strokeWidth = 2f)
    }
}

private const val FIELD_DEG = 60f
private const val HOME_CLOSE_DELAY_MS = 1_500L

private fun fieldBitmap(v: Vision, px: Int): Bitmap {
    val p = v.params
    val out = IntArray(px * px)
    val half = px / 2f
    for (row in 0 until px) {
        for (col in 0 until px) {
            val dx = (col + 0.5f - half) / half
            val dy = (half - row - 0.5f) / half
            val rr = kotlin.math.hypot(dx, dy)
            if (rr > 1f) continue
            out[row * px + col] = VeilMath.argb(p, rr * FIELD_DEG, dx * FIELD_DEG)
        }
    }
    return Bitmap.createBitmap(out, px, px, Bitmap.Config.ARGB_8888)
}

@Composable
private fun BottomButtons(state: LvState, c: Copy) {
    val enabled = state.selected != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { LvStore.walkClicked() },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LvColors.Accent, contentColor = LvColors.OnAccent),
        ) {
            Icon(painter = painterResource(R.drawable.ic_eye), contentDescription = null, modifier = Modifier.size(22.dp), tint = LvColors.OnAccent)
            Spacer(Modifier.width(8.dp))
            FitText(c.walk, where = "home/c.walk", maxLines = 1, color = LvColors.OnAccent)
        }
        Button(
            onClick = { LvStore.lensClicked() },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(c.lens, where = "home/c.lens", maxLines = 1)
                FitText(c.lensCameraNote, where = "home/c.lensCameraNote", style = LvType.small, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// lens controls (P9 / S20 – S22)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LensControls(state: LvState, v: Vision, c: Copy) {
    var now by remember { mutableLongStateOf(LvStore.now()) }
    LaunchedEffect(state.lens.openedAtMs) {
        while (true) {
            now = LvStore.now()
            delay(1_000)
        }
    }
    Row(Modifier.fillMaxSize()) {
        FieldDiagram(v, Modifier.size(160.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(c.lensSource, style = LvType.small, color = PicoTheme.colorScheme.labelPrimary)
            Spacer(Modifier.height(6.dp))
            SegmentControl(modifier = Modifier.fillMaxWidth().height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                listOf(LensSource.CAMERA to c.sourceCamera, LensSource.KITCHEN to c.photoKitchen, LensSource.PILLS to c.photoPills, LensSource.STAIRS to c.photoStairs).forEach { (s, label) ->
                    SegmentItem(
                        selected = state.lens.source == s,
                        onClick = { LvStore.lensSource(s) },
                        modifier = Modifier.width(104.dp).height(48.dp),
                        textStyle = PicoTheme.typography.labelLarge,
                        colors =
                            SegmentControlDefaults.colors(
                                itemBackgroundColor = Color.Transparent,
                                itemContentColor = PicoTheme.colorScheme.labelPrimary,
                                selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                                selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                            ),
                        title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SeverityControl(v, c)
            Spacer(Modifier.height(8.dp))
            if (state.lens.source == LensSource.CAMERA) {
                val line = if (state.isEmulator) c.lensNoCamera else if (now - state.lens.openedAtMs > 5_000) c.lensBlackHint else null
                if (line != null) FitText(line, where = "home/line", style = LvType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            }
            FitText(c.lensNote, where = "home/c.lensNote", style = LvType.small, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldButton(c.compare, Modifier.weight(1f)) { LvStore.lensCompare(it) }
                Button(onClick = { LvStore.lensCloseClicked() }, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
                    FitText(c.closeLens, where = "home/c.closeLens", maxLines = 1)
                }
            }
        }
    }
}

/** A press-and-hold button: [onHold] true on press, false on release or cancel (T14 / P7 peek). */
@Composable
fun HoldButton(label: String, modifier: Modifier, container: Color = PicoTheme.colorScheme.fillSecondary, content: Color = PicoTheme.colorScheme.labelPrimary, onHold: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier =
            modifier.height(52.dp)
                .clip(shape)
                .background(container, shape)
                .spatialHoverEffect()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onHold(true)
                        waitForUpOrCancellation()
                        onHold(false)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        FitText(label, where = "home/label", style = PicoTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// overlays: settings (P2 / S05), lens unavailable (S23)
// ---------------------------------------------------------------------------------------------

/** The app's rows of the template's settings page (P2 / S05); the template adds 「退出应用」 last (D26). */
@Composable
private fun SettingsRows(state: LvState, c: Copy) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.language, style = LvType.body, color = ink, modifier = Modifier.weight(1f))
        SegmentControl(modifier = Modifier.width(224.dp).height(52.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
            listOf(Lang.ZH to "中文", Lang.EN to "English").forEach { (l, label) ->
                SegmentItem(
                    selected = state.lang == l,
                    onClick = { LvStore.langSelected(l) },
                    modifier = Modifier.width(108.dp).height(48.dp),
                    textStyle = PicoTheme.typography.labelLarge,
                    colors =
                        SegmentControlDefaults.colors(
                            itemBackgroundColor = Color.Transparent,
                            itemContentColor = ink,
                            selectedItemBackgroundColor = LvColors.Accent,
                            selectedItemContentColor = LvColors.OnAccent,
                        ),
                    title = { FitText(text = label, where = "home/label", maxLines = 1, softWrap = false) },
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(52.dp).clickable { LvStore.muteClicked() }, verticalAlignment = Alignment.CenterVertically) {
        Text(c.mute, style = LvType.body, color = ink, modifier = Modifier.weight(1f))
        Switch(checked = state.muted, onCheckedChange = { LvStore.muteClicked() })
    }
    Row(Modifier.fillMaxWidth().height(52.dp).clickable { LvStore.showSafetyChanged(!state.showSafety) }, verticalAlignment = Alignment.CenterVertically) {
        Text(c.safetyAgain, style = LvType.body, color = ink, modifier = Modifier.weight(1f))
        Switch(checked = state.showSafety, onCheckedChange = { LvStore.showSafetyChanged(it) })
    }
    Column {
        Text(c.about, style = LvType.cardName, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(c.disclaimer, style = LvType.body, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(c.honesty, style = LvType.small, color = ink)
        Spacer(Modifier.height(8.dp))
        Text(c.references, style = LvType.small, color = ink)
        c.referenceLines.forEach { Text("· $it", style = LvType.small, color = ink) }
        Spacer(Modifier.height(6.dp))
        Text("${c.version} ${BuildConfig.VERSION_NAME}", style = LvType.small, color = ink)
    }
}

/** A card drawn over the page inside the window (S23); opaque so it reads over any page. */
@Composable
private fun OverlayCard(modifier: Modifier, width: Int, content: @Composable () -> Unit) {
    Box(
        modifier
            .width(width.dp)
            .background(LvColors.PlateInk, RoundedCornerShape(24.dp))
            .border(1.dp, LvColors.PlateRaised, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
private fun LensErrorCard(c: Copy, reason: String, modifier: Modifier) {
    OverlayCard(modifier, 440) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(LvColors.Danger, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(c.lensUnavailable, style = LvType.cardName, color = LvColors.OnPlate)
            }
            Spacer(Modifier.height(8.dp))
            FitText(reason, where = "home/reason", style = LvType.small, color = LvColors.OnPlateDim, maxLines = 3)
            Spacer(Modifier.height(14.dp))
            Button(onClick = { LvStore.lensErrorOk() }, modifier = Modifier.height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = LvColors.Accent, contentColor = LvColors.OnAccent)) { Text(c.ok, color = LvColors.OnAccent) }
        }
    }
}
