package com.tilelauncher.ui.start

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.IconLoader
import com.tilelauncher.data.LauncherSettings
import com.tilelauncher.data.StartItem
import com.tilelauncher.data.TileKind
import com.tilelauncher.data.ShortcutRef
import com.tilelauncher.data.TileSize
import com.tilelauncher.ui.tile.ShortcutMenuActions
import com.tilelauncher.ui.tile.ShortcutMenuItems
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tilelauncher.live.LiveContent
import com.tilelauncher.live.LiveTiles
import com.tilelauncher.live.TileFace
import com.tilelauncher.ui.theme.LocalMetro
import com.tilelauncher.ui.tile.AppTileFront
import com.tilelauncher.ui.tile.Backdrop
import com.tilelauncher.ui.tile.FolderTileContent
import com.tilelauncher.ui.tile.TileFaceView
import com.tilelauncher.ui.tile.TileFrame
import com.tilelauncher.ui.tile.TileLabel
import com.tilelauncher.ui.tile.TileTransform
import com.tilelauncher.ui.tile.rememberFlipper
import com.tilelauncher.ui.tile.rememberTileIcon
import com.tilelauncher.widgets.WidgetView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive

private val ScreenMargin = 12.dp
private val TileGap = 6.dp
private val PanelPadding = 12.dp
private val PanelTitle = 44.dp

interface StartActions {
    fun launch(app: AppEntry, bounds: Rect?)
    fun launchWeather()
    fun unpin(tileId: Long)
    fun resize(tileId: Long, size: TileSize)
    fun reorder(order: List<Long>)
    fun dropOnto(draggedId: Long, targetId: Long)
    fun removeFromFolder(childId: Long)
    fun rename(tileId: Long, title: String)
    fun setLive(tileId: Long, enabled: Boolean)
    fun openAppList()
    fun openSettings()
    fun addWeather()
    fun addWidget()
    fun addMe()
    fun launchShortcut(ref: ShortcutRef, bounds: Rect?)
}

/** Long-lived helpers tiles need, bundled to keep signatures short. */
class TileEnv(
    val iconLoader: IconLoader,
    val liveTiles: LiveTiles,
    val widgetHost: AppWidgetHost,
    val widgetManager: AppWidgetManager,
    val shortcutIcon: (ShortcutRef) -> ImageBitmap?,
    val shortcutMenu: ShortcutMenuActions,
)

@Composable
fun StartScreen(
    items: List<StartItem>,
    settings: LauncherSettings,
    background: ImageBitmap?,
    badges: Map<String, Int>,
    scrollState: ScrollState,
    env: TileEnv,
    /** Start is on screen and the activity is resumed; live tiles only animate then. */
    visible: Boolean,
    lifecycleEvents: SharedFlow<Lifecycle.Event>,
    /** Shared with the app list; owned by the activity, which plays it on resume. */
    turnstile: Turnstile,
    /** Plays the turnstile exit (unless animations are off) and runs [launch] partway through. */
    launchAnimated: (origin: Any, launch: () -> Unit) -> Unit,
    actions: StartActions,
) {
    val metro = LocalMetro.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val controller = remember { StartController() }
    val gridCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var renameId by remember { mutableStateOf<Long?>(null) }
    var moreMenuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = controller.editingId != null || controller.openFolderId != null) { controller.exitEdit() }

    LaunchedEffect(Unit) {
        lifecycleEvents.collect { if (it == Lifecycle.Event.ON_STOP) controller.exitEdit() }
    }
    // The saved layout caught up with the drag; drop the local order.
    LaunchedEffect(items) {
        if (controller.dragId == null) controller.workingOrder = null
    }

    val displayItems = controller.ordered(items)
    val columns = settings.columns
    val labelSize = if (columns == 6) 11.sp else 13.sp

    fun onTileTap(item: StartItem, bounds: Rect?) {
        if (controller.editingId != null) {
            controller.editingId = item.tile.id
            return
        }
        when (item) {
            is StartItem.App -> launchAnimated(item.tile.id) { actions.launch(item.app, bounds) }
            is StartItem.Folder -> controller.openFolderId = if (controller.openFolderId == item.tile.id) null else item.tile.id
            is StartItem.Weather -> launchAnimated(item.tile.id) { actions.launchWeather() }
            is StartItem.Shortcut -> launchAnimated(item.tile.id) { actions.launchShortcut(item.ref, bounds) }
            is StartItem.Widget, is StartItem.Me -> Unit
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val viewportPx = constraints.maxHeight.toFloat()
        val marginPx = with(density) { ScreenMargin.toPx() }
        val gapPx = with(density) { TileGap.toPx() }
        val cellPx = (widthPx - 2 * marginPx - (columns - 1) * gapPx) / columns
        val openFolder = displayItems.firstOrNull { it.tile.id == controller.openFolderId } as? StartItem.Folder
        val geometry = remember(displayItems.map { it.tile }, openFolder?.children?.map { it.tile }, columns, cellPx) {
            computeGeometry(
                items = displayItems,
                columns = columns,
                cell = cellPx,
                gap = gapPx,
                margin = marginPx,
                openFolder = openFolder,
                panelPadding = with(density) { PanelPadding.toPx() },
                panelTitle = with(density) { PanelTitle.toPx() },
            )
        }
        controller.bind(geometry, displayItems, actions, haptics)

        val backdrop = remember(background, settings.parallax, viewportPx) {
            background?.let { img ->
                val extra = (img.height - viewportPx).coerceAtLeast(0f)
                Backdrop(img) { if (settings.parallax) (scrollState.value * 0.25f).coerceAtMost(extra) else 0f }
            }
        }
        val live = visible && controller.editingId == null && turnstile.isIdle

        // Grouping needs a short rest over the target, so tiles sliding under the finger mid-reorder don't merge.
        LaunchedEffect(controller.folderCandidateId) {
            val candidate = controller.folderCandidateId ?: return@LaunchedEffect
            delay(450)
            controller.armFolder(candidate)
        }

        // Auto-scroll while dragging near the top or bottom edge.
        LaunchedEffect(controller.dragId != null) {
            if (controller.dragId == null) return@LaunchedEffect
            val edge = with(density) { 96.dp.toPx() }
            while (isActive && controller.dragId != null) {
                withFrameNanos { }
                val coords = gridCoords[0]?.takeIf { it.isAttached } ?: continue
                val y = coords.localToWindow(controller.finger).y
                val speed = when {
                    y < edge -> -(edge - y) / edge * 28f
                    y > viewportPx - edge -> (y - (viewportPx - edge)) / edge * 28f
                    else -> 0f
                }
                if (speed != 0f) controller.scrolledBy(scrollState.scrollBy(speed))
            }
        }

        val topInset = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toFloat() }
        val chromePx = with(density) { (24 + 80).dp.toPx() }
        val gridHeightPx = maxOf(geometry.height, viewportPx - topInset - bottomInset - chromePx)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = controller.dragId == null)
                .padding(horizontal = ScreenMargin),
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { gridHeightPx.toDp() })
                    .onGloballyPositioned { gridCoords[0] = it }
                    .pointerInput(Unit) { detectTapGestures(onTap = { controller.tapEmpty() }) }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = controller::dragStart,
                            onDrag = { change, amount ->
                                change.consume()
                                controller.dragBy(amount)
                            },
                            onDragEnd = controller::dragEnd,
                            onDragCancel = controller::dragCancel,
                        )
                    },
            ) {
                val gridTop = gridCoords[0]?.takeIf { it.isAttached }?.positionInWindow()?.y ?: 0f

                openFolder?.let { folder ->
                    FolderPanel(folder, geometry, labelSize, env, backdrop, controller, actions, onRename = { renameId = folder.tile.id })
                }

                displayItems.filter { it.tile.id in geometry.rects }.forEach { item ->
                    key(item.tile.id) {
                        val rect = geometry.rects.getValue(item.tile.id)
                        val isDragged = controller.dragId == item.tile.id
                        val target = rect.topLeft.round()
                        val position = remember { Animatable(target, IntOffset.VectorConverter) }
                        LaunchedEffect(isDragged) {
                            if (isDragged) snapshotFlow { controller.dragTopLeft }.collect { position.snapTo(it.round()) }
                        }
                        LaunchedEffect(target, isDragged) {
                            if (!isDragged) position.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                        }

                        val editing = controller.editingId != null
                        val selected = controller.editingId == item.tile.id
                        val folderTarget = controller.folderTargetId == item.tile.id
                        val editScale by animateFloatAsState(
                            when {
                                folderTarget -> 1.08f
                                isDragged -> 1.04f
                                editing && !selected -> 0.9f
                                else -> 1f
                            },
                            label = "editScale",
                        )
                        val editAlpha by animateFloatAsState(if (editing && !selected && !folderTarget) 0.6f else 1f, label = "editAlpha")
                        val turn = if (turnstile.isIdle) TileTransform() else turnstile.transform(
                            key = item.tile.id,
                            screenRow = ((gridTop + rect.top) / geometry.step).toInt(),
                            rightHalf = rect.center.x > widthPx / 2,
                            pivotFractionX = -(rect.left + marginPx) / rect.width,
                        )
                        val transform = turn.copy(scale = turn.scale * editScale, alpha = turn.alpha * editAlpha)

                        Box(
                            Modifier
                                .offset { position.value }
                                .zIndex(if (isDragged) 2f else if (selected) 1f else 0f),
                        ) {
                            StartTile(
                                item = item,
                                widthDp = with(density) { rect.width.toDp() },
                                heightDp = with(density) { rect.height.toDp() },
                                transform = transform,
                                settings = settings,
                                badges = badges,
                                env = env,
                                backdrop = backdrop,
                                live = live,
                                editing = editing,
                                labelSize = labelSize,
                                onTap = { bounds -> onTileTap(item, bounds) },
                            )
                        }
                    }
                }

                // Edit-mode corner buttons on the selected tile.
                val selected = displayItems.firstOrNull { it.tile.id == controller.editingId }
                val selectedRect = selected?.let { geometry.rects[it.tile.id] }
                if (selected != null && selectedRect != null && controller.dragId == null) {
                    val half = with(density) { (EditButtonSize / 2).toPx() }
                    val maxX = widthPx - 2 * marginPx + marginPx - 2 * half
                    fun at(x: Float, y: Float) = IntOffset((x - half).coerceIn(-marginPx, maxX).toInt(), (y - half).toInt())
                    val sizes = selected.tile.kind.sizes
                    val nextSize = sizes[(sizes.indexOf(selected.tile.size) + 1).mod(sizes.size)]
                    val grows = nextSize.w * nextSize.h > selected.tile.size.w * selected.tile.size.h

                    EditButton(
                        EditGlyph.UNPIN,
                        onClick = {
                            controller.editingId = null
                            actions.unpin(selected.tile.id)
                        },
                        Modifier.offset { at(selectedRect.right, selectedRect.top) }.zIndex(3f),
                    )
                    EditButton(
                        if (grows) EditGlyph.GROW else EditGlyph.SHRINK,
                        onClick = { actions.resize(selected.tile.id, nextSize) },
                        Modifier.offset { at(selectedRect.right, selectedRect.bottom) }.zIndex(3f),
                    )
                    if (selected is StartItem.App || selected is StartItem.Folder) {
                        Box(Modifier.offset { at(selectedRect.left, selectedRect.bottom) }.zIndex(3f)) {
                            EditButton(EditGlyph.MORE, onClick = { moreMenuOpen = true })
                            DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                                if (selected is StartItem.App) {
                                    val on = selected.tile.liveEnabled
                                    DropdownMenuItem(
                                        text = { Text(if (on) "turn live tile off" else "turn live tile on") },
                                        onClick = {
                                            moreMenuOpen = false
                                            actions.setLive(selected.tile.id, !on)
                                        },
                                    )
                                    ShortcutMenuItems(selected.app, env.shortcutMenu, onDone = { moreMenuOpen = false })
                                }
                                if (selected is StartItem.Folder) {
                                    DropdownMenuItem(text = { Text("rename") }, onClick = {
                                        moreMenuOpen = false
                                        renameId = selected.tile.id
                                    })
                                }
                            }
                        }
                    }
                }

                // Long press on empty space.
                controller.menuAt?.let { at ->
                    Box(Modifier.offset { at.round() }.size(1.dp)) {
                        DropdownMenu(expanded = true, onDismissRequest = { controller.menuAt = null }) {
                            if (items.none { it.tile.kind == TileKind.ME }) {
                                DropdownMenuItem(text = { Text("add me tile") }, onClick = {
                                    controller.menuAt = null
                                    actions.addMe()
                                })
                            }
                            if (items.none { it.tile.kind == TileKind.WEATHER }) {
                                DropdownMenuItem(text = { Text("add weather tile") }, onClick = {
                                    controller.menuAt = null
                                    actions.addWeather()
                                })
                            }
                            DropdownMenuItem(text = { Text("add widget") }, onClick = {
                                controller.menuAt = null
                                actions.addWidget()
                            })
                            DropdownMenuItem(text = { Text("launcher settings") }, onClick = {
                                controller.menuAt = null
                                actions.openSettings()
                            })
                        }
                    }
                }
            }
            AllAppsArrow(actions::openAppList, Modifier.align(Alignment.End).padding(top = 20.dp, bottom = 16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }

        // Keeps status bar icons readable over tiles scrolled beneath them.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(metro.background),
        )
    }

    renameId?.let { id ->
        val current = (items.firstOrNull { it.tile.id == id } as? StartItem.Folder)?.tile?.title.orEmpty()
        RenameDialog(
            initial = current,
            onDone = {
                actions.rename(id, it)
                renameId = null
            },
            onDismiss = { renameId = null },
        )
    }
}

private fun LiveContent?.sequence(): List<TileFace?> = when {
    this == null || faces.isEmpty() -> listOf(null)
    replacesFront -> faces
    else -> faces.flatMap { listOf(null, it) }
}

@Composable
private fun StartTile(
    item: StartItem,
    widthDp: Dp,
    heightDp: Dp,
    transform: TileTransform,
    settings: LauncherSettings,
    badges: Map<String, Int>,
    env: TileEnv,
    backdrop: Backdrop?,
    live: Boolean,
    editing: Boolean,
    labelSize: TextUnit,
    onTap: (Rect?) -> Unit,
) {
    val accent = LocalMetro.current.accent
    val size = item.tile.size
    when (item) {
        is StartItem.App -> {
            val liveOn = settings.liveTiles && item.tile.liveEnabled && size != TileSize.SMALL
            val flow = remember(item.app.key, liveOn) { if (liveOn) env.liveTiles.contentFor(item.app) else flowOf(null) }
            val content by flow.collectAsState(null)
            val faces = remember(content) { content.sequence() }
            val flipper = rememberFlipper(faces.size, live)
            val icon = rememberTileIcon(item.app, env.iconLoader)
            val labelFlow = remember(item.app.key) { env.liveTiles.labelFor(item.app) }
            val labelOverride by labelFlow.collectAsState(null)
            TileFrame(widthDp, heightDp, accent, backdrop, transform, { flipper.rotation.value }, !editing, onTap) {
                val face = faces.getOrNull(flipper.index)
                if (face == null) AppTileFront(icon, size, widthDp, heightDp, badges[item.app.packageName] ?: 0)
                else TileFaceView(face, size, heightDp, live)
                if (size != TileSize.SMALL) TileLabel(labelOverride ?: item.app.label, labelSize)
            }
        }
        is StartItem.Folder -> {
            TileFrame(widthDp, heightDp, accent, backdrop, transform, { 0f }, !editing, onTap) {
                FolderTileContent(item.children, size, env.iconLoader)
                if (size != TileSize.SMALL) TileLabel(item.tile.title ?: "folder", labelSize)
            }
        }
        is StartItem.Weather -> {
            val flow = remember(settings.liveTiles) { if (settings.liveTiles) env.liveTiles.weather else flowOf(null) }
            val content by flow.collectAsState(null)
            val faces = remember(content) { content.sequence() }
            val flipper = rememberFlipper(faces.size, live)
            TileFrame(widthDp, heightDp, accent, backdrop, transform, { flipper.rotation.value }, !editing, onTap) {
                val face = faces.getOrNull(flipper.index)
                if (face != null) {
                    TileFaceView(face, size, heightDp, live)
                } else {
                    Text(
                        "weather",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (size != TileSize.SMALL) TileLabel("weather", labelSize)
            }
        }
        is StartItem.Shortcut -> {
            val icon by produceState<ImageBitmap?>(null, item.ref) {
                value = withContext(Dispatchers.IO) { env.shortcutIcon(item.ref) }
            }
            TileFrame(widthDp, heightDp, accent, backdrop, transform, { 0f }, !editing, onTap) {
                val shortSide = minOf(widthDp.value, heightDp.value)
                icon?.let { Image(it, null, Modifier.align(Alignment.Center).size((shortSide * if (size == TileSize.SMALL) 0.6f else 0.42f).dp)) }
                if (size != TileSize.SMALL) TileLabel(item.label, labelSize)
            }
        }
        is StartItem.Me -> {
            val flow = remember(settings.liveTiles) { if (settings.liveTiles) env.liveTiles.me else flowOf(null) }
            val content by flow.collectAsState(null)
            val faces = remember(content) { content.sequence() }
            val flipper = rememberFlipper(faces.size, live)
            val scope = rememberCoroutineScope()
            // Tapping Me flips to the next face straight away.
            val tap: (Rect?) -> Unit = { bounds -> if (editing) onTap(bounds) else scope.launch { flipper.advance(faces.size) } }
            TileFrame(widthDp, heightDp, accent, backdrop, transform, { flipper.rotation.value }, !editing, tap) {
                val face = faces.getOrNull(flipper.index)
                if (face != null) {
                    TileFaceView(face, size, heightDp, live)
                } else {
                    Text("me", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))
                }
                if (size != TileSize.SMALL) TileLabel("me", labelSize)
            }
        }
        is StartItem.Widget -> {
            TileFrame(widthDp, heightDp, Color.Transparent, null, transform, { 0f }, false, onTap) {
                item.tile.widgetId?.let { WidgetView(env.widgetHost, env.widgetManager, it, widthDp, heightDp) }
            }
        }
    }
}

/** An open folder: a full-width band below the folder's row holding its tiles. */
@Composable
private fun FolderPanel(
    folder: StartItem.Folder,
    geometry: GridGeometry,
    labelSize: TextUnit,
    env: TileEnv,
    backdrop: Backdrop?,
    controller: StartController,
    actions: StartActions,
    onRename: () -> Unit,
) {
    val metro = LocalMetro.current
    val density = LocalDensity.current
    val panel = geometry.panel ?: return
    Box(
        Modifier
            .offset { panel.topLeft.round() }
            .size(with(density) { panel.width.toDp() }, with(density) { panel.height.toDp() })
            .background(metro.chrome),
    )
    Text(
        folder.tile.title ?: "folder",
        fontSize = 24.sp,
        fontWeight = FontWeight.Light,
        modifier = Modifier
            .offset { IntOffset(0, (panel.top + with(density) { PanelPadding.toPx() }).toInt()) }
            .pointerInput(Unit) { detectTapGestures(onTap = { onRename() }) },
    )
    folder.children.filter { it.tile.id in geometry.childRects }.forEach { child ->
        val rect = geometry.childRects.getValue(child.tile.id)
        key(child.tile.id) {
            val w = with(density) { rect.width.toDp() }
            val h = with(density) { rect.height.toDp() }
            Box(Modifier.offset { rect.topLeft.round() }) {
                val icon = rememberTileIcon(child.app, env.iconLoader)
                TileFrame(w, h, metro.accent, backdrop, TileTransform(), { 0f }, true, { bounds -> actions.launch(child.app, bounds) }) {
                    AppTileFront(icon, child.tile.size, w, h, 0)
                    if (child.tile.size != TileSize.SMALL) TileLabel(child.app.label, labelSize)
                }
                DropdownMenu(expanded = controller.childMenuId == child.tile.id, onDismissRequest = { controller.childMenuId = null }) {
                    DropdownMenuItem(text = { Text("remove from folder") }, onClick = {
                        controller.childMenuId = null
                        actions.removeFromFolder(child.tile.id)
                    })
                    DropdownMenuItem(text = { Text("unpin") }, onClick = {
                        controller.childMenuId = null
                        actions.unpin(child.tile.id)
                    })
                }
            }
        }
    }
}
