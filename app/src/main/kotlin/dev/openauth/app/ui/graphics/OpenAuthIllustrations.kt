package dev.openauth.app.ui.graphics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.min

enum class IllustrationKind { WELCOME, EMPTY_VAULT, BACKUP, RESTORE, LOCKED }

private data class IllustrationColors(
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val paper: Color,
    val ink: Color,
    val outline: Color,
    val warm: Color,
    val warmContainer: Color,
)

/** Декоративный рисунок: описание и семантику задаёт вызывающий экран. */
@Composable
fun OpenAuthIllustration(kind: IllustrationKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = IllustrationColors(
        accent = scheme.primary,
        accentContainer = scheme.primaryContainer,
        onAccent = scheme.onPrimary,
        paper = scheme.surfaceContainerLowest,
        ink = scheme.onSurface,
        outline = scheme.outlineVariant,
        warm = scheme.tertiary,
        warmContainer = scheme.tertiaryContainer,
    )
    Canvas(modifier = modifier.sizeIn(maxWidth = 360.dp).aspectRatio(4f / 3f)) {
        // Единая система координат сохраняет пропорции при любых ограничениях UI.
        val scaleFactor = min(size.width / 320f, size.height / 240f)
        withTransform({
            translate((size.width - 320f * scaleFactor) / 2f, (size.height - 240f * scaleFactor) / 2f)
            scale(scaleFactor, scaleFactor, pivot = Offset.Zero)
        }) {
            drawCircle(colors.accentContainer.copy(alpha = 0.5f), 84f, Offset(160f, 118f))
            drawCircle(colors.warmContainer.copy(alpha = 0.75f), 21f, Offset(246f, 66f))
            drawCircle(colors.accent.copy(alpha = 0.14f), 7f, Offset(66f, 166f))
            drawLine(colors.outline, Offset(58f, 79f), Offset(70f, 79f), 3f, StrokeCap.Round)
            drawLine(colors.outline, Offset(64f, 73f), Offset(64f, 85f), 3f, StrokeCap.Round)
            when (kind) {
                IllustrationKind.WELCOME -> welcome(colors)
                IllustrationKind.EMPTY_VAULT -> emptyVault(colors)
                IllustrationKind.BACKUP -> backup(colors)
                IllustrationKind.RESTORE -> restore(colors)
                IllustrationKind.LOCKED -> locked(colors)
            }
        }
    }
}

private fun DrawScope.welcome(colors: IllustrationColors) {
    drawCircle(colors.paper, 69f, Offset(158f, 117f))
    drawCircle(colors.outline.copy(alpha = 0.45f), 69f, Offset(158f, 117f), style = Stroke(1.5f))
    drawArc(
        color = colors.accent,
        startAngle = 43f,
        sweepAngle = 286f,
        useCenter = false,
        topLeft = Offset(110f, 69f),
        size = Size(96f, 96f),
        style = Stroke(14f, cap = StrokeCap.Round),
    )
    drawLine(colors.accent, Offset(177f, 136f), Offset(215f, 174f), 14f, StrokeCap.Round)
    drawLine(colors.accent, Offset(198f, 157f), Offset(209f, 146f), 10f, StrokeCap.Round)
    drawLine(colors.accent, Offset(210f, 169f), Offset(221f, 158f), 10f, StrokeCap.Round)
    drawCircle(colors.warmContainer, 18f, Offset(98f, 169f))
    checkmark(Offset(98f, 169f), colors.warm, 0.8f)
}

private fun DrawScope.emptyVault(colors: IllustrationColors) {
    rotate(-9f, Offset(158f, 120f)) {
        card(Offset(83f, 54f), Size(154f, 124f), colors.accentContainer, colors.outline)
    }
    rotate(7f, Offset(160f, 125f)) {
        card(Offset(88f, 65f), Size(154f, 124f), colors.warmContainer, colors.outline)
    }
    codeCard(Offset(83f, 75f), Size(154f, 120f), colors)
    drawCircle(colors.accent, 24f, Offset(228f, 169f))
    drawLine(colors.onAccent, Offset(218f, 169f), Offset(238f, 169f), 3f, StrokeCap.Round)
    drawLine(colors.onAccent, Offset(228f, 159f), Offset(228f, 179f), 3f, StrokeCap.Round)
}

private fun DrawScope.backup(colors: IllustrationColors) {
    document(colors)
    cloud(Offset(192f, 109f), colors.accentContainer, colors.accent)
    drawLine(colors.accent, Offset(192f, 143f), Offset(192f, 109f), 5f, StrokeCap.Round)
    polyline(colors.accent, 5f, Offset(180f, 121f), Offset(192f, 109f), Offset(204f, 121f))
    drawCircle(colors.warmContainer, 19f, Offset(103f, 175f))
    checkmark(Offset(103f, 175f), colors.warm, 0.85f)
}

private fun DrawScope.restore(colors: IllustrationColors) {
    drawArc(colors.accent, 205f, 152f, false, Offset(76f, 32f), Size(168f, 168f), style = Stroke(5f, cap = StrokeCap.Round))
    polyline(colors.accent, 5f, Offset(231f, 101f), Offset(244f, 116f), Offset(252f, 97f))
    drawArc(colors.warm, 25f, 152f, false, Offset(76f, 40f), Size(168f, 168f), style = Stroke(5f, cap = StrokeCap.Round))
    polyline(colors.warm, 5f, Offset(89f, 139f), Offset(76f, 124f), Offset(68f, 143f))
    codeCard(Offset(102f, 79f), Size(116f, 92f), colors)
    drawCircle(colors.accent, 20f, Offset(206f, 163f))
    checkmark(Offset(206f, 163f), colors.onAccent, 0.9f)
}

private fun DrawScope.locked(colors: IllustrationColors) {
    drawArc(
        colors.accent, 180f, 180f, false,
        Offset(123f, 49f), Size(74f, 90f), style = Stroke(12f, cap = StrokeCap.Round),
    )
    drawLine(colors.accent, Offset(123f, 94f), Offset(123f, 117f), 12f, StrokeCap.Round)
    drawLine(colors.accent, Offset(197f, 94f), Offset(197f, 117f), 12f, StrokeCap.Round)
    card(Offset(104f, 104f), Size(112f, 91f), colors.paper, colors.outline)
    drawRoundRect(colors.accentContainer, Offset(113f, 113f), Size(94f, 73f), CornerRadius(17f))
    drawCircle(colors.accent, 9f, Offset(160f, 144f))
    drawLine(colors.accent, Offset(160f, 149f), Offset(160f, 165f), 7f, StrokeCap.Round)
    drawCircle(colors.warmContainer, 17f, Offset(224f, 177f))
    checkmark(Offset(224f, 177f), colors.warm, 0.7f)
}

private fun DrawScope.document(colors: IllustrationColors) {
    val file = Path().apply {
        moveTo(104f, 47f)
        lineTo(174f, 47f)
        lineTo(207f, 80f)
        lineTo(207f, 180f)
        quadraticTo(207f, 191f, 196f, 191f)
        lineTo(104f, 191f)
        quadraticTo(93f, 191f, 93f, 180f)
        lineTo(93f, 58f)
        quadraticTo(93f, 47f, 104f, 47f)
        close()
    }
    drawPath(file, colors.paper)
    drawPath(file, colors.outline, style = Stroke(1.5f, join = StrokeJoin.Round))
    val fold = Path().apply {
        moveTo(174f, 47f)
        lineTo(174f, 69f)
        quadraticTo(174f, 80f, 185f, 80f)
        lineTo(207f, 80f)
        close()
    }
    drawPath(fold, colors.accentContainer)
    drawLine(colors.accent, Offset(113f, 105f), Offset(151f, 105f), 5f, StrokeCap.Round)
    drawLine(colors.outline, Offset(113f, 121f), Offset(151f, 121f), 4f, StrokeCap.Round)
    drawLine(colors.outline, Offset(113f, 137f), Offset(138f, 137f), 4f, StrokeCap.Round)
}

private fun DrawScope.cloud(center: Offset, fill: Color, ink: Color) {
    val shape = Path().apply {
        moveTo(center.x - 33f, center.y + 20f)
        cubicTo(center.x - 55f, center.y + 20f, center.x - 55f, center.y - 13f, center.x - 33f, center.y - 14f)
        cubicTo(center.x - 29f, center.y - 43f, center.x + 10f, center.y - 45f, center.x + 20f, center.y - 17f)
        cubicTo(center.x + 47f, center.y - 19f, center.x + 51f, center.y + 20f, center.x + 25f, center.y + 20f)
        close()
    }
    drawPath(shape, fill)
    drawPath(shape, ink, style = Stroke(2f, join = StrokeJoin.Round))
}

private fun DrawScope.codeCard(origin: Offset, dimensions: Size, colors: IllustrationColors) {
    card(origin, dimensions, colors.paper, colors.outline)
    val unit = dimensions.width / 154f
    drawCircle(colors.accentContainer, 12f * unit, origin + Offset(28f * unit, 29f * unit))
    drawLine(colors.accent, origin + Offset(52f * unit, 24f * unit), origin + Offset(118f * unit, 24f * unit), 5f * unit, StrokeCap.Round)
    drawLine(colors.outline, origin + Offset(52f * unit, 35f * unit), origin + Offset(92f * unit, 35f * unit), 3f * unit, StrokeCap.Round)
    repeat(6) { index ->
        drawRoundRect(colors.ink.copy(alpha = 0.8f), origin + Offset((22f + index * 19f) * unit, 61f * unit), Size(10f * unit, 18f * unit), CornerRadius(3f * unit))
    }
    drawRoundRect(colors.accentContainer, origin + Offset(22f * unit, 94f * unit), Size(110f * unit, 4f * unit), CornerRadius(2f * unit))
    drawRoundRect(colors.accent, origin + Offset(22f * unit, 94f * unit), Size(73f * unit, 4f * unit), CornerRadius(2f * unit))
}

private fun DrawScope.card(origin: Offset, dimensions: Size, fill: Color, outline: Color) {
    drawRoundRect(outline.copy(alpha = 0.18f), origin + Offset(0f, 5f), dimensions, CornerRadius(18f))
    drawRoundRect(fill, origin, dimensions, CornerRadius(18f))
    drawRoundRect(outline, origin, dimensions, CornerRadius(18f), style = Stroke(1.5f))
}

private fun DrawScope.checkmark(center: Offset, color: Color, scale: Float) {
    polyline(color, 3.5f * scale, center + Offset(-8f, 0f) * scale, center + Offset(-2f, 6f) * scale, center + Offset(9f, -7f) * scale)
}

private fun DrawScope.polyline(color: Color, width: Float, start: Offset, middle: Offset, end: Offset) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(middle.x, middle.y)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
