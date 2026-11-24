package com.tilelauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/** Builds the first-run Start screen from the device's default apps. */
object DefaultLayout {

    private const val MAX_TILES = 14

    fun build(context: Context, apps: List<AppEntry>): List<Tile> {
        val pm = context.packageManager
        val primary = apps.filterNot { it.isWorkProfile }

        fun category(c: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, c)
        val picks = listOf(
            Intent(Intent.ACTION_DIAL) to TileSize.MEDIUM,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to TileSize.MEDIUM,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) to TileSize.MEDIUM,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE) to TileSize.MEDIUM,
            category(Intent.CATEGORY_APP_GALLERY) to TileSize.WIDE,
            category(Intent.CATEGORY_APP_EMAIL) to TileSize.MEDIUM,
            category(Intent.CATEGORY_APP_CALENDAR) to TileSize.MEDIUM,
            category(Intent.CATEGORY_APP_CONTACTS) to TileSize.SMALL,
            category(Intent.CATEGORY_APP_MUSIC) to TileSize.SMALL,
            category(Intent.CATEGORY_APP_MAPS) to TileSize.SMALL,
            Intent(Settings.ACTION_SETTINGS) to TileSize.SMALL,
        )

        val chosen = LinkedHashMap<AppEntry, TileSize>()
        for ((intent, size) in picks) {
            val pkg = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            // "android" is the disambiguation dialog: no default is set, so skip it.
            if (pkg == null || pkg == "android" || chosen.keys.any { it.packageName == pkg }) continue
            primary.firstOrNull { it.packageName == pkg }?.let { chosen[it] = size }
        }
        // Top up with other apps so a fresh install doesn't look empty.
        primary.asSequence()
            .filter { app -> chosen.keys.none { it.packageName == app.packageName } }
            .take((MAX_TILES - chosen.size).coerceAtLeast(0))
            .forEach { chosen[it] = TileSize.SMALL }

        val tiles = chosen.entries.mapIndexed { i, (app, size) ->
            Tile(id = i + 1L, kind = TileKind.APP, size = size, app = app.key)
        }
        // A weather tile after the first block of apps, so live tiles show up on day one.
        val weather = Tile(id = tiles.size + 1L, kind = TileKind.WEATHER, size = TileSize.WIDE)
        val me = Tile(id = tiles.size + 2L, kind = TileKind.ME, size = TileSize.WIDE)
        return tiles.take(4) + weather + tiles.drop(4) + me
    }
}
