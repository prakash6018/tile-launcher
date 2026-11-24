package com.tilelauncher.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tilelauncher.data.IconPackInfo
import com.tilelauncher.data.IconStyle
import com.tilelauncher.data.LauncherSettings
import com.tilelauncher.data.ThemeMode
import com.tilelauncher.live.NotificationHub
import com.tilelauncher.live.NotificationListener
import com.tilelauncher.ui.theme.Accents
import com.tilelauncher.ui.theme.LocalMetro
import com.tilelauncher.ui.theme.SemiLight
import kotlinx.coroutines.launch

const val PAGE_PERMISSIONS = 3
private val PAGES = listOf("theme", "start", "tiles", "permissions", "backup")

/** WP-style pivot: big lowercase headers that slide with the pages. */
@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    onUpdate: ((LauncherSettings) -> LauncherSettings) -> Unit,
    iconPacks: List<IconPackInfo>,
    initialPage: Int,
    /** Changes on every resume, so permission states re-read after a trip to system settings. */
    resumeCount: Int,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onPermissionsChanged: () -> Unit,
) {
    val metro = LocalMetro.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage) { PAGES.size }
    val headerScroll = rememberScrollState()
    val headerX = remember { mutableStateListOf(*Array(PAGES.size) { 0 }) }

    LaunchedEffect(pager.currentPage) { headerScroll.animateScrollTo(headerX[pager.currentPage]) }

    Column(Modifier.fillMaxSize().background(metro.background)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Text(
            "TILE LAUNCHER",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp),
        )
        Row(Modifier.horizontalScroll(headerScroll, enabled = false)) {
            PAGES.forEachIndexed { i, title ->
                Text(
                    title,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    color = if (i == pager.currentPage) metro.foreground else metro.subtle.copy(alpha = 0.6f),
                    modifier = Modifier
                        .onGloballyPositioned { c -> c.positionInParent().x.toInt().let { if (headerX[i] != it) headerX[i] = it } }
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(start = 16.dp, end = 4.dp),
                )
            }
            Spacer(Modifier.width(400.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                when (PAGES[page]) {
                    "theme" -> ThemePage(settings, onUpdate)
                    "start" -> StartPage(settings, onUpdate, onPickBackground, onClearBackground)
                    "tiles" -> TilesPage(settings, onUpdate, iconPacks)
                    "permissions" -> PermissionsPage(resumeCount, onPermissionsChanged)
                    "backup" -> BackupPage(onExport, onImport)
                }
                Spacer(Modifier.padding(bottom = 24.dp).windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun ThemePage(settings: LauncherSettings, onUpdate: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    Section("background")
    ThemeMode.entries.forEach { mode ->
        Choice(mode.name.lowercase(), settings.theme == mode) { onUpdate { it.copy(theme = mode) } }
    }
    Section("accent color")
    Accents.all.chunked(5).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (name, color) ->
                val selected = settings.accent == color.toArgb()
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(color)
                        .then(if (selected) Modifier.border(3.dp, LocalMetro.current.foreground) else Modifier)
                        .clickable { onUpdate { it.copy(accent = color.toArgb()) } },
                ) {
                    Text(name, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun StartPage(
    settings: LauncherSettings,
    onUpdate: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
) {
    Toggle("show more tiles", "fit three medium tiles across instead of two", settings.columns == 6) { on ->
        onUpdate { it.copy(columns = if (on) 6 else 4) }
    }
    Toggle("live tiles", "tiles flip to show photos, appointments, music and more", settings.liveTiles) { on ->
        onUpdate { it.copy(liveTiles = on) }
    }
    Toggle("message previews", "show who notifications are from and what they say, e.g. Outlook mail and Teams chats", settings.notificationPreviews) { on ->
        onUpdate { it.copy(notificationPreviews = on) }
    }
    Section("start background")
    Text("A picture that shows through your tiles.", color = LocalMetro.current.subtle, fontSize = 15.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetroButton("choose", onPickBackground)
        if (settings.backgroundVersion != 0L) MetroButton("remove", onClearBackground)
    }
    if (settings.backgroundVersion != 0L) {
        Toggle("parallax", "background drifts as you scroll", settings.parallax) { on -> onUpdate { it.copy(parallax = on) } }
    }
}

@Composable
private fun TilesPage(settings: LauncherSettings, onUpdate: ((LauncherSettings) -> LauncherSettings) -> Unit, iconPacks: List<IconPackInfo>) {
    Section("icon style")
    Choice("glyph (white, where the app provides one)", settings.iconStyle == IconStyle.GLYPH) { onUpdate { it.copy(iconStyle = IconStyle.GLYPH) } }
    Choice("full color", settings.iconStyle == IconStyle.COLOR) { onUpdate { it.copy(iconStyle = IconStyle.COLOR) } }
    Section("icon pack")
    Choice("none", settings.iconPack == null) { onUpdate { it.copy(iconPack = null) } }
    iconPacks.forEach { pack ->
        Choice(pack.label, settings.iconPack == pack.packageName) { onUpdate { it.copy(iconPack = pack.packageName) } }
    }
    if (iconPacks.isEmpty()) {
        Text("Install an icon pack (ADW/Nova format) to use it here.", color = LocalMetro.current.subtle, fontSize = 15.sp)
    }
}

private data class RuntimePermission(val title: String, val why: String, val permission: String)

@Composable
private fun PermissionsPage(resumeCount: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val photos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val runtime = listOf(
        RuntimePermission("calendar", "next appointment on the calendar tile", Manifest.permission.READ_CALENDAR),
        RuntimePermission("photos", "recent pictures on the photos tile", photos),
        RuntimePermission("contacts", "contact pictures on the people tile", Manifest.permission.READ_CONTACTS),
        RuntimePermission("location", "local forecast on the weather tile", Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    val pending = remember { arrayOfNulls<String>(1) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val asked = pending[0]
        // Denied for good: the only way back is the app's system settings page.
        if (!granted && asked != null && activity != null && !activity.shouldShowRequestPermissionRationale(asked)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        onChanged()
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    // resumeCount is read so statuses refresh after returning from system settings.
    val statuses = remember(resumeCount) {
        runtime.associate { it.permission to granted(context, it.permission) } +
            mapOf("notifications" to NotificationHub.isEnabled(context), "home" to isDefaultHome(context))
    }

    PermissionRow("default home screen", "open Tile Launcher with the home button", statuses["home"] == true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    PermissionRow("notification access", "unread counts, and now playing on music tiles", statuses["notifications"] == true) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NotificationListener::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    runtime.forEach { p ->
        PermissionRow(p.title, p.why, statuses[p.permission] == true) {
            pending[0] = p.permission
            request.launch(p.permission)
        }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isDefaultHome(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
}

@Composable
private fun BackupPage(onExport: () -> Unit, onImport: () -> Unit) {
    Text(
        "Save your tiles, folders and theme to a file, or restore them from one. Your layout is also included in Android's automatic backup.",
        color = LocalMetro.current.subtle,
        fontSize = 15.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetroButton("export", onExport)
        MetroButton("import", onImport)
    }
}

// --- Controls ---

@Composable
private fun Section(title: String) {
    Text(title, color = LocalMetro.current.subtle, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val metro = LocalMetro.current
    Text(
        label,
        fontSize = 22.sp,
        fontWeight = SemiLight,
        color = if (selected) metro.accent else metro.foreground,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

/** WP toggle: label, then On/Off with a rectangular switch. */
@Composable
private fun Toggle(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val metro = LocalMetro.current
    Column(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Text(label, fontSize = 20.sp, fontWeight = SemiLight)
        detail?.let { Text(it, fontSize = 14.sp, color = metro.subtle) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(if (checked) "On" else "Off", fontSize = 22.sp, fontWeight = SemiLight, modifier = Modifier.weight(1f))
            Box(Modifier.size(width = 60.dp, height = 26.dp).border(2.dp, metro.foreground).padding(4.dp)) {
                if (checked) Box(Modifier.fillMaxSize().background(metro.accent))
                Box(
                    Modifier
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 0.dp)
                        .width(12.dp)
                        .fillMaxHeight()
                        .background(metro.foreground),
                )
            }
        }
    }
}

@Composable
fun MetroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val metro = LocalMetro.current
    Box(
        modifier
            .then(if (filled) Modifier.background(metro.accent) else Modifier)
            .border(2.dp, if (filled) metro.accent else metro.foreground)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 17.sp, color = if (filled) Color.White else metro.foreground)
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    val metro = LocalMetro.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = SemiLight)
            Text(why, fontSize = 14.sp, color = metro.subtle)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) Text("on", fontSize = 17.sp, color = metro.accent) else MetroButton("turn on", onGrant)
    }
}
