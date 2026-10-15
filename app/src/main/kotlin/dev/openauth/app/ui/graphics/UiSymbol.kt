package dev.openauth.app.ui.graphics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor

enum class Symbol { CODES, BACKUP, SETTINGS, ADD, SCAN, LOCK, SEARCH, STAR, EDIT, COPY, CLOSE, BACK, SORT }

/** Небольшой локальный набор пиктограмм; доступную подпись задаёт кнопка. */
@Composable
fun UiSymbol(symbol: Symbol, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, filled: Boolean = false) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, 1.8f * unit, StrokeCap.Round)
        fun rect(x: Float, y: Float, width: Float, height: Float) = drawRoundRect(tint, point(x, y), Size(width * unit, height * unit), CornerRadius(2f * unit), style = Stroke(1.8f * unit))
        when (symbol) {
            Symbol.ADD -> { line(point(5f,12f),point(19f,12f)); line(point(12f,5f),point(12f,19f)) }
            Symbol.CLOSE -> { line(point(6f,6f),point(18f,18f)); line(point(6f,18f),point(18f,6f)) }
            Symbol.BACK -> { line(point(5f,12f),point(20f,12f)); line(point(5f,12f),point(12f,5f));line(point(5f,12f),point(12f,19f)) }
            Symbol.SEARCH -> { drawCircle(tint, 6.5f * unit, point(10f,10f), style = Stroke(1.8f * unit));line(point(15f,15f),point(21f,21f)) }
            Symbol.STAR -> {
                val path = Path()
                repeat(10) { i -> val angle = Math.PI * (i / 5.0 - 0.5); val radius = if (i % 2 == 0) 10.0 else 4.5
                    val p = point((12 + kotlin.math.cos(angle) * radius).toFloat(), (12 + kotlin.math.sin(angle) * radius).toFloat())
                    if (i == 0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) }
                path.close(); if (filled) drawPath(path,tint) else drawPath(path,tint,style=Stroke(1.7f*unit))
            }
            Symbol.COPY -> { rect(8f,7f,12f,14f);line(point(15f,3f),point(4f,3f));line(point(4f,3f),point(4f,16f)) }
            Symbol.EDIT -> { line(point(5f,17f),point(17f,5f));line(point(8f,20f),point(20f,8f));line(point(17f,5f),point(20f,8f));line(point(5f,17f),point(4f,21f));line(point(4f,21f),point(8f,20f)) }
            Symbol.LOCK -> { rect(5f,10f,14f,11f); drawArc(tint,180f,180f,false,point(8f,2f),Size(8f*unit,15f*unit), style=Stroke(1.8f*unit));line(point(12f,14f),point(12f,17f)) }
            Symbol.CODES -> { rect(3f,4f,18f,16f); repeat(3) { x -> repeat(2) { y -> drawCircle(tint,1.2f*unit,point(7f+x*5f,9f+y*6f)) } } }
            Symbol.BACKUP -> { rect(4f,5f,16f,15f);line(point(8f,2f),point(16f,2f));line(point(12f,16f),point(12f,9f));line(point(12f,9f),point(8f,13f));line(point(12f,9f),point(16f,13f)) }
            Symbol.SETTINGS -> { drawCircle(tint,4f*unit,point(12f,12f),style=Stroke(1.8f*unit));repeat(8) { i -> val a=Math.PI*i/4;line(point((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat()),point((12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())) } }
            Symbol.SCAN -> { rect(3f,3f,6f,6f);rect(15f,3f,6f,6f);rect(3f,15f,6f,6f);line(point(15f,15f),point(21f,15f));line(point(21f,15f),point(21f,21f));line(point(15f,21f),point(17f,21f)) }
            Symbol.SORT -> { line(point(4f,6f),point(20f,6f));line(point(7f,12f),point(17f,12f));line(point(10f,18f),point(14f,18f)) }
        }
    }
}
