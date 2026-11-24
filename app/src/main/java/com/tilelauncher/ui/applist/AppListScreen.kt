package com.tilelauncher.ui.applist

import android.graphics.Rect
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.tilelauncher.ui.start.Turnstile
import com.tilelauncher.ui.tile.ShortcutMenuActions
import com.tilelauncher.ui.tile.ShortcutMenuItems
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.AppKey
import com.tilelauncher.data.IconLoader
import com.tilelauncher.ui.theme.LocalMetro
import com.tilelauncher.ui.theme.OnTile
import com.tilelauncher.ui.tile.rememberTileIcon
import com.tilelauncher.ui.theme.SemiLight
import com.tilelauncher.ui.theme.Selawik
import kotlinx.coroutines.launch

private const val JUMP_LETTERS = "#abcdefghijklmnopqrstuvwxyz"
private val LetterTileSize = 52.dp

private fun groupLetter(label: String): Char {
    val c = label.firstOrNull()?.lowercaseChar() ?: return '#'
    return if (c in 'a'..'z') c else '#'
}

/** Key of an app's row; also the turnstile origin when launching from the list. */
fun rowKey(app: AppEntry): String = "${app.key.component.flattenToString()}#${app.key.userSerial}"

private sealed interface ListRow {
    /** Stable, saveable key for lazy items and the turnstile origin. */
    val key: String

    data class Header(val letter: Char) : ListRow {
        override val key get() = "h:$letter"
    }

    data class App(val app: AppEntry) : ListRow {
        override val key get() = rowKey(app)
    }
}

@Composable
fun AppListScreen(
    apps: List<AppEntry>,
    pinned: Set<AppKey>,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    iconLoader: IconLoader,
    /** Shared with Start: rows swing out on launch and fly back in on return. */
    turnstile: Turnstile,
    /** [sourceBounds] is the row's icon square in window coordinates, for the open animation. */
    onLaunch: (app: AppEntry, sourceBounds: Rect?) -> Unit,
    onPin: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
    shortcutMenu: ShortcutMenuActions,
) {
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }

    val searching = query.isNotBlank()
    val rows: List<ListRow> = remember(apps, query) {
        if (searching) {
            apps.filter { it.label.contains(query.trim(), ignoreCase = true) }.map { ListRow.App(it) }
        } else {
            // '#' sorts first, then a..z; the collator already ordered apps within each group.
            apps.groupBy { groupLetter(it.label) }
                .toSortedMap(compareBy { JUMP_LETTERS.indexOf(it) })
                .flatMap { (letter, group) -> listOf(ListRow.Header(letter)) + group.map { ListRow.App(it) } }
        }
    }
    val headerIndex: Map<Char, Int> = remember(rows) {
        rows.withIndex().mapNotNull { (i, r) -> (r as? ListRow.Header)?.let { it.letter to i } }.toMap()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            ) {
                SearchBox(
                    query = query,
                    onQueryChange = onQueryChange,
                    onGo = { rows.firstNotNullOfOrNull { it as? ListRow.App }?.let { onLaunch(it.app, null) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                SettingsDots(onOpenSettings)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                    // Only read the scroll position while the turnstile runs, so scrolling doesn't recompose rows.
                    val turn = if (turnstile.isIdle) null else turnstile.transform(
                        key = row.key,
                        screenRow = index - listState.firstVisibleItemIndex,
                        rightHalf = false,
                        pivotFractionX = 0f,
                    )
                    Box(
                        Modifier.graphicsLayer {
                            if (turn != null) {
                                rotationY = turn.rotationY
                                alpha = turn.alpha
                                transformOrigin = TransformOrigin(turn.pivotFractionX, 0.5f)
                                cameraDistance = 12 * density
                            }
                        },
                    ) {
                        when (row) {
                            is ListRow.Header -> LetterTile(
                                letter = row.letter,
                                onClick = { jumpOpen = true },
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                            is ListRow.App -> AppRow(
                                app = row.app,
                                isPinned = row.app.key in pinned,
                                iconLoader = iconLoader,
                                onLaunch = onLaunch,
                                onPin = onPin,
                                onAppInfo = onAppInfo,
                                onUninstall = onUninstall,
                                shortcutMenu = shortcutMenu,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp).windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }
        }

        if (jumpOpen) {
            BackHandler { jumpOpen = false }
            JumpGrid(
                available = headerIndex.keys,
                onPick = { letter ->
                    jumpOpen = false
                    headerIndex[letter]?.let { scope.launch { listState.scrollToItem(it) } }
                },
                onDismiss = { jumpOpen = false },
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val metro = LocalMetro.current
    // WP text boxes: light grey at rest, white when focused, black text. On the light theme
    // the box needs an outline (accent when focused) to show against the white page.
    val fill = when {
        focused -> Color.White
        metro.isLight -> metro.chrome
        else -> Color(0xFFCCCCCC)
    }
    val outline = when {
        !metro.isLight -> Color.Transparent
        focused -> metro.accent
        else -> Color(0xFFBBBBBB)
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Selawik, fontSize = 18.sp, color = Color.Black),
        cursorBrush = SolidColor(LocalMetro.current.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(fill)
                    .border(2.dp, outline)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (query.isEmpty()) {
                    Text("search", color = Color(0xFF666666), fontSize = 18.sp)
                }
                inner()
            }
        },
    )
}

@Composable
private fun LetterTile(letter: Char, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val metro = LocalMetro.current
    Box(
        modifier
            .size(LetterTileSize)
            .background(metro.accent)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = letter.toString(),
            color = OnTile,
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 0.dp),
        )
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    isPinned: Boolean,
    iconLoader: IconLoader,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onPin: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    shortcutMenu: ShortcutMenuActions,
) {
    val metro = LocalMetro.current
    var menuOpen by remember { mutableStateOf(false) }
    val icon = rememberTileIcon(app, iconLoader)
    val iconCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        val r = iconCoords[0]?.takeIf { it.isAttached }?.boundsInWindow()
                        onLaunch(app, r?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })
                    },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(LetterTileSize).onGloballyPositioned { iconCoords[0] = it }.background(metro.accent)) {
                icon?.let {
                    Image(
                        bitmap = it.bitmap,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(if (it.glyph) 40.dp else 32.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (app.isWorkProfile) "${app.label} (work)" else app.label,
                fontSize = 20.sp,
                fontWeight = SemiLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (menuOpen) ShortcutMenuItems(app, shortcutMenu, onDone = { menuOpen = false })
            if (!isPinned) {
                DropdownMenuItem(text = { Text("pin to start") }, onClick = { menuOpen = false; onPin(app) })
            }
            DropdownMenuItem(text = { Text("app info") }, onClick = { menuOpen = false; onAppInfo(app) })
            DropdownMenuItem(text = { Text("uninstall") }, onClick = { menuOpen = false; onUninstall(app) })
        }
    }
}

/** Full-screen a–z grid; letters with no apps are greyed out. */
@Composable
private fun JumpGrid(available: Set<Char>, onPick: (Char) -> Unit, onDismiss: () -> Unit) {
    val metro = LocalMetro.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        ) {
            items(JUMP_LETTERS.toList()) { letter ->
                val enabled = letter in available
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(if (enabled) metro.accent else metro.chrome)
                        .clickable(enabled = enabled) { onPick(letter) },
                ) {
                    Text(
                        text = letter.toString(),
                        color = if (enabled) OnTile else metro.subtle,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** The "…" that opens launcher settings, as on WP app bars. */
@Composable
private fun SettingsDots(onClick: () -> Unit) {
    val color = LocalMetro.current.foreground
    Box(Modifier.size(40.dp).clickable(onClick = onClick)) {
        Canvas(Modifier.align(Alignment.Center).size(width = 24.dp, height = 6.dp)) {
            val r = size.height / 2
            listOf(r, size.width / 2, size.width - r).forEach { drawCircle(color, r, Offset(it, r)) }
        }
    }
}
