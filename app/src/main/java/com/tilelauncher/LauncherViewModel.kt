package com.tilelauncher

import android.app.Application
import android.app.SearchManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.AppKey
import com.tilelauncher.data.AppShortcut
import com.tilelauncher.data.ShortcutRef
import com.tilelauncher.data.Shortcuts
import com.tilelauncher.data.AppRepository
import com.tilelauncher.data.DefaultLayout
import com.tilelauncher.data.IconLoader
import com.tilelauncher.data.IconPack
import com.tilelauncher.data.IconPackInfo
import com.tilelauncher.data.LauncherSettings
import com.tilelauncher.data.LayoutOps
import com.tilelauncher.data.LayoutStore
import com.tilelauncher.data.SettingsRepository
import com.tilelauncher.data.StartItem
import com.tilelauncher.data.Tile
import com.tilelauncher.data.TileKind
import com.tilelauncher.data.TileSize
import com.tilelauncher.live.LiveTiles
import com.tilelauncher.live.NotificationHub
import com.tilelauncher.live.TileFace
import com.tilelauncher.widgets.GlanceSnapshot
import com.tilelauncher.widgets.GlanceWidget
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repository = AppRepository(application, viewModelScope)
    private val layout = application.launcherApp.layout
    private val settingsRepository = SettingsRepository(application)
    private val shortcuts = Shortcuts(application)

    val iconLoader = IconLoader(application)
    val liveTiles = LiveTiles(application, viewModelScope)
    val widgetHost: AppWidgetHost = application.launcherApp.widgetHost
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(application)

    val apps: StateFlow<List<AppEntry>> = repository.apps
    val settings: StateFlow<LauncherSettings> = settingsRepository.settings

    /** Null until the saved layout (or the first-run default) is loaded. */
    private val tiles = layout.tiles

    /** Top-level Start tiles joined with their apps; tiles for uninstalled apps are hidden, not deleted. */
    val startItems: StateFlow<List<StartItem>> = combine(tiles.filterNotNull(), repository.apps) { tiles, apps ->
        resolve(tiles, apps.associateBy { it.key })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pinnedKeys: StateFlow<Set<AppKey>> = tiles.filterNotNull()
        .map { list -> list.mapNotNullTo(HashSet()) { it.app } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val backgroundFile = File(application.filesDir, "start_background.jpg")

    val background: StateFlow<ImageBitmap?> = settings.map { it.backgroundVersion }.distinctUntilChanged()
        .mapLatest { version ->
            if (version == 0L) null
            else withContext(Dispatchers.IO) { BitmapFactory.decodeFile(backgroundFile.path)?.asImageBitmap() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _iconPacks = MutableStateFlow<List<IconPackInfo>>(emptyList())
    val iconPacks: StateFlow<List<IconPackInfo>> = _iconPacks

    init {
        repository.start()
        viewModelScope.launch {
            if (!layout.awaitLoaded() && tiles.value == null) {
                val installed = repository.apps.first { it.isNotEmpty() }
                layout.set(withContext(Dispatchers.Default) { DefaultLayout.build(application, installed) })
            }
            deleteOrphanWidgets()
        }
        viewModelScope.launch {
            settings.collect {
                iconLoader.configure(it.iconStyle, it.iconPack)
                liveTiles.setPreviewsEnabled(it.notificationPreviews)
            }
        }
        // Package changes can add or remove icon packs and change icons.
        viewModelScope.launch {
            repository.apps.collect { list ->
                liveTiles.setAppLabels(list.associate { it.packageName to it.label })
                _iconPacks.value = withContext(Dispatchers.IO) { IconPack.installed(application) }
            }
        }
    }

    private fun resolve(tiles: List<Tile>, byKey: Map<AppKey, AppEntry>): List<StartItem> {
        val installed = byKey.keys.mapTo(HashSet()) { it.component.packageName to it.userSerial }
        val children = tiles.filter { it.parentId != null }.groupBy { it.parentId }
        return tiles.filter { it.parentId == null }.mapNotNull { t ->
            when (t.kind) {
                TileKind.APP -> t.app?.let(byKey::get)?.let { StartItem.App(t, it) }
                TileKind.FOLDER -> children[t.id].orEmpty()
                    .mapNotNull { c -> c.app?.let(byKey::get)?.let { StartItem.App(c, it) } }
                    .takeIf { it.isNotEmpty() }
                    ?.let { StartItem.Folder(t, it) }
                TileKind.WEATHER -> StartItem.Weather(t)
                TileKind.WIDGET -> t.widgetId?.let { StartItem.Widget(t) }
                // Shown while the owning app is installed for that profile.
                TileKind.SHORTCUT -> t.shortcut
                    ?.takeIf { ref -> installed.contains(ref.packageName to ref.userSerial) }
                    ?.let { StartItem.Shortcut(t, it, t.title.orEmpty()) }
                TileKind.ME -> StartItem.Me(t)
            }
        }
    }

    // --- Apps ---

    fun launch(app: AppEntry, sourceBounds: Rect?, options: Bundle? = null) = repository.launch(app, sourceBounds, options)
    fun openAppInfo(app: AppEntry) = repository.openAppInfo(app)
    fun uninstall(app: AppEntry) = repository.uninstall(app)

    fun launchWeather() {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "weather")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=weather"))
        for (intent in listOf(search, web)) {
            if (runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    // --- Layout ---

    fun pin(app: AppEntry) = updateTiles { LayoutOps.pinApp(it, app.key) }
    fun resize(tileId: Long, size: TileSize) = updateTiles { LayoutOps.resize(it, tileId, size) }
    fun reorder(order: List<Long>) = updateTiles { LayoutOps.reorder(it, order) }
    fun dropOnto(draggedId: Long, targetId: Long) = updateTiles { LayoutOps.dropOnto(it, draggedId, targetId) }
    fun removeFromFolder(childId: Long) = updateTiles { LayoutOps.removeFromFolder(it, childId) }
    fun rename(tileId: Long, title: String) = updateTiles { LayoutOps.rename(it, tileId, title) }
    fun setLive(tileId: Long, enabled: Boolean) = updateTiles { LayoutOps.setLive(it, tileId, enabled) }
    fun addWeather() = updateTiles { LayoutOps.add(it, Tile(id = 0, kind = TileKind.WEATHER, size = TileSize.WIDE)) }
    fun addMe() = updateTiles { LayoutOps.add(it, Tile(id = 0, kind = TileKind.ME, size = TileSize.WIDE)) }

    fun unpin(tileId: Long) {
        val removed = layout.update { LayoutOps.remove(it, tileId) }
        releaseResources(removed)
    }

    private fun updateTiles(transform: (List<Tile>) -> List<Tile>) {
        layout.update(transform)
    }

    /** Frees what removed tiles held: widget ids, shortcut pins and cached icons. */
    private fun releaseResources(removed: List<Tile>) {
        removed.mapNotNull { it.widgetId }.forEach { widgetHost.deleteAppWidgetId(it) }
        val refs = removed.mapNotNull { it.shortcut }
        if (refs.isEmpty()) return
        refs.forEach(shortcuts::deleteIcon)
        val remaining = tiles.value.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            shortcuts.syncPins(remaining, refs.map { it.packageName to it.userSerial })
        }
    }

    // --- Shortcuts ---

    suspend fun shortcutsFor(app: AppEntry): List<AppShortcut> = withContext(Dispatchers.IO) { shortcuts.forApp(app) }

    fun launchShortcut(ref: ShortcutRef, bounds: Rect?, options: Bundle?) = shortcuts.start(ref, bounds, options)

    /** Pins one of an app's own shortcuts as a tile (from the long-press menu). */
    fun pinShortcut(shortcut: AppShortcut) {
        val ref = shortcut.ref
        if (tiles.value.orEmpty().any { it.shortcut == ref }) return
        updateTiles {
            LayoutOps.add(it, Tile(id = 0, kind = TileKind.SHORTCUT, size = TileSize.MEDIUM, title = shortcut.label, shortcut = ref))
        }
        val now = tiles.value.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            shortcuts.saveIcon(ref, shortcut.info)
            shortcuts.syncPins(now, listOf(ref.packageName to ref.userSerial))
        }
    }

    fun shortcutIcon(ref: ShortcutRef): ImageBitmap? = shortcuts.loadIcon(ref)

    // --- Widgets ---

    fun installedWidgets(): List<AppWidgetProviderInfo> =
        widgetManager.installedProviders.sortedBy { it.loadLabel(app.packageManager).lowercase() }

    fun addWidgetTile(widgetId: Int, info: AppWidgetProviderInfo) {
        val dp = app.resources.displayMetrics.density
        val size = when {
            info.minHeight / dp > 200 -> TileSize.LARGE
            info.minWidth / dp > 150 -> TileSize.WIDE
            else -> TileSize.MEDIUM
        }
        updateTiles { LayoutOps.add(it, Tile(id = 0, kind = TileKind.WIDGET, size = size, widgetId = widgetId)) }
    }

    fun deleteWidget(widgetId: Int) = widgetHost.deleteAppWidgetId(widgetId)

    private fun deleteOrphanWidgets() {
        val used = tiles.value.orEmpty().mapNotNullTo(HashSet()) { it.widgetId }
        widgetHost.appWidgetIds.filterNot { it in used }.forEach { widgetHost.deleteAppWidgetId(it) }
    }

    // --- Settings ---

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) = settingsRepository.update(transform)

    fun refreshLiveTiles() = liveTiles.refresh()

    private val glanceActive = MutableStateFlow(GlanceWidget.hasInstances(application))

    fun onLauncherResumed() {
        liveTiles.refreshCarrier()
        glanceActive.value = GlanceWidget.hasInstances(app)
    }

    // Declared after glanceActive: property initialisers and init blocks run top to bottom.
    init {
        startGlanceUpdates()
    }

    /** Pushes live data to the cover-screen/home-screen glance widget while one is placed. */
    @OptIn(FlowPreview::class)
    private fun startGlanceUpdates() = viewModelScope.launch {
        glanceActive.flatMapLatest { active ->
            if (!active) emptyFlow()
            else combine(
                combine(settings, liveTiles.weather, liveTiles.calendar, ::Triple),
                liveTiles.badges,
                NotificationHub.media,
                repository.apps,
            ) { (s, weather, calendar), badges, playing, apps ->
                val labels = apps.associate { it.packageName to it.label }
                val w = weather?.faces?.firstOrNull() as? TileFace.Text
                val next = calendar?.faces?.firstOrNull() as? TileFace.Text
                val busiest = badges.entries.filter { it.value > 0 && it.key in labels }.sortedByDescending { it.value }
                GlanceSnapshot(
                    accent = s.accent,
                    weatherTemp = w?.headline?.takeIf { it.endsWith("°") },
                    weatherLines = w?.lines?.take(2).orEmpty(),
                    nextTitle = next?.headline,
                    nextWhen = next?.lines?.lastOrNull(),
                    unread = busiest.sumOf { it.value },
                    unreadApps = busiest.take(3).joinToString("\n") { (pkg, n) -> "${labels[pkg] ?: pkg.substringAfterLast('.')} $n" },
                    nowPlaying = playing?.let { listOfNotNull(it.title, it.artist).joinToString(" · ") },
                )
            }
        }.distinctUntilChanged().debounce(800).collect { GlanceWidget.update(app, it) }
    }

    /** Copies the picked image, cropped to the screen plus room for parallax, so it survives the picker's grant. */
    fun setBackground(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val metrics = app.resources.displayMetrics
                    val w = metrics.widthPixels
                    val h = (metrics.heightPixels * 1.3f).toInt()
                    val source = decode(uri, max(w, h))
                    val cropped = centerCrop(source, w, h)
                    backgroundFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }.isSuccess
            }
            if (ok) updateSettings { it.copy(backgroundVersion = System.currentTimeMillis()) }
            else toast("Couldn't use that picture")
        }
    }

    fun clearBackground() {
        backgroundFile.delete()
        updateSettings { it.copy(backgroundVersion = 0) }
    }

    private fun decode(uri: Uri, targetMax: Int): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > targetMax * 2) decoder.setTargetSampleSize(longest / (targetMax * 2).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        }

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (w / scale).toInt().coerceAtMost(src.width)
        val sh = (h / scale).toInt().coerceAtMost(src.height)
        val region = Bitmap.createBitmap(src, (src.width - sw) / 2, (src.height - sh) / 2, sw, sh)
        return Bitmap.createScaledBitmap(region, w, h, true)
    }

    // --- Backup ---

    fun exportLayout(uri: Uri) {
        viewModelScope.launch {
            val json = JSONObject()
                .put("format", 1)
                .put("tiles", LayoutStore.encode(tiles.value.orEmpty().filter { it.kind != TileKind.WIDGET && it.kind != TileKind.SHORTCUT }))
                .put("settings", SettingsRepository.toJson(settings.value))
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri)!!.use { it.write(json.toString(2).encodeToByteArray()) } }.isSuccess
            }
            toast(if (ok) "Layout exported" else "Export failed")
        }
    }

    fun importLayout(uri: Uri) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val json = JSONObject(app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                    LayoutStore.decode(json.getJSONArray("tiles")) to json.optJSONObject("settings")
                }.getOrNull()
            }
            if (parsed == null) {
                toast("That file isn't a Tile Launcher backup")
                return@launch
            }
            val (imported, settingsJson) = parsed
            tiles.value.orEmpty().mapNotNull { it.widgetId }.forEach { widgetHost.deleteAppWidgetId(it) }
            layout.set(LayoutOps.normalizeFolders(imported))
            settingsJson?.let { o -> updateSettings { SettingsRepository.fromJson(o, it) } }
            toast("Layout imported")
        }
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    override fun onCleared() {
        repository.stop()
        liveTiles.close()
    }

}
