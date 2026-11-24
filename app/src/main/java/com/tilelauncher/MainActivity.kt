package com.tilelauncher

import android.Manifest
import android.app.ActivityOptions
import androidx.compose.ui.platform.LocalView
import com.tilelauncher.ui.applist.rowKey
import com.tilelauncher.ui.start.Turnstile
import kotlinx.coroutines.delay
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.ShortcutRef
import com.tilelauncher.data.TileSize
import com.tilelauncher.ui.tile.ShortcutMenuActions
import com.tilelauncher.ui.applist.AppListScreen
import com.tilelauncher.ui.settings.PAGE_PERMISSIONS
import com.tilelauncher.ui.settings.SettingsScreen
import com.tilelauncher.ui.settings.WelcomeScreen
import com.tilelauncher.ui.start.StartActions
import com.tilelauncher.ui.start.StartScreen
import com.tilelauncher.ui.start.TileEnv
import com.tilelauncher.ui.theme.MetroTheme
import com.tilelauncher.ui.theme.metroColors
import com.tilelauncher.ui.theme.resolveLight
import com.tilelauncher.widgets.WidgetPicker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color as ComposeColor

private const val PAGE_START = 0
private const val PAGE_APPS = 1
private const val REQUEST_CONFIGURE_WIDGET = 41

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    /** Home intents; the value says whether the launcher was already in front when it arrived. */
    private val homePresses = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val lifecycleEvents = MutableSharedFlow<Lifecycle.Event>(extraBufferCapacity = 8)
    private val resumed = MutableStateFlow(false)
    private val resumeCount = MutableStateFlow(0)
    private var permissionPrint = ""

    /** Widget being added while the bind/configure screens are up. */
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (id, info) = pendingWidget ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureOrAddWidget(id, info) else abandonWidget(id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySystemBars(light = false)
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            lifecycleEvents.tryEmit(event)
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed.value = true
                    resumeCount.value++
                    refreshIfPermissionsChanged()
                    viewModel.onLauncherResumed()
                }
                Lifecycle.Event.ON_PAUSE -> resumed.value = false
                else -> Unit
            }
        })
        setContent {
            LauncherRoot(this, viewModel, homePresses, lifecycleEvents, resumed, resumeCount)
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { viewModel.widgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { viewModel.widgetHost.stopListening() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // onNewIntent runs before onResume, so this is false when coming back from an app.
            homePresses.tryEmit(resumed.value)
        }
    }

    fun applySystemBars(light: Boolean) {
        val style = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /** Live-tile sources re-read only when a permission actually changed, not on every return home. */
    private fun refreshIfPermissionsChanged() {
        val print = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
        ).joinToString { (ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED).toString() }
        if (print != permissionPrint) {
            permissionPrint = print
            viewModel.refreshLiveTiles()
        }
    }

    fun requestDefaultHome(launcher: (Intent) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launcher(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    // --- Widgets: allocate → bind (may ask the user) → configure (if the widget has a config screen) → tile ---

    fun startAddWidget(info: AppWidgetProviderInfo) {
        val id = viewModel.widgetHost.allocateAppWidgetId()
        pendingWidget = id to info
        if (viewModel.widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAddWidget(id, info)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            )
        }
    }

    private fun configureOrAddWidget(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure == null) {
            finishWidget(id, info)
            return
        }
        runCatching {
            viewModel.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
        }.onFailure { finishWidget(id, info) }
    }

    private fun finishWidget(id: Int, info: AppWidgetProviderInfo) {
        pendingWidget = null
        viewModel.addWidgetTile(id, info)
    }

    private fun abandonWidget(id: Int) {
        pendingWidget = null
        viewModel.deleteWidget(id)
    }

    @Deprecated("Widget configuration still reports through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val (id, info) = pendingWidget ?: return
        if (resultCode == RESULT_OK) finishWidget(id, info) else abandonWidget(id)
    }
}

@Composable
private fun LauncherRoot(
    activity: MainActivity,
    viewModel: LauncherViewModel,
    homePresses: SharedFlow<Boolean>,
    lifecycleEvents: SharedFlow<Lifecycle.Event>,
    resumed: StateFlow<Boolean>,
    resumeCount: StateFlow<Int>,
) {
    val settings by viewModel.settings.collectAsState()
    val light = resolveLight(settings.theme)
    LaunchedEffect(light) { activity.applySystemBars(light) }
    val colors = metroColors(ComposeColor(settings.accent), light)

    MetroTheme(colors) {
        val items by viewModel.startItems.collectAsState()
        val apps by viewModel.apps.collectAsState()
        val pinned by viewModel.pinnedKeys.collectAsState()
        val badges by viewModel.liveTiles.badges.collectAsState()
        val background by viewModel.background.collectAsState()
        val iconPacks by viewModel.iconPacks.collectAsState()
        val isResumed by resumed.collectAsState()
        val resumes by resumeCount.collectAsState()

        val pager = rememberPagerState { 2 }
        val startScroll = rememberScrollState()
        val appListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        var query by remember { mutableStateOf("") }
        var settingsPage by remember { mutableStateOf<Int?>(null) }
        var widgetChoices by remember { mutableStateOf<List<AppWidgetProviderInfo>?>(null) }

        val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::setBackground)
        }
        val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(viewModel::exportLayout)
        }
        val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importLayout)
        }
        val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

        val view = LocalView.current
        val turnstile = remember { Turnstile() }
        val animationsOff = remember {
            Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
        // Tiles hide when the launcher leaves the screen and fly in on resume. Resume (not start)
        // is when Home/back/Recents actually lands here; on start the closing app still covers us.
        LaunchedEffect(Unit) {
            lifecycleEvents.collect { event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> if (animationsOff) turnstile.reset() else turnstile.prepareFlyIn()
                    Lifecycle.Event.ON_RESUME -> if (!turnstile.isIdle) scope.launch { turnstile.flyIn() }
                    else -> Unit
                }
            }
        }

        /** The app window grows out of the tapped tile or row. */
        fun openOptions(bounds: Rect?): Bundle? = bounds?.let {
            val origin = IntArray(2).also(view::getLocationInWindow)
            ActivityOptions.makeScaleUpAnimation(view, it.left - origin[0], it.top - origin[1], it.width(), it.height()).toBundle()
        }

        fun launchAnimated(origin: Any, launch: () -> Unit) {
            if (animationsOff || !turnstile.isIdle) {
                launch()
                return
            }
            scope.launch {
                turnstile.out(origin, launch)
                // The app should cover us by now; still in front means the launch failed, so come back.
                delay(1_200)
                if (resumed.value && turnstile.mode == Turnstile.Mode.OUT) turnstile.flyIn()
            }
        }

        // Home while already home: close overlays, then the app list, then scroll Start to the top.
        LaunchedEffect(Unit) {
            homePresses.collect { wasInFront ->
                if (!wasInFront) {
                    // Back from an app: land on Start without sliding the pager; the fly-in plays on resume.
                    settingsPage = null
                    widgetChoices = null
                    if (pager.currentPage != PAGE_START) pager.scrollToPage(PAGE_START)
                    return@collect
                }
                when {
                    settingsPage != null || widgetChoices != null -> {
                        settingsPage = null
                        widgetChoices = null
                    }
                    pager.currentPage != PAGE_START -> pager.animateScrollToPage(PAGE_START)
                    else -> startScroll.animateScrollTo(0)
                }
            }
        }
        // Leaving the app list resets it, like WP.
        LaunchedEffect(pager.settledPage) {
            if (pager.settledPage == PAGE_START) {
                focusManager.clearFocus()
                query = ""
                appListState.scrollToItem(0)
            }
        }

        // The launcher must never finish on Back; later handlers win while they're enabled.
        BackHandler { }
        BackHandler(enabled = pager.currentPage == PAGE_APPS) {
            scope.launch { pager.animateScrollToPage(PAGE_START) }
        }
        BackHandler(enabled = settingsPage != null) { settingsPage = null }

        val shortcutMenu = remember {
            ShortcutMenuActions(
                load = viewModel::shortcutsFor,
                launch = { viewModel.launchShortcut(it.ref, null, null) },
                pin = viewModel::pinShortcut,
            )
        }
        val env = remember {
            TileEnv(
                iconLoader = viewModel.iconLoader,
                liveTiles = viewModel.liveTiles,
                widgetHost = viewModel.widgetHost,
                widgetManager = viewModel.widgetManager,
                shortcutIcon = viewModel::shortcutIcon,
                shortcutMenu = shortcutMenu,
            )
        }
        val actions = remember {
            object : StartActions {
                override fun launch(app: AppEntry, bounds: Rect?) = viewModel.launch(app, bounds, openOptions(bounds))
                override fun launchWeather() = viewModel.launchWeather()
                override fun unpin(tileId: Long) = viewModel.unpin(tileId)
                override fun resize(tileId: Long, size: TileSize) = viewModel.resize(tileId, size)
                override fun reorder(order: List<Long>) = viewModel.reorder(order)
                override fun dropOnto(draggedId: Long, targetId: Long) = viewModel.dropOnto(draggedId, targetId)
                override fun removeFromFolder(childId: Long) = viewModel.removeFromFolder(childId)
                override fun rename(tileId: Long, title: String) = viewModel.rename(tileId, title)
                override fun setLive(tileId: Long, enabled: Boolean) = viewModel.setLive(tileId, enabled)
                override fun openAppList() {
                    scope.launch { pager.animateScrollToPage(PAGE_APPS) }
                }
                override fun openSettings() {
                    settingsPage = 0
                }
                override fun addWeather() = viewModel.addWeather()
                override fun addMe() = viewModel.addMe()
                override fun launchShortcut(ref: ShortcutRef, bounds: Rect?) =
                    viewModel.launchShortcut(ref, bounds, openOptions(bounds))
                override fun addWidget() {
                    widgetChoices = viewModel.installedWidgets()
                }
            }
        }

        Box(Modifier.fillMaxSize().background(colors.background)) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                // Keyboard-mode focus must not land on the off-screen page, or the pager scrolls to it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusProperties { onEnter = { if (page != pager.currentPage) cancelFocusChange() } }
                        .focusGroup(),
                ) {
                    when (page) {
                        PAGE_START -> StartScreen(
                            items = items,
                            settings = settings,
                            background = background,
                            badges = badges,
                            scrollState = startScroll,
                            env = env,
                            visible = isResumed && pager.currentPage == PAGE_START && settingsPage == null && settings.onboarded,
                            lifecycleEvents = lifecycleEvents,
                            turnstile = turnstile,
                            launchAnimated = ::launchAnimated,
                            actions = actions,
                        )
                        PAGE_APPS -> AppListScreen(
                            apps = apps,
                            pinned = pinned,
                            query = query,
                            onQueryChange = { query = it },
                            listState = appListState,
                            iconLoader = viewModel.iconLoader,
                            turnstile = turnstile,
                            onLaunch = { app, bounds ->
                                launchAnimated(rowKey(app)) { viewModel.launch(app, bounds, openOptions(bounds)) }
                            },
                            onPin = viewModel::pin,
                            onAppInfo = viewModel::openAppInfo,
                            onUninstall = viewModel::uninstall,
                            onOpenSettings = { settingsPage = 0 },
                            shortcutMenu = shortcutMenu,
                        )
                    }
                }
            }

            settingsPage?.let { page ->
                SettingsScreen(
                    settings = settings,
                    onUpdate = viewModel::updateSettings,
                    iconPacks = iconPacks,
                    initialPage = page,
                    resumeCount = resumes,
                    onPickBackground = {
                        pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClearBackground = viewModel::clearBackground,
                    onExport = { exportFile.launch("tile-launcher-layout.json") },
                    onImport = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onPermissionsChanged = viewModel::refreshLiveTiles,
                )
            }

            if (!settings.onboarded) {
                WelcomeScreen(
                    onMakeDefault = { activity.requestDefaultHome(roleRequest::launch) },
                    onChoosePermissions = {
                        viewModel.updateSettings { it.copy(onboarded = true) }
                        settingsPage = PAGE_PERMISSIONS
                    },
                    onDone = { viewModel.updateSettings { it.copy(onboarded = true) } },
                )
            }

            widgetChoices?.let { choices ->
                WidgetPicker(
                    providers = choices,
                    onPick = { info ->
                        widgetChoices = null
                        activity.startAddWidget(info)
                    },
                    onDismiss = { widgetChoices = null },
                )
            }
        }
    }
}
