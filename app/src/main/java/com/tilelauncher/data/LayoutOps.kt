package com.tilelauncher.data

/**
 * Pure edits on the tile list. List order is Start order for top-level tiles and
 * folder order for children; the two never interleave meaningfully.
 */
object LayoutOps {

    fun nextId(tiles: List<Tile>): Long = (tiles.maxOfOrNull { it.id } ?: 0) + 1

    fun topLevel(tiles: List<Tile>): List<Tile> = tiles.filter { it.parentId == null }

    fun pinApp(tiles: List<Tile>, key: AppKey): List<Tile> =
        if (tiles.any { it.app == key }) tiles
        else tiles + Tile(id = nextId(tiles), kind = TileKind.APP, size = TileSize.MEDIUM, app = key)

    fun add(tiles: List<Tile>, tile: Tile): List<Tile> = tiles + tile.copy(id = nextId(tiles))

    /** Removes a tile; removing a folder removes everything in it. */
    fun remove(tiles: List<Tile>, id: Long): List<Tile> =
        normalizeFolders(tiles.filterNot { it.id == id || it.parentId == id })

    fun resize(tiles: List<Tile>, id: Long, size: TileSize): List<Tile> =
        tiles.map { if (it.id == id) it.copy(size = size) else it }

    fun setLive(tiles: List<Tile>, id: Long, enabled: Boolean): List<Tile> =
        tiles.map { if (it.id == id) it.copy(liveEnabled = enabled) else it }

    fun rename(tiles: List<Tile>, id: Long, title: String): List<Tile> =
        tiles.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { null }) else it }

    /** Moves a top-level tile to [toIndex] among top-level tiles. */
    fun move(tiles: List<Tile>, id: Long, toIndex: Int): List<Tile> {
        val top = topLevel(tiles).toMutableList()
        val from = top.indexOfFirst { it.id == id }
        if (from < 0) return tiles
        val tile = top.removeAt(from)
        top.add(toIndex.coerceIn(0, top.size), tile)
        return top + tiles.filter { it.parentId != null }
    }

    /** Applies a full top-level order (ids), e.g. after a drag. Unknown ids keep their relative order at the end. */
    fun reorder(tiles: List<Tile>, order: List<Long>): List<Tile> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        val top = topLevel(tiles).sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        return top + tiles.filter { it.parentId != null }
    }

    /**
     * Drops app tile [draggedId] onto [targetId]. Onto a folder it joins the folder; onto
     * another app tile the two become a new folder in the target's place.
     */
    fun dropOnto(tiles: List<Tile>, draggedId: Long, targetId: Long): List<Tile> {
        val dragged = tiles.firstOrNull { it.id == draggedId } ?: return tiles
        val target = tiles.firstOrNull { it.id == targetId } ?: return tiles
        if (dragged.kind != TileKind.APP || dragged.id == target.id) return tiles
        return when (target.kind) {
            TileKind.FOLDER -> {
                val rest = tiles.filterNot { it.id == draggedId }
                normalizeFolders(rest + dragged.copy(parentId = target.id))
            }
            TileKind.APP -> {
                if (target.parentId != null) return tiles
                val folder = Tile(id = nextId(tiles), kind = TileKind.FOLDER, size = TileSize.MEDIUM)
                val out = ArrayList<Tile>(tiles.size + 1)
                for (t in tiles) {
                    when (t.id) {
                        targetId -> out += folder
                        draggedId -> Unit
                        else -> out += t
                    }
                }
                out += target.copy(parentId = folder.id)
                out += dragged.copy(parentId = folder.id)
                normalizeFolders(out)
            }
            else -> tiles
        }
    }

    /** Moves a folder child back onto Start, right after its folder. */
    fun removeFromFolder(tiles: List<Tile>, childId: Long): List<Tile> {
        val child = tiles.firstOrNull { it.id == childId } ?: return tiles
        val folderId = child.parentId ?: return tiles
        val out = tiles.filterNot { it.id == childId }.toMutableList()
        val at = out.indexOfFirst { it.id == folderId }
        out.add(at + 1, child.copy(parentId = null))
        return normalizeFolders(out)
    }

    /** Empty folders disappear; a folder with one tile is replaced by that tile. */
    fun normalizeFolders(tiles: List<Tile>): List<Tile> {
        val folderIds = tiles.filter { it.kind == TileKind.FOLDER }.mapTo(HashSet()) { it.id }
        val childCount = tiles.filter { it.parentId != null }.groupingBy { it.parentId!! }.eachCount()
        val out = ArrayList<Tile>(tiles.size)
        for (t in tiles) {
            when {
                t.kind == TileKind.FOLDER -> when (childCount[t.id] ?: 0) {
                    0 -> Unit
                    1 -> out += tiles.first { it.parentId == t.id }.copy(parentId = null)
                    else -> out += t
                }
                t.parentId == null -> out += t
                t.parentId !in folderIds -> out += t.copy(parentId = null) // orphan
                (childCount[t.parentId] ?: 0) > 1 -> out += t
            }
        }
        return out
    }
}
