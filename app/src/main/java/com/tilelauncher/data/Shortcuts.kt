package com.tilelauncher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** One of an app's shortcuts ("New email", "New chat"…), ready for a menu. */
data class AppShortcut(val ref: ShortcutRef, val label: String, val icon: ImageBitmap?, val info: ShortcutInfo)

/**
 * App shortcuts through [LauncherApps]. Only the default home app may read and pin them,
 * so every call degrades to "nothing" when that isn't the case.
 */
class Shortcuts(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val iconDir = File(context.filesDir, "shortcut_icons").apply { mkdirs() }

    private fun hasAccess() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    /** Static (manifest) shortcuts first, then dynamic ones, at most five, like other launchers. */
    fun forApp(app: AppEntry): List<AppShortcut> {
        if (!hasAccess()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.key.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val infos = runCatching { launcherApps.getShortcuts(query, app.user) }.getOrNull().orEmpty()
        return infos
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
            .take(5)
            .map { toAppShortcut(it, app.key.userSerial) }
    }

    fun toAppShortcut(info: ShortcutInfo, userSerial: Long) = AppShortcut(
        ref = ShortcutRef(info.`package`, info.id, userSerial),
        label = labelOf(info),
        icon = render(info)?.asImageBitmap(),
        info = info,
    )

    fun labelOf(info: ShortcutInfo): String = (info.shortLabel ?: info.longLabel)?.toString().orEmpty()

    fun start(ref: ShortcutRef, bounds: Rect?, options: Bundle?) {
        val user = userFor(ref.userSerial)
        runCatching { launcherApps.startShortcut(ref.packageName, ref.id, bounds, options, user ?: return) }
            .onFailure { Toast.makeText(context, "That shortcut isn't available any more", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Makes the system's pinned set for each touched package match the tiles. Pins are per
     * launcher, so this is how a removed tile releases its shortcut.
     */
    fun syncPins(tiles: List<Tile>, touched: Collection<Pair<String, Long>>) {
        if (!hasAccess()) return
        val wanted = tiles.mapNotNull { it.shortcut }.groupBy({ it.packageName to it.userSerial }, { it.id })
        for ((pkg, serial) in touched.toSet()) {
            val user = userFor(serial) ?: continue
            runCatching { launcherApps.pinShortcuts(pkg, wanted[pkg to serial].orEmpty(), user) }
        }
    }

    // Icons are cached as files at pin time, so shortcut tiles render even if the app later
    // stops exposing the shortcut or we lose default-home status.
    fun saveIcon(ref: ShortcutRef, info: ShortcutInfo) {
        val bitmap = render(info) ?: return
        runCatching { iconFile(ref).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun loadIcon(ref: ShortcutRef): ImageBitmap? =
        runCatching { BitmapFactory.decodeFile(iconFile(ref).path)?.asImageBitmap() }.getOrNull()

    fun deleteIcon(ref: ShortcutRef) {
        iconFile(ref).delete()
    }

    private fun iconFile(ref: ShortcutRef) =
        File(iconDir, "${ref.packageName}_${ref.userSerial}_${ref.id.hashCode().toUInt()}.png")

    private fun render(info: ShortcutInfo): Bitmap? {
        val drawable = runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull() ?: return null
        val size = (96 * context.resources.displayMetrics.density).toInt()
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }
    }
}
