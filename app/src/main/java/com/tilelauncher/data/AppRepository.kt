package com.tilelauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Collator

/** Lists launchable apps across all user profiles and keeps the list fresh as packages change. */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val loadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
    }

    fun refresh() {
        scope.launch(loadDispatcher) { _apps.value = load() }
    }

    private fun load(): List<AppEntry> {
        val me = Process.myUserHandle()
        val collator = Collator.getInstance()
        return launcherApps.profiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map { info ->
                AppEntry(
                    key = AppKey(info.componentName, serial),
                    label = info.label.toString(),
                    user = user,
                    info = info,
                    isWorkProfile = user != me,
                )
            }
        }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareBy(collator) { it.label })
    }

    fun launch(app: AppEntry, sourceBounds: Rect?, options: Bundle?) {
        runCatching { launcherApps.startMainActivity(app.key.component, app.user, sourceBounds, options) }
            .onFailure { toast("Couldn't open ${app.label}") }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.key.component, app.user, null, null) }
            .onFailure { toast("Couldn't open app info") }
    }

    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { toast("Couldn't uninstall ${app.label}") }
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
