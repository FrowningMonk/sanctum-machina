package app.sanctum.machina.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sanctum.machina.ui.theme.LocalSanctumColors

// Sigil: diamond (10 2 18 10 10 18 2 10) + center circle r=2.2 + top/bottom spokes
// viewport 20×20, strokeWidth 1.2dp
@Composable
fun SmSigil(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = LocalSanctumColors.current.accent,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.width / 20f
        val sw = 1.2f * scale
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val diamond = Path().apply {
            moveTo(10f * scale, 2f * scale)
            lineTo(18f * scale, 10f * scale)
            lineTo(10f * scale, 18f * scale)
            lineTo(2f * scale, 10f * scale)
            close()
        }
        drawPath(diamond, color = color, style = stroke)

        drawCircle(
            color = color,
            radius = 2.2f * scale,
            center = Offset(10f * scale, 10f * scale),
            style = stroke,
        )

        // top spoke: (10,2) → (10,7.8)
        drawLine(
            color = color,
            start = Offset(10f * scale, 2f * scale),
            end = Offset(10f * scale, 7.8f * scale),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )

        // bottom spoke: (10,12.2) → (10,18)
        drawLine(
            color = color,
            start = Offset(10f * scale, 12.2f * scale),
            end = Offset(10f * scale, 18f * scale),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}
