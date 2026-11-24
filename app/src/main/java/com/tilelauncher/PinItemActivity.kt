package com.tilelauncher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.tilelauncher.data.LayoutOps
import com.tilelauncher.data.SettingsRepository
import com.tilelauncher.data.ShortcutRef
import com.tilelauncher.data.Shortcuts
import com.tilelauncher.data.Tile
import com.tilelauncher.data.TileKind
import com.tilelauncher.data.TileSize
import com.tilelauncher.ui.settings.MetroButton
import com.tilelauncher.ui.theme.MetroTheme
import com.tilelauncher.ui.theme.OnTile
import com.tilelauncher.ui.theme.metroColors
import kotlinx.coroutines.launch

/**
 * Handles "Add to home screen" from other apps: Chrome web apps, chat shortcuts, and apps
 * asking to pin one of their widgets. Shows a small WP-style confirmation, then adds a tile.
 */
class PinItemActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request == null || !request.isValid) {
            finish()
            return
        }

        val label: String
        val icon: ImageBitmap?
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = request.shortcutInfo ?: return finish()
                label = Shortcuts(this).labelOf(info)
                icon = Shortcuts(this).toAppShortcut(info, 0).icon
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val info = request.getAppWidgetProviderInfo(this) ?: return finish()
                label = info.loadLabel(packageManager)
                icon = runCatching { info.loadIcon(this, resources.displayMetrics.densityDpi)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
            }
            else -> return finish()
        }

        val settings = SettingsRepository(this).settings.value
        setContent {
            MetroTheme(metroColors(Color(settings.accent), light = false)) {
                ConfirmPin(
                    label = label,
                    icon = icon,
                    accent = Color(settings.accent),
                    onAdd = { lifecycleScope.launch { accept(request); finish() } },
                    onCancel = ::finish,
                )
            }
        }
    }

    private suspend fun accept(request: LauncherApps.PinItemRequest) {
        val layout = launcherApp.layout
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = request.shortcutInfo ?: return
                if (!request.accept()) return
                val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.userHandle ?: Process.myUserHandle())
                val ref = ShortcutRef(info.`package`, info.id, serial)
                val shortcuts = Shortcuts(this)
                shortcuts.saveIcon(ref, info)
                layout.updateWhenLoaded { tiles ->
                    if (tiles.any { it.shortcut == ref }) tiles
                    else LayoutOps.add(tiles, Tile(id = 0, kind = TileKind.SHORTCUT, size = TileSize.MEDIUM, title = shortcuts.labelOf(info), shortcut = ref))
                }
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val info = request.getAppWidgetProviderInfo(this) ?: return
                val host = launcherApp.widgetHost
                val id = host.allocateAppWidgetId()
                if (!request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })) {
                    host.deleteAppWidgetId(id)
                    return
                }
                val dp = resources.displayMetrics.density
                val size = when {
                    info.minHeight / dp > 200 -> TileSize.LARGE
                    info.minWidth / dp > 150 -> TileSize.WIDE
                    else -> TileSize.MEDIUM
                }
                layout.updateWhenLoaded { LayoutOps.add(it, Tile(id = 0, kind = TileKind.WIDGET, size = size, widgetId = id)) }
            }
        }
    }
}

@Composable
private fun ConfirmPin(label: String, icon: ImageBitmap?, accent: Color, onAdd: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1F1F1F))
                .clickable(enabled = true, onClick = {})
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("pin to start", fontSize = 30.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).background(accent)) {
                    icon?.let { Image(it, null, Modifier.align(Alignment.Center).size(44.dp)) }
                }
                Spacer(Modifier.width(16.dp))
                Text(label, fontSize = 22.sp, color = OnTile)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton("pin", onAdd, filled = true)
                MetroButton("cancel", onCancel)
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
