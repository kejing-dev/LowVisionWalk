package io.embodit.lowvision.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/** SpatialUI Vibrant (dark) with the design's interaction and danger roles and the Noto Sans family. */
@Composable
fun LvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors = remember(system) { system.copy(error = LvColors.Danger, interaction = LvColors.Accent) }
    val family = rememberLvFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
