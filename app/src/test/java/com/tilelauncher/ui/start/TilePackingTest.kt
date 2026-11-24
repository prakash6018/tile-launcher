package com.tilelauncher.ui.start

import com.tilelauncher.data.TileSize.MEDIUM
import com.tilelauncher.data.TileSize.SMALL
import com.tilelauncher.data.TileSize.WIDE
import org.junit.Assert.assertEquals
import org.junit.Test

class TilePackingTest {

    @Test
    fun mediumsFillTwoPerRow() {
        val p = packTiles(listOf(MEDIUM, MEDIUM, MEDIUM), columns = 4)
        assertEquals(listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 2, 2)), p.placements)
        assertEquals(4, p.rows)
    }

    @Test
    fun fourSmallsShareOneMediumSlot() {
        val p = packTiles(listOf(MEDIUM, SMALL, SMALL, SMALL, SMALL), columns = 4)
        assertEquals(
            listOf(Placement(0, 0, 2, 2), Placement(2, 0, 1, 1), Placement(3, 0, 1, 1), Placement(2, 1, 1, 1), Placement(3, 1, 1, 1)),
            p.placements,
        )
        assertEquals(2, p.rows)
    }

    @Test
    fun wideDropsBelowPartialRow() {
        val p = packTiles(listOf(MEDIUM, WIDE), columns = 4)
        assertEquals(Placement(0, 2, 4, 2), p.placements[1])
    }

    @Test
    fun smallBackfillsHoleLeftByWide() {
        val p = packTiles(listOf(SMALL, WIDE, SMALL), columns = 4)
        assertEquals(Placement(0, 1, 4, 2), p.placements[1])
        assertEquals(Placement(1, 0, 1, 1), p.placements[2])
    }

    @Test
    fun sixColumnsFitThreeMediums() {
        val p = packTiles(listOf(MEDIUM, MEDIUM, MEDIUM), columns = 6)
        assertEquals(listOf(0, 2, 4), p.placements.map { it.col })
        assertEquals(2, p.rows)
    }
}
