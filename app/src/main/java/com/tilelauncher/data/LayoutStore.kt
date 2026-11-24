package com.tilelauncher.data

import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the Start screen tile list as a small JSON file. Returns null from [load] on first run. */
class LayoutStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "start_layout.json"))

    fun load(): List<Tile>? {
        if (!file.baseFile.exists()) return null
        return runCatching { decode(JSONArray(file.readFully().decodeToString())) }.getOrNull()
    }

    fun save(tiles: List<Tile>) {
        val out = file.startWrite()
        try {
            out.write(encode(tiles).toString().encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        fun encode(tiles: List<Tile>): JSONArray {
            val array = JSONArray()
            tiles.forEach { t ->
                array.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("kind", t.kind.name)
                        .put("size", t.size.name)
                        .put("live", t.liveEnabled)
                        .apply {
                            t.app?.let { put("component", it.component.flattenToString()).put("user", it.userSerial) }
                            t.parentId?.let { put("parent", it) }
                            t.title?.let { put("title", it) }
                            t.widgetId?.let { put("widget", it) }
                            t.shortcut?.let { put("scPkg", it.packageName).put("scId", it.id).put("scUser", it.userSerial) }
                        }
                )
            }
            return array
        }

        fun decode(array: JSONArray): List<Tile> = (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val kind = runCatching { TileKind.valueOf(o.optString("kind", "APP")) }.getOrNull() ?: return@mapNotNull null
            val app = o.optString("component").takeIf { it.isNotEmpty() }
                ?.let { ComponentName.unflattenFromString(it) }
                ?.let { AppKey(it, o.optLong("user")) }
            if (kind == TileKind.APP && app == null) return@mapNotNull null
            val shortcut = if (o.has("scPkg")) ShortcutRef(o.getString("scPkg"), o.getString("scId"), o.optLong("scUser")) else null
            if (kind == TileKind.SHORTCUT && shortcut == null) return@mapNotNull null
            Tile(
                id = o.getLong("id"),
                kind = kind,
                size = runCatching { TileSize.valueOf(o.getString("size")) }.getOrDefault(TileSize.MEDIUM),
                app = app,
                parentId = if (o.has("parent")) o.getLong("parent") else null,
                title = if (o.has("title")) o.getString("title") else null,
                widgetId = if (o.has("widget")) o.getInt("widget") else null,
                shortcut = shortcut,
                liveEnabled = o.optBoolean("live", true),
            )
        }
    }
}
