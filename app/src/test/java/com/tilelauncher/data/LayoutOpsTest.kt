package com.tilelauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutOpsTest {

    // Tiles carry no AppKey here (ComponentName is an Android class); structure is all that matters.
    private fun app(id: Long, parent: Long? = null) =
        Tile(id = id, kind = TileKind.APP, app = null, parentId = parent)

    private fun folder(id: Long) = Tile(id = id, kind = TileKind.FOLDER)

    private fun List<Tile>.shape() = map { Triple(it.id, it.kind, it.parentId) }

    @Test
    fun dropOntoAppCreatesFolderInTargetsPlace() {
        val tiles = listOf(app(1), app(2), app(3))
        val out = LayoutOps.dropOnto(tiles, draggedId = 3, targetId = 1)
        assertEquals(
            listOf(Triple(4L, TileKind.FOLDER, null), Triple(2L, TileKind.APP, null), Triple(1L, TileKind.APP, 4L), Triple(3L, TileKind.APP, 4L)),
            out.shape(),
        )
    }

    @Test
    fun dropOntoFolderJoinsIt() {
        val tiles = listOf(folder(1), app(2, parent = 1), app(3, parent = 1), app(4))
        val out = LayoutOps.dropOnto(tiles, draggedId = 4, targetId = 1)
        assertEquals(listOf(2L, 3L, 4L), out.filter { it.parentId == 1L }.map { it.id })
        assertEquals(listOf(1L), LayoutOps.topLevel(out).map { it.id })
    }

    @Test
    fun folderWithOneChildDissolvesIntoIt() {
        val tiles = listOf(app(9), folder(1), app(2, parent = 1), app(3, parent = 1))
        val out = LayoutOps.removeFromFolder(tiles, childId = 2)
        // 2 goes after the folder, then the folder (now holding only 3) becomes 3.
        assertEquals(listOf(9L, 3L, 2L), out.map { it.id })
        out.forEach { assertNull(it.parentId) }
    }

    @Test
    fun removingFolderRemovesChildren() {
        val tiles = listOf(folder(1), app(2, parent = 1), app(3, parent = 1), app(4))
        assertEquals(listOf(4L), LayoutOps.remove(tiles, 1).map { it.id })
    }

    @Test
    fun moveKeepsChildrenAndReordersTopLevel() {
        val tiles = listOf(app(1), app(2), folder(3), app(4, parent = 3), app(5, parent = 3))
        val out = LayoutOps.move(tiles, id = 1, toIndex = 2)
        assertEquals(listOf(2L, 3L, 1L), LayoutOps.topLevel(out).map { it.id })
        assertEquals(listOf(4L, 5L), out.filter { it.parentId == 3L }.map { it.id })
    }

    @Test
    fun reorderAppliesDragOrder() {
        val tiles = listOf(app(1), app(2), app(3))
        assertEquals(listOf(3L, 1L, 2L), LayoutOps.reorder(tiles, listOf(3, 1, 2)).map { it.id })
    }

    @Test
    fun orphanChildrenReturnToStart() {
        val tiles = listOf(app(1), app(2, parent = 99))
        assertEquals(listOf(null, null), LayoutOps.normalizeFolders(tiles).map { it.parentId })
    }
}
