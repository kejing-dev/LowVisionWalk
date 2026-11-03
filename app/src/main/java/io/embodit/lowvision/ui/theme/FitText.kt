package io.embodit.lowvision.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.pico.spatial.ui.design.LocalTextStyle
import com.pico.spatial.ui.design.Text
import io.embodit.lowvision.platform.LvLog

/**
 * SpatialUI [Text] for every text with a fixed number of lines (profile D13 / D16, v0.1.3): when the laid-out text does
 * not fit (cut or ellipsised) it logs `OVERFLOW <where> lines=<n> "<text>"` once per text, so a tour of all states in
 * both languages shows every truncation (QA #2 q2-4). [where] names the panel / control.
 */
@Composable
fun FitText(
    text: String,
    where: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
        textAlign = textAlign,
        onTextLayout = { r ->
            if (r.hasVisualOverflow || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1))) {
                val key = "$where|$text"
                if (reported.add(key)) LvLog.w("OVERFLOW $where lines=${r.lineCount} \"$text\"")
            }
        },
    )
}

private val reported = java.util.Collections.synchronizedSet(HashSet<String>())
