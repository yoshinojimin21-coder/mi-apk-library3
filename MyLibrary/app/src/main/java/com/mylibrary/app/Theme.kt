package com.mylibrary.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

/** Paleta estilo Mac OS X "Aqua" / iPod (2001-2008). */
data class Aqua(
    val dark: Boolean,
    val stripeA: Color, val stripeB: Color,
    val barTop: Color, val barBottom: Color,
    val text: Color, val sub: Color,
    val rowA: Color, val rowB: Color,
    val lcdTop: Color, val lcdBottom: Color, val lcdText: Color,
    val btnTop: Color, val btnBottom: Color,
    val border: Color
)

val LightAqua = Aqua(
    false, Color(0xFFEDEDED), Color(0xFFF7F7F7),
    Color(0xFFF4F4F4), Color(0xFFBDC2C8),
    Color(0xFF1B1B1B), Color(0xFF6A6A6A),
    Color(0xFFFFFFFF), Color(0xFFEAF1FB),
    Color(0xFFE3EDF8), Color(0xFFB4C8DE), Color(0xFF13233A),
    Color(0xFF86C3FF), Color(0xFF1F78E6),
    Color(0xFF8C939B)
)

val DarkAqua = Aqua(
    true, Color(0xFF232426), Color(0xFF2A2B2D),
    Color(0xFF4B4D52), Color(0xFF26272A),
    Color(0xFFF2F2F2), Color(0xFF9EA0A6),
    Color(0xFF2F3033), Color(0xFF27282A),
    Color(0xFF2B4E73), Color(0xFF15283D), Color(0xFFC6E4FF),
    Color(0xFF5BA9FF), Color(0xFF1453B0),
    Color(0xFF0D0E10)
)

val LocalAqua = staticCompositionLocalOf { LightAqua }

/** Fondo de rayas finas, el clásico de Mac OS X. */
fun Modifier.pinstripes(a: Aqua): Modifier = this.drawBehind {
    drawRect(a.stripeA)
    val step = 4.dp.toPx()
    val h = 2.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(a.stripeB, Offset(0f, y), Size(size.width, h))
        y += step
    }
}

/** Botón azul "gel" con brillo en la mitad superior. */
@Composable
fun GlossButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    hPad: Dp = 14.dp,
    content: @Composable RowScope.() -> Unit
) {
    val a = LocalAqua.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(a.btnTop, a.btnBottom)))
            .drawWithContent {
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.08f))),
                    size = Size(size.width, size.height * 0.5f)
                )
                drawContent()
            }
            .border(1.dp, a.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}
