package com.tilelauncher.ui.tile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.AppShortcut
import com.tilelauncher.ui.theme.LocalMetro

/** What a long-press menu needs to list an app's shortcuts. */
class ShortcutMenuActions(
    val load: suspend (AppEntry) -> List<AppShortcut>,
    val launch: (AppShortcut) -> Unit,
    val pin: (AppShortcut) -> Unit,
)

/**
 * The app's shortcuts as menu rows: tap to open, tap the pin to make it a tile. Loads when
 * the menu opens; shows nothing if the app has none (or we aren't the default home app).
 */
@Composable
fun ShortcutMenuItems(app: AppEntry, actions: ShortcutMenuActions, onDone: () -> Unit) {
    val shortcuts by produceState(emptyList<AppShortcut>(), app.key) { value = actions.load(app) }
    shortcuts.forEach { shortcut ->
        DropdownMenuItem(
            text = { Text(shortcut.label) },
            leadingIcon = shortcut.icon?.let { icon -> { Image(icon, null, Modifier.size(24.dp)) } },
            trailingIcon = {
                PinGlyph(Modifier.clickable {
                    onDone()
                    actions.pin(shortcut)
                })
            },
            onClick = {
                onDone()
                actions.launch(shortcut)
            },
        )
    }
}

@Composable
private fun PinGlyph(modifier: Modifier) {
    val ink = LocalMetro.current.foreground
    Box(modifier.size(36.dp).padding(9.dp)) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            drawRoundRect(ink, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.38f), CornerRadius(stroke))
            drawLine(ink, Offset(w * 0.18f, h * 0.45f), Offset(w * 0.82f, h * 0.45f), stroke, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.5f, h * 0.45f), Offset(w * 0.5f, h), stroke, StrokeCap.Round)
        }
    }
}
