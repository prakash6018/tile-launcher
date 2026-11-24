package com.tilelauncher.ui.start

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.tilelauncher.data.StartItem
import com.tilelauncher.data.TileKind
import kotlin.math.min

/** Where everything on the Start grid sits, in grid-local pixels. */
class GridGeometry(
    val rects: Map<Long, Rect>,
    /** Expanded folder panel (full-bleed), if a folder is open. */
    val panel: Rect?,
    val childRects: Map<Long, Rect>,
    val height: Float,
    val step: Float,
)

fun computeGeometry(
    items: List<StartItem>,
    columns: Int,
    cell: Float,
    gap: Float,
    margin: Float,
    openFolder: StartItem.Folder?,
    panelPadding: Float,
    panelTitle: Float,
): GridGeometry {
    val step = cell + gap
    fun span(units: Int) = units * cell + (units - 1) * gap
    val packing = packTiles(items.map { it.tile.size }, columns)

    // An open folder expands below its own row, at the first row boundary no tile straddles.
    var splitRow = -1
    var childRects = emptyMap<Long, Rect>()
    var panel: Rect? = null
    var panelShift = 0f
    if (openFolder != null) {
        val i = items.indexOfFirst { it.tile.id == openFolder.tile.id }
        if (i >= 0) {
            val p = packing.placements[i]
            var r = p.row + p.h
            while (packing.placements.any { it.row < r && it.row + it.h > r }) r++
            splitRow = r
            val childPacking = packTiles(openFolder.children.map { it.tile.size }, columns)
            val top = r * step
            val contentTop = top + panelPadding + panelTitle
            childRects = openFolder.children.withIndex().associate { (ci, child) ->
                val cp = childPacking.placements[ci]
                child.tile.id to Rect(Offset(cp.col * step, contentTop + cp.row * step), Size(span(cp.w), span(cp.h)))
            }
            val panelHeight = panelPadding * 2 + panelTitle + span(childPacking.rows.coerceAtLeast(1))
            panel = Rect(-margin, top, columns * step - gap + margin, top + panelHeight)
            panelShift = panelHeight + gap
        }
    }

    val rects = items.withIndex().associate { (i, item) ->
        val p = packing.placements[i]
        val shift = if (splitRow >= 0 && p.row >= splitRow) panelShift else 0f
        item.tile.id to Rect(Offset(p.col * step, p.row * step + shift), Size(span(p.w), span(p.h)))
    }
    val height = if (packing.rows == 0) 0f else span(packing.rows) + if (splitRow >= 0) panelShift else 0f
    return GridGeometry(rects, panel, childRects, height, step)
}

/**
 * Edit mode, drag-to-reorder, drop-to-folder and the long-press menus. Pointer callbacks
 * come from a single gesture detector on the grid, so state here is the source of truth.
 */
@Stable
class StartController {
    var editingId by mutableStateOf<Long?>(null)
    var openFolderId by mutableStateOf<Long?>(null)
    var dragId by mutableStateOf<Long?>(null)
        private set
    var dragTopLeft by mutableStateOf(Offset.Zero)
        private set
    /** Tile the dragged tile is hovering over the centre of; becomes [folderTargetId] after a short dwell. */
    var folderCandidateId by mutableStateOf<Long?>(null)
        private set
    var folderTargetId by mutableStateOf<Long?>(null)
        private set
    /** Top-level order while dragging (and until the saved layout catches up). */
    var workingOrder by mutableStateOf<List<Long>?>(null)
    /** Long press on empty space: where to show the Start menu. */
    var menuAt by mutableStateOf<Offset?>(null)
    /** Long press on a tile inside an open folder. */
    var childMenuId by mutableStateOf<Long?>(null)

    var finger = Offset.Zero
        private set
    private var grab = Offset.Zero
    private var lastReorderAt = 0L
    private var initialOrder: List<Long>? = null

    private var geometry: GridGeometry? = null
    private var items: List<StartItem> = emptyList()
    private var actions: StartActions? = null
    private var haptics: HapticFeedback? = null

    fun bind(geometry: GridGeometry, items: List<StartItem>, actions: StartActions, haptics: HapticFeedback) {
        this.geometry = geometry
        this.items = items
        this.actions = actions
        this.haptics = haptics
    }

    fun ordered(items: List<StartItem>): List<StartItem> {
        val order = workingOrder ?: return items
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return items.sortedBy { rank[it.tile.id] ?: Int.MAX_VALUE }
    }

    fun exitEdit() {
        editingId = null
        openFolderId = null
    }

    fun tapEmpty() {
        if (editingId != null) editingId = null else openFolderId = null
    }

    fun dragStart(at: Offset) {
        val g = geometry ?: return
        g.childRects.entries.firstOrNull { at in it.value }?.let {
            childMenuId = it.key
            buzz()
            return
        }
        if (g.panel?.contains(at) == true) return
        val hit = items.firstOrNull { g.rects[it.tile.id]?.contains(at) == true }
        if (hit == null) {
            if (editingId == null) {
                menuAt = at
                buzz()
            }
            return
        }
        buzz()
        openFolderId = null
        editingId = hit.tile.id
        val r = g.rects.getValue(hit.tile.id)
        finger = at
        grab = at - r.topLeft
        dragTopLeft = r.topLeft
        initialOrder = items.map { it.tile.id }
        workingOrder = initialOrder
        dragId = hit.tile.id
    }

    fun dragBy(amount: Offset) {
        if (dragId == null) return
        finger += amount
        dragTopLeft = finger - grab
        retarget()
    }

    /** The grid scrolled under a stationary finger. */
    fun scrolledBy(dy: Float) = dragBy(Offset(0f, dy))

    /** Called once the pointer has rested on [folderCandidateId] long enough to mean "make a folder". */
    fun armFolder(candidate: Long) {
        if (dragId != null && folderCandidateId == candidate) folderTargetId = candidate
    }

    fun dragEnd() {
        val id = dragId ?: return
        val target = folderTargetId
        dragId = null
        folderCandidateId = null
        folderTargetId = null
        if (target != null) {
            workingOrder = initialOrder
            editingId = null
            actions?.dropOnto(id, target)
        } else {
            val order = workingOrder
            if (order != null && order != initialOrder) actions?.reorder(order)
        }
    }

    fun dragCancel() {
        dragId = null
        folderCandidateId = null
        folderTargetId = null
        workingOrder = initialOrder
    }

    private fun retarget() {
        val g = geometry ?: return
        val id = dragId ?: return
        val dragged = items.firstOrNull { it.tile.id == id } ?: return
        val own = g.rects[id] ?: return
        val center = dragTopLeft + Offset(own.width / 2, own.height / 2)
        val hovered = items.firstOrNull { it.tile.id != id && g.rects[it.tile.id]?.contains(center) == true }
        if (hovered == null) {
            setCandidate(null)
            val bottom = g.rects.values.maxOfOrNull { it.bottom } ?: 0f
            if (center.y > bottom) moveTo(items.lastIndex)
            return
        }
        val hr = g.rects.getValue(hovered.tile.id)
        val inner = hr.deflate(min(hr.width, hr.height) * 0.25f)
        val canGroup = dragged.tile.kind == TileKind.APP &&
            (hovered.tile.kind == TileKind.APP || hovered.tile.kind == TileKind.FOLDER)
        if (canGroup && center in inner) {
            setCandidate(hovered.tile.id)
            return
        }
        setCandidate(null)
        moveTo(workingOrder?.indexOf(hovered.tile.id) ?: return)
    }

    private fun setCandidate(id: Long?) {
        if (folderCandidateId == id) return
        folderCandidateId = id
        folderTargetId = null
    }

    private fun moveTo(to: Int) {
        val id = dragId ?: return
        val order = workingOrder?.toMutableList() ?: return
        val from = order.indexOf(id)
        if (from < 0 || from == to || to < 0) return
        // Throttle so a tile that slides under the finger doesn't bounce straight back.
        val now = SystemClock.uptimeMillis()
        if (now - lastReorderAt < 180) return
        lastReorderAt = now
        order.removeAt(from)
        order.add(to.coerceAtMost(order.size), id)
        workingOrder = order
    }

    private fun buzz() = haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
}
