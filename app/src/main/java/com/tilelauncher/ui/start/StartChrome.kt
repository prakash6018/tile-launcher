package com.tilelauncher.ui.start

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tilelauncher.ui.theme.LocalMetro

val EditButtonSize = 34.dp

enum class EditGlyph { UNPIN, GROW, SHRINK, MORE }

/** The round buttons WP shows on a tile's corners in edit mode. */
@Composable
fun EditButton(glyph: EditGlyph, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val metro = LocalMetro.current
    val ink = metro.background
    Box(
        modifier
            .size(EditButtonSize)
            .clip(CircleShape)
            .background(metro.foreground)
            .border(2.dp, ink, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val stroke = 2.dp.toPx()
            when (glyph) {
                EditGlyph.UNPIN -> drawUnpin(ink, stroke)
                EditGlyph.GROW -> drawDiagonalArrow(ink, stroke, outward = true)
                EditGlyph.SHRINK -> drawDiagonalArrow(ink, stroke, outward = false)
                EditGlyph.MORE -> {
                    val r = stroke * 0.9f
                    listOf(0.15f, 0.5f, 0.85f).forEach { drawCircle(ink, r, Offset(size.width * it, size.height / 2)) }
                }
            }
        }
    }
}

private fun DrawScope.drawUnpin(ink: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    // Push-pin: head, collar, needle; then a slash through it.
    drawRoundRect(ink, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.38f), CornerRadius(stroke))
    drawLine(ink, Offset(w * 0.18f, h * 0.45f), Offset(w * 0.82f, h * 0.45f), stroke, StrokeCap.Round)
    drawLine(ink, Offset(w * 0.5f, h * 0.45f), Offset(w * 0.5f, h), stroke, StrokeCap.Round)
    drawLine(ink, Offset(0f, h), Offset(w, 0f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawDiagonalArrow(ink: Color, stroke: Float, outward: Boolean) {
    val a = Offset(size.width * 0.1f, size.height * 0.1f)
    val b = Offset(size.width * 0.9f, size.height * 0.9f)
    val (from, to) = if (outward) a to b else b to a
    drawLine(ink, from, to, stroke, StrokeCap.Round)
    val head = size.width * 0.4f
    val dir = if (outward) -1f else 1f
    drawLine(ink, to, Offset(to.x + dir * head, to.y), stroke, StrokeCap.Round)
    drawLine(ink, to, Offset(to.x, to.y + dir * head), stroke, StrokeCap.Round)
}

/** The circled right-arrow under the tiles that opens the app list. */
@Composable
fun AllAppsArrow(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val color = LocalMetro.current.foreground
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize().padding(13.dp)) {
            val stroke = 2.dp.toPx()
            val midY = this.size.height / 2
            drawLine(color, Offset(0f, midY), Offset(this.size.width, midY), stroke, StrokeCap.Square)
            drawLine(color, Offset(this.size.width, midY), Offset(this.size.width - midY, 0f), stroke, StrokeCap.Square)
            drawLine(color, Offset(this.size.width, midY), Offset(this.size.width - midY, this.size.height), stroke, StrokeCap.Square)
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("name folder") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
        shape = RectangleShape,
    )
}
