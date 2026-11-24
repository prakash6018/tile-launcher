package com.tilelauncher.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The saved tile layout: loads once, applies edits, persists them in order. */
class LayoutRepository(context: Context, private val scope: CoroutineScope) {

    private val store = LayoutStore(context)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val loaded = CompletableDeferred<Boolean>()

    private val _tiles = MutableStateFlow<List<Tile>?>(null)
    /** Null until loaded, and on first run until [set] provides the default layout. */
    val tiles: StateFlow<List<Tile>?> = _tiles

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.load() }
            _tiles.value = saved
            loaded.complete(saved != null)
        }
    }

    /** True if a layout was saved before; false on first run. */
    suspend fun awaitLoaded(): Boolean = loaded.await()

    fun set(list: List<Tile>) {
        _tiles.value = list
        scope.launch(io) { store.save(list) }
    }

    /** Applies [transform] to the current layout; returns the tiles it removed. */
    fun update(transform: (List<Tile>) -> List<Tile>): List<Tile> {
        val current = _tiles.value ?: return emptyList()
        val next = transform(current)
        if (next == current) return emptyList()
        set(next)
        val kept = next.mapTo(HashSet()) { it.id }
        return current.filter { it.id !in kept }
    }

    /** For edits that may arrive before the launcher has ever run (e.g. a pin request on a fresh install). */
    suspend fun updateWhenLoaded(transform: (List<Tile>) -> List<Tile>) {
        awaitLoaded()
        if (_tiles.value == null) set(transform(emptyList())) else update(transform)
    }
}
