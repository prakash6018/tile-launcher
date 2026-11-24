package com.tilelauncher

import android.app.Application
import android.appwidget.AppWidgetHost
import com.tilelauncher.data.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide state shared by the launcher screen and the pin-confirmation dialog that
 * other apps open ("Add to home screen"), which runs as a separate activity.
 */
class LauncherApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val layout by lazy { LayoutRepository(this, appScope) }
    val widgetHost by lazy { AppWidgetHost(this, WIDGET_HOST_ID) }

    companion object {
        private const val WIDGET_HOST_ID = 0x7117
    }
}

val android.content.Context.launcherApp: LauncherApp get() = applicationContext as LauncherApp
