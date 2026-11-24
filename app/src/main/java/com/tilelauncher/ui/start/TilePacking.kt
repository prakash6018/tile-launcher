package com.tilelauncher.ui.start

import com.tilelauncher.data.TileSize

data class Placement(val col: Int, val row: Int, val w: Int, val h: Int)

class Packing(val placements: List<Placement>, val rows: Int)

/**
 * First-fit flow packing, like the WP Start screen: each tile, in order, takes the first
 * top-to-bottom, left-to-right slot it fits in. Positions are derived, never stored, so
 * reordering or resizing just re-runs this.
 */
fun packTiles(sizes: List<TileSize>, columns: Int): Packing {
    val occupied = ArrayList<BooleanArray>()
    fun ensureRows(n: Int) {
        while (occupied.size < n) occupied.add(BooleanArray(columns))
    }
    fun fits(row: Int, col: Int, w: Int, h: Int): Boolean {
        ensureRows(row + h)
        for (r in row until row + h) for (c in col until col + w) if (occupied[r][c]) return false
        return true
    }

    var searchFrom = 0 // first row that may still have a free cell
    val placements = sizes.map { size ->
        val w = size.w.coerceAtMost(columns)
        val h = size.h
        var row = searchFrom
        var placed: Placement? = null
        while (placed == null) {
            for (col in 0..columns - w) {
                if (fits(row, col, w, h)) {
                    placed = Placement(col, row, w, h)
                    break
                }
            }
            if (placed == null) row++
        }
        for (r in placed.row until placed.row + h) for (c in placed.col until placed.col + w) occupied[r][c] = true
        while (searchFrom < occupied.size && occupied[searchFrom].all { it }) searchFrom++
        placed
    }
    val rows = placements.maxOfOrNull { it.row + it.h } ?: 0
    return Packing(placements, rows)
}
