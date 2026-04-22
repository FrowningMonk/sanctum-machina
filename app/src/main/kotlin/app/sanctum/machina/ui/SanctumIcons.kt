package app.sanctum.machina.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val stroke = SolidColor(Color.Black)
private val fill = SolidColor(Color.Black)
private val noFill = SolidColor(Color.Transparent)

private fun icon(name: String, block: ImageVector.Builder.() -> ImageVector.Builder): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 20f,
        viewportHeight = 20f,
    ).block().build()

object SanctumIcons {

    val IconMenu: ImageVector = icon("IconMenu") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(3f, 6f); lineTo(17f, 6f) }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(3f, 10f); lineTo(17f, 10f) }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(3f, 14f); lineTo(17f, 14f) }
    }

    val IconSettings: ImageVector = icon("IconSettings") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 7.5f); arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12.5f, 10f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10f, 12.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.5f, 10f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10f, 7.5f)
            close()
        }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 2f); lineTo(10f, 4f)
            moveTo(10f, 16f); lineTo(10f, 18f)
            moveTo(18f, 10f); lineTo(16f, 10f)
            moveTo(4f, 10f); lineTo(2f, 10f)
            moveTo(15.6f, 4.4f); lineTo(14.2f, 5.8f)
            moveTo(5.8f, 14.2f); lineTo(4.4f, 15.6f)
            moveTo(15.6f, 15.6f); lineTo(14.2f, 14.2f)
            moveTo(5.8f, 5.8f); lineTo(4.4f, 4.4f)
        }
    }

    val IconPlus: ImageVector = icon("IconPlus") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 4f); lineTo(10f, 16f)
            moveTo(4f, 10f); lineTo(16f, 10f)
        }
    }

    val IconBolt: ImageVector = icon("IconBolt") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(11f, 2f)
            lineTo(4f, 11f)
            lineTo(9f, 11f)
            lineTo(8f, 18f)
            lineTo(15f, 9f)
            lineTo(10f, 9f)
            close()
        }
    }

    val IconGhost: ImageVector = icon("IconGhost") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(4f, 9f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 16f, 9f)
            lineTo(16f, 16f)
            lineTo(14f, 14.5f)
            lineTo(12f, 16f)
            lineTo(10f, 14.5f)
            lineTo(8f, 16f)
            lineTo(6f, 14.5f)
            lineTo(4f, 16f)
            close()
        }
        path(fill = fill) {
            moveTo(7.2f, 9f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 9f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.2f, 9f)
            close()
        }
        path(fill = fill) {
            moveTo(11.2f, 9f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12.8f, 9f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11.2f, 9f)
            close()
        }
    }

    val IconEyeOff: ImageVector = icon("IconEyeOff") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(2f, 10f)
            curveTo(2f, 10f, 5f, 4f, 10f, 4f)
            curveTo(11.7f, 4f, 13.2f, 4.7f, 14.4f, 5.6f)
        }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(18f, 10f)
            curveTo(18f, 10f, 15f, 16f, 10f, 16f)
            curveTo(8.3f, 16f, 6.8f, 15.3f, 5.6f, 14.4f)
        }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(3f, 3f); lineTo(17f, 17f) }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 7.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12.5f, 10f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10f, 12.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.5f, 10f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10f, 7.5f)
        }
    }

    val IconSend: ImageVector = icon("IconSend") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(3f, 10f)
            lineTo(17f, 4f)
            lineTo(11f, 18f)
            lineTo(9f, 12f)
            close()
        }
    }

    val IconSend2: ImageVector = icon("IconSend2") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(3f, 17f); lineTo(17f, 10f); lineTo(3f, 3f); lineTo(5f, 10f); close()
            moveTo(5f, 10f); lineTo(13f, 10f)
        }
    }

    val IconMic: ImageVector = icon("IconMic") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(8f, 2f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8f, 2f)
            horizontalLineToRelative(4f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 11f)
            horizontalLineToRelative(-4f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8f, 2f)
        }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(8f, 2f)
            lineTo(8f, 11f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 12f, 11f)
            lineTo(12f, 2f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8f, 2f)
        }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(5f, 9f); curveTo(5f, 14f, 15f, 14f, 15f, 9f) }
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 14f); lineTo(10f, 18f)
            moveTo(7f, 18f); lineTo(13f, 18f)
        }
    }

    val IconAttach: ImageVector = icon("IconAttach") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(13f, 5f)
            lineTo(7f, 11f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 10.5f, 14.5f)
            lineTo(17.5f, 7.5f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 11.8f, 1.8f)
            lineTo(4.8f, 8.8f)
            arcTo(5.5f, 5.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 12.6f, 16.6f)
            lineTo(17.6f, 11.6f)
        }
    }

    val IconStar: ImageVector = icon("IconStar") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 2f)
            lineTo(12.2f, 7.2f)
            lineTo(18f, 7.7f)
            lineTo(13.6f, 11.5f)
            lineTo(15f, 17f)
            lineTo(10f, 14f)
            lineTo(5f, 17f)
            lineTo(6.4f, 11.5f)
            lineTo(2f, 7.7f)
            lineTo(7.8f, 7.2f)
            close()
        }
    }

    val IconStarFill: ImageVector = icon("IconStarFill") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = fill,
        ) {
            moveTo(10f, 2f)
            lineTo(12.2f, 7.2f)
            lineTo(18f, 7.7f)
            lineTo(13.6f, 11.5f)
            lineTo(15f, 17f)
            lineTo(10f, 14f)
            lineTo(5f, 17f)
            lineTo(6.4f, 11.5f)
            lineTo(2f, 7.7f)
            lineTo(7.8f, 7.2f)
            close()
        }
    }

    val IconMore: ImageVector = icon("IconMore") {
        path(fill = fill) {
            moveTo(2.8f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.2f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, 10f)
            close()
        }
        path(fill = fill) {
            moveTo(8.8f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11.2f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 10f)
            close()
        }
        path(fill = fill) {
            moveTo(14.8f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.2f, 10f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14.8f, 10f)
            close()
        }
    }

    val IconCheck: ImageVector = icon("IconCheck") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(3f, 10f); lineTo(8f, 15f); lineTo(17f, 5f)
        }
    }

    val IconBack: ImageVector = icon("IconBack") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(11f, 4f); lineTo(5f, 10f); lineTo(11f, 16f)
            moveTo(5f, 10f); lineTo(18f, 10f)
        }
    }

    val IconEdit: ImageVector = icon("IconEdit") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(3f, 17f); lineTo(4f, 13f); lineTo(14f, 3f); lineTo(17f, 6f); lineTo(7f, 16f); close()
            moveTo(12f, 5f); lineTo(15f, 8f)
        }
    }

    val IconTrash: ImageVector = icon("IconTrash") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(3f, 6f); lineTo(17f, 6f)
            moveTo(5f, 6f); lineTo(6f, 17f)
            curveTo(6f, 18.1f, 6.9f, 19f, 8f, 19f)
            horizontalLineToRelative(4f)
            curveTo(13.1f, 19f, 14f, 18.1f, 14f, 17f)
            lineTo(15f, 6f)
            moveTo(8f, 6f); lineTo(8f, 4f)
            curveTo(8f, 2.9f, 8.9f, 2f, 10f, 2f)
            curveTo(11.1f, 2f, 12f, 2.9f, 12f, 4f)
            lineTo(12f, 6f)
        }
    }

    val IconWarn: ImageVector = icon("IconWarn") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 2f); lineTo(18f, 17f); lineTo(2f, 17f); close()
            moveTo(10f, 8f); lineTo(10f, 12f)
        }
        path(fill = fill) {
            moveTo(9.4f, 14.5f)
            arcTo(0.6f, 0.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.6f, 14.5f)
            arcTo(0.6f, 0.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.4f, 14.5f)
            close()
        }
    }

    val IconChevronDown: ImageVector = icon("IconChevronDown") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) { moveTo(5f, 8f); lineTo(10f, 13f); lineTo(15f, 8f) }
    }

    val IconDownload: ImageVector = icon("IconDownload") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(6f, 10f); lineTo(10f, 14f); lineTo(14f, 10f)
            moveTo(10f, 4f); lineTo(10f, 14f)
            moveTo(4f, 17f); lineTo(16f, 17f)
        }
    }

    val IconSparkle: ImageVector = icon("IconSparkle") {
        path(
            stroke = stroke, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = noFill,
        ) {
            moveTo(10f, 2f)
            lineTo(11.5f, 7.5f)
            lineTo(17f, 9f)
            lineTo(11.5f, 10.5f)
            lineTo(10f, 16f)
            lineTo(8.5f, 10.5f)
            lineTo(3f, 9f)
            lineTo(8.5f, 7.5f)
            close()
        }
    }
}
