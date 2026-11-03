package io.embodit.lowvision.ui.window

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.lowvision.platform.LvLog

/*
 * 主窗口模板（taste D8「合成一个窗口模板」+ D21 拖动交给系统 + D26 设置里退出应用），
 * 拷自产线 prompts/snippets/MainWindowTemplate.kt（2026-11-02 原型站）。本应用只改了三处，结构没动：
 *  - 页签受控：current / onSelect 由 LvStore 管（调试广播和 Stage 里的药丸也要能切页，走同一套状态）；
 *  - 文案按语言传进来（中英双语，D26）；退出的二次确认状态也由 LvStore 管（调试广播 `quit` 走同一条路）；
 *  - 页面可以不要模板画的页标题（showTitle = false），由页面自己画标题行。
 * 规矩：
 *  - 应用全部 2D 内容都在这一个系统窗口里；页面用系统 TabBar 切（TabBar 是系统挂在窗口外的独立小窗，模拟器上从正面看在窗口上方，不叠在窗口前后）。
 *  - 切页只用 SpatialUI 原生 TabBar（com.pico.spatial.ui.design.windows.TabBar，用户 2026-11-02「用原生 tab bar 组件」）；
 *    不要用 SegmentControl / 一排 Button / 自画 Tab 切页面，SegmentControl 只用来切同一页里的选项。
 *  - 「设置」固定是最后一页，最底下固定「退出应用」，在同一窗口里二次确认（不弹第二块面板）。
 *  - 页面自己的快捷操作放 page.toolbar：系统 Toolbar 出现在窗口底边下方。
 *  - 真要第二块内容，用 SideAugment（挂在窗口右侧，间距 56 dp）或系统多窗口 + Placement；不要在 Stage 里再挂一块面板。
 *  - 拖动 / 关闭 / 最小化是系统窗口控件（窗口下方的小圆点），这里不写任何拖动代码。
 */

/** One page of the main window. [icon]: a Material Symbols vector (taste D23); [toolbar]: this page's quick actions. */
class MainWindowPage(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val showTitle: Boolean = true,
    val toolbar: (@Composable RowScope.() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/** The quit row's words and its confirm state (profile D26), owned by the app's store. */
class ExitRow(val quitApp: String, val quitAsk: String, val no: String, val yes: String, val asked: Boolean, val onAsk: (Boolean) -> Unit)

/** Id of the template's settings page (always the last TabBar item). */
const val SETTINGS_PAGE = "settings"

/**
 * The app's single main window. [pages] are the app's own pages (at most 6: the TabBar holds 7 and the last one is
 * Settings). [settings] are the app's settings rows (language, sound, about); the exit row is added by the template.
 * [onExit] runs the app's exit (release, close the Stage, remove every task, end the process).
 */
@Composable
fun MainWindow(
    pages: List<MainWindowPage>,
    current: String,
    onSelect: (String) -> Unit,
    @DrawableRes settingsIcon: Int,
    settingsLabel: String,
    settings: @Composable ColumnScope.() -> Unit,
    exit: ExitRow,
    exitColor: Color,
    onExitColor: Color,
    onExit: () -> Unit,
) {
    require(pages.size in 1..6) { "TabBar holds at most 7 items: 6 pages + Settings" }
    val page = if (current == SETTINGS_PAGE) null else pages.firstOrNull { it.id == current } ?: pages.first()

    PicoTheme {
        Box(Modifier.fillMaxSize()) {
            if (page != null) {
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
                    if (page.showTitle) {
                        Text(page.label, fontSize = 28.sp, color = PicoTheme.colorScheme.labelPrimary)
                        Spacer(Modifier.height(16.dp))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) { page.content() }
                }
            } else {
                SettingsPage(settingsLabel, rows = settings, exit = exit, exitColor = exitColor, onExitColor = onExitColor, onExit = onExit)
            }
        }

        // system TabBar: its own small window outside this one (emulator 6.1.0, seen from the front: centred above the window)
        TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
            pages.forEach { p ->
                item(
                    selected = page?.id == p.id,
                    onClick = {
                        LvLog.i("WINDOW tab ${p.id}")
                        onSelect(p.id)
                    },
                    mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                    supportContent = { Text(p.label) },
                )
            }
            item(
                selected = page == null,
                onClick = {
                    LvLog.i("WINDOW tab $SETTINGS_PAGE")
                    onSelect(SETTINGS_PAGE)
                },
                mainContent = { Icon(painter = painterResource(settingsIcon), contentDescription = settingsLabel) },
                supportContent = { Text(settingsLabel) },
            )
        }

        // system Toolbar under the window, only for pages that have quick actions
        page?.toolbar?.let { actions -> Toolbar { actions() } }
    }
}

@Composable
private fun SettingsPage(title: String, rows: @Composable ColumnScope.() -> Unit, exit: ExitRow, exitColor: Color, onExitColor: Color, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text(title, fontSize = 28.sp, color = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(16.dp))
        // the long part scrolls; the exit row stays at the bottom of the page (D26)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { rows() }
        Spacer(Modifier.height(16.dp))
        // D26: the exit row is always last; the confirmation stays inside this window (no second panel)
        if (!exit.asked) {
            Button(onClick = { exit.onAsk(true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(exit.quitApp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(exit.quitAsk, fontSize = 22.sp, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                // default colours for 「取消」: a tertiary fill disappears on the window glass
                Button(onClick = { exit.onAsk(false) }, modifier = Modifier.size(width = 120.dp, height = 52.dp)) { Text(exit.no) }
                Button(onClick = onExit, modifier = Modifier.size(width = 120.dp, height = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = exitColor, contentColor = onExitColor)) {
                    Text(exit.yes, color = onExitColor)
                }
            }
        }
    }
}

/**
 * A second block of content beside the main window (D8: only Augment or a system window, never a panel in front of
 * or behind the window). Anchored to the window's right edge, top aligned, 56 dp gap (spatial-design_foundation_window).
 * Call it from inside MainWindow's page content.
 */
@Composable
fun SideAugment(width: Int = 360, content: @Composable () -> Unit) {
    Augment(
        anchor = NormalizedPoint3D.TopRight,
        alignment = AugmentContentAlignment.TopLeft,
        offset = DpOffset3D(56.dp, 0.dp, 0.dp),
    ) {
        PicoTheme { Box(Modifier.size(width.dp, 320.dp).padding(16.dp)) { content() } }
    }
}
