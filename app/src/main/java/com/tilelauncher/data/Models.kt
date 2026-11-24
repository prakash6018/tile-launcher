package com.tilelauncher.data

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle

/** Identifies a launchable activity in a specific user profile. Stable across restarts. */
data class AppKey(val component: ComponentName, val userSerial: Long)

data class AppEntry(
    val key: AppKey,
    val label: String,
    val user: UserHandle,
    val info: LauncherActivityInfo,
    val isWorkProfile: Boolean,
) {
    val packageName: String get() = key.component.packageName
}

/** Tile footprint in small-cell units. LARGE is only offered for widget tiles. */
enum class TileSize(val w: Int, val h: Int) {
    SMALL(1, 1),
    MEDIUM(2, 2),
    WIDE(4, 2),
    LARGE(4, 4),
}

enum class TileKind {
    APP,
    FOLDER,
    WEATHER,
    WIDGET,
    /** A pinned app shortcut, e.g. a Chrome web app or "New Teams chat". */
    SHORTCUT,
    /** The "Me" digest tile. */
    ME;

    /** Sizes the resize button cycles through, in WP order (medium → small → wide). */
    val sizes: List<TileSize>
        get() = when (this) {
            APP, FOLDER, SHORTCUT -> listOf(TileSize.MEDIUM, TileSize.SMALL, TileSize.WIDE)
            WEATHER -> listOf(TileSize.MEDIUM, TileSize.WIDE)
            WIDGET -> listOf(TileSize.MEDIUM, TileSize.WIDE, TileSize.LARGE)
            ME -> listOf(TileSize.WIDE, TileSize.MEDIUM, TileSize.LARGE)
        }
}

/** Identifies a pinned shortcut across restarts. */
data class ShortcutRef(val packageName: String, val id: String, val userSerial: Long)

data class Tile(
    val id: Long,
    val kind: TileKind = TileKind.APP,
    val size: TileSize = TileSize.MEDIUM,
    val app: AppKey? = null,
    /** Folder this tile lives in; null for tiles on Start itself. */
    val parentId: Long? = null,
    /** Folder name, or a shortcut's label. */
    val title: String? = null,
    val widgetId: Int? = null,
    val shortcut: ShortcutRef? = null,
    val liveEnabled: Boolean = true,
)

/** A top-level Start tile joined with whatever it shows. */
sealed interface StartItem {
    val tile: Tile

    data class App(override val tile: Tile, val app: AppEntry) : StartItem
    data class Folder(override val tile: Tile, val children: List<App>) : StartItem
    data class Weather(override val tile: Tile) : StartItem
    data class Widget(override val tile: Tile) : StartItem
    data class Shortcut(override val tile: Tile, val ref: ShortcutRef, val label: String) : StartItem
    data class Me(override val tile: Tile) : StartItem
}
