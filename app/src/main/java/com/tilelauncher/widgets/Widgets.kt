package com.tilelauncher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.tilelauncher.ui.theme.LocalMetro
import com.tilelauncher.ui.theme.OnTile
import com.tilelauncher.ui.theme.SemiLight

/** A bound app widget hosted inside a tile. */
@Composable
fun WidgetView(host: AppWidgetHost, manager: AppWidgetManager, widgetId: Int, width: Dp, height: Dp) {
    val info = remember(widgetId) { manager.getAppWidgetInfo(widgetId) }
    if (info == null) {
        Box(Modifier.fillMaxSize()) {
            Text("widget unavailable", color = OnTile, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    AndroidView(
        factory = { context -> host.createView(context, widgetId, info).apply { setPadding(0, 0, 0, 0) } },
        update = { view ->
            @Suppress("DEPRECATION") // The SizeF list variant needs API 31; these bounds are exact anyway.
            view.updateAppWidgetSize(Bundle(), width.value.toInt(), height.value.toInt(), width.value.toInt(), height.value.toInt())
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun WidgetPicker(providers: List<AppWidgetProviderInfo>, onPick: (AppWidgetProviderInfo) -> Unit, onDismiss: () -> Unit) {
    val metro = LocalMetro.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.densityDpi
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(metro.chrome)
                .padding(vertical = 16.dp),
        ) {
            Text("choose a widget", fontSize = 26.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.size(8.dp))
            if (providers.isEmpty()) {
                Text("No widgets are installed.", color = metro.subtle, modifier = Modifier.padding(20.dp))
            }
            LazyColumn {
                items(providers, key = { it.provider.flattenToString() }) { info ->
                    val label = remember(info) { info.loadLabel(context.packageManager) }
                    val icon = remember(info) {
                        runCatching { info.loadIcon(context, density)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
                    }
                    val app = remember(info) {
                        runCatching {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(info.provider.packageName, 0)
                            ).toString()
                        }.getOrDefault("")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(info) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Box(Modifier.size(44.dp).background(metro.accent)) {
                            icon?.let { Image(it, null, Modifier.align(Alignment.Center).size(32.dp)) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(label, fontSize = 18.sp, fontWeight = SemiLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (app.isNotEmpty()) Text(app, fontSize = 13.sp, color = metro.subtle, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
