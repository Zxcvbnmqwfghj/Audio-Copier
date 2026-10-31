package com.audiocopier.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*

enum class Glyph { POWER, STATS, FOLDER, GEAR, WAVE, SHIELD, BATTERY, STORAGE, SOURCE, DESTINATION, MUSIC, SEARCH, LANGUAGE, MOON, MORE, PLAY, PAUSE, PREVIOUS, NEXT }
@Composable
fun DesignIcon(kind: Glyph, tint: Color, paper: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 100f, size.height / 100f, Offset.Zero) }) {
            val line = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun path(block: Path.() -> Unit) = Path().apply(block)
            fun outline(p: Path, width: Float = 5f) = drawPath(p, tint, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            when (kind) {
                Glyph.POWER -> {
                    drawArc(tint, -52f, 284f, false, Offset(12f, 17f), Size(76f, 76f), style = Stroke(13f, cap = StrokeCap.Round))
                    drawLine(tint, Offset(50f, 5f), Offset(50f, 37f), 13f, StrokeCap.Round)
                }
                Glyph.STATS -> {
                    drawRect(tint, Offset(12f, 58f), Size(15f, 24f)); drawRect(tint, Offset(42f, 12f), Size(15f, 70f))
                    drawRect(tint, Offset(72f, 38f), Size(15f, 44f)); drawRect(tint, Offset(6f, 88f), Size(88f, 7f))
                }
                Glyph.FOLDER -> {
                    outline(path { moveTo(8f, 80f); lineTo(8f, 23f); quadraticBezierTo(8f, 13f, 18f, 13f); lineTo(39f, 13f); lineTo(49f, 25f); lineTo(82f, 25f); lineTo(87f, 33f) })
                    outline(path { moveTo(12f, 88f); lineTo(23f, 36f); lineTo(94f, 36f); lineTo(83f, 88f); close() })
                }
                Glyph.GEAR -> {
                    val p = Path()
                    for (i in 0 until 32) {
                        val a = i * PI * 2 / 32
                        val radius = if (i % 4 < 2) 44f else 35f
                        val x = 50f + cos(a).toFloat() * radius; val y = 50f + sin(a).toFloat() * radius
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close(); outline(p, 4f); drawCircle(tint, 16f, Offset(50f, 50f), style = Stroke(4f))
                }
                Glyph.WAVE -> for ((i, height) in listOf(22f, 55f, 35f, 82f, 50f).withIndex()) {
                    drawLine(tint, Offset(10f + i * 20, 50 - height / 2), Offset(10f + i * 20, 50 + height / 2), 8f, StrokeCap.Round)
                }
                Glyph.SHIELD -> {
                    outline(path { moveTo(50f, 6f); lineTo(91f, 24f); cubicTo(92f, 59f, 78f, 82f, 50f, 96f); cubicTo(22f, 82f, 8f, 59f, 9f, 24f); close() })
                    outline(path { moveTo(29f, 49f); lineTo(46f, 65f); lineTo(73f, 35f) })
                }
                Glyph.BATTERY -> {
                    drawRoundRect(tint, Offset(25f, 16f), Size(50f, 77f), CornerRadius(5f), style = Stroke(3f))
                    drawRoundRect(tint, Offset(39f, 8f), Size(22f, 8f), CornerRadius(2f), style = Stroke(3f))
                    outline(path { moveTo(58f, 31f); lineTo(38f, 58f); lineTo(51f, 55f); lineTo(43f, 78f); lineTo(64f, 48f); lineTo(51f, 51f); close() }, 2.5f)
                }
                Glyph.STORAGE -> {
                    drawCircle(tint, 24f, Offset(52f, 35f)); drawCircle(tint, 20f, Offset(28f, 52f)); drawCircle(tint, 20f, Offset(75f, 52f)); drawRoundRect(tint, Offset(11f, 48f), Size(82f, 33f), CornerRadius(13f))
                    drawLine(paper, Offset(51f, 77f), Offset(51f, 46f), 9f, StrokeCap.Round)
                    drawLine(paper, Offset(37f, 58f), Offset(51f, 44f), 9f, StrokeCap.Round)
                    drawLine(paper, Offset(65f, 58f), Offset(51f, 44f), 9f, StrokeCap.Round)
                }
                Glyph.SOURCE, Glyph.DESTINATION, Glyph.MUSIC -> {
                    val p = path { moveTo(17f, 7f); lineTo(60f, 7f); lineTo(84f, 31f); lineTo(84f, 93f); lineTo(17f, 93f); close() }
                    if (kind == Glyph.MUSIC) outline(p, 4f) else drawPath(p, tint)
                    val inner = if (kind == Glyph.MUSIC) tint else paper
                    drawPath(path { moveTo(60f, 8f); lineTo(60f, 31f); lineTo(84f, 31f) }, inner, style = Stroke(4f))
                    if (kind == Glyph.MUSIC) {
                        drawPath(path { moveTo(40f, 75f); lineTo(40f, 49f); lineTo(64f, 43f); lineTo(64f, 69f) }, tint, style = Stroke(4f))
                        drawOval(tint, Offset(28f, 71f), Size(14f, 10f)); drawOval(tint, Offset(52f, 65f), Size(14f, 10f))
                    } else {
                        val up = kind == Glyph.SOURCE
                        drawLine(inner, Offset(50f, 48f), Offset(50f, 79f), 5f, StrokeCap.Round)
                        val point = if (up) 46f else 81f; val tail = if (up) 59f else 68f
                        drawPath(path { moveTo(37f, tail); lineTo(50f, point); lineTo(63f, tail) }, inner, style = line)
                    }
                }
                Glyph.SEARCH -> { drawCircle(tint, 26f, Offset(42f, 42f), style = Stroke(8f)); drawLine(tint, Offset(63f, 63f), Offset(90f, 90f), 8f, StrokeCap.Round) }
                Glyph.LANGUAGE -> {
                    outline(path { moveTo(12f, 36f); cubicTo(16f, 11f, 51f, 6f, 74f, 15f); lineTo(66f, 5f); moveTo(74f, 15f); lineTo(62f, 23f) }, 4f)
                    outline(path { moveTo(88f, 64f); cubicTo(84f, 89f, 49f, 94f, 26f, 85f); lineTo(34f, 95f); moveTo(26f, 85f); lineTo(38f, 77f) }, 4f)
                    outline(path { moveTo(58f, 62f); lineTo(72f, 30f); lineTo(86f, 62f); moveTo(64f, 49f); lineTo(80f, 49f) }, 4f)
                    outline(path { moveTo(9f, 46f); lineTo(43f, 46f); moveTo(26f, 38f); lineTo(26f, 46f); moveTo(35f, 46f); quadraticBezierTo(34f, 64f, 12f, 71f); moveTo(17f, 51f); quadraticBezierTo(21f, 66f, 42f, 71f) }, 3f)
                }
                Glyph.MOON -> {
                    drawCircle(tint, 30f, Offset(60f, 38f)); drawCircle(paper, 27f, Offset(74f, 25f))
                    for ((x, y) in listOf(16f to 23f, 29f to 20f, 36f to 29f, 26f to 70f, 44f to 72f, 54f to 62f, 75f to 67f, 87f to 60f)) drawCircle(tint, 10f, Offset(x, y))
                }
                Glyph.MORE -> for (y in listOf(25f, 50f, 75f)) drawCircle(tint, 6f, Offset(50f, y))
                Glyph.PLAY -> drawPath(path { moveTo(34f, 23f); lineTo(76f, 50f); lineTo(34f, 77f); close() }, tint)
                Glyph.PAUSE -> { drawRect(tint, Offset(31f, 24f), Size(12f, 52f)); drawRect(tint, Offset(57f, 24f), Size(12f, 52f)) }
                Glyph.PREVIOUS, Glyph.NEXT -> {
                    val x = if (kind == Glyph.PREVIOUS) 28f else 72f; val tail = 100f - x
                    drawLine(tint, Offset(x, 23f), Offset(x, 77f), 5f)
                    drawPath(path { moveTo(x, 50f); lineTo(tail, 24f); lineTo(tail, 76f); close() }, tint)
                }
            }
        }
    }
}
