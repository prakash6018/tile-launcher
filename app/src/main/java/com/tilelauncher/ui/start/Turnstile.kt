package com.tilelauncher.ui.start

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tilelauncher.ui.tile.TileTransform
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The WP turnstile: items swing around the left screen edge, staggered row by row from the
 * top, with the tapped item going last. [flyIn] plays it in reverse when the launcher comes
 * back. One instance is shared by Start and the app list.
 */
@Stable
class Turnstile {

    enum class Mode { IDLE, OUT, IN }

    var mode by mutableStateOf(Mode.IDLE)
        private set
    private var origin: Any? = null
    private val elapsed = Animatable(0f)
    private var totalMs = 0f

    val isIdle: Boolean get() = mode == Mode.IDLE

    /**
     * Transform for one item. [screenRow] is its row counted from the top of the screen and
     * [pivotFractionX] puts the pivot on the screen's left edge.
     */
    fun transform(key: Any, screenRow: Int, rightHalf: Boolean, pivotFractionX: Float): TileTransform {
        if (mode == Mode.IDLE) return NONE
        val delay = if (key == origin) maxDelay() + ORIGIN_EXTRA_MS else rowDelay(screenRow, rightHalf)
        val local = ((elapsed.value - delay) / ITEM_MS).coerceIn(0f, 1f)
        return when (mode) {
            Mode.OUT -> {
                val e = FastOutLinearInEasing.transform(local)
                TileTransform(rotationY = 85f * e, alpha = if (local >= 1f) 0f else 1f - e * e, pivotFractionX = pivotFractionX)
            }
            Mode.IN -> {
                val e = LinearOutSlowInEasing.transform(local)
                TileTransform(rotationY = -85f * (1f - e), alpha = if (local <= 0f) 0f else e, pivotFractionX = pivotFractionX)
            }
            Mode.IDLE -> NONE
        }
    }

    /**
     * Plays the exit and calls [onLaunch] partway through, so the app window starts growing
     * while the last items are still leaving.
     */
    suspend fun out(origin: Any, onLaunch: () -> Unit) = coroutineScope {
        this@Turnstile.origin = origin
        totalMs = maxDelay() + ORIGIN_EXTRA_MS + ITEM_MS
        elapsed.snapTo(0f)
        mode = Mode.OUT
        launch {
            delay((totalMs * LAUNCH_AT).toLong())
            onLaunch()
        }
        elapsed.animateTo(totalMs, tween(totalMs.toInt(), easing = LinearEasing))
    }

    /** Hides everything so the next [flyIn] starts from nothing (called when the launcher is hidden). */
    suspend fun prepareFlyIn() {
        origin = null
        elapsed.snapTo(0f)
        mode = Mode.IN
    }

    suspend fun flyIn() {
        if (mode != Mode.IN) prepareFlyIn()
        totalMs = maxDelay() + ITEM_MS
        elapsed.snapTo(0f)
        elapsed.animateTo(totalMs, tween(totalMs.toInt(), easing = LinearEasing))
        mode = Mode.IDLE
    }

    suspend fun reset() {
        elapsed.snapTo(0f)
        mode = Mode.IDLE
    }

    private fun rowDelay(row: Int, rightHalf: Boolean) =
        row.coerceIn(0, MAX_ROWS) * ROW_STAGGER_MS + if (rightHalf) COLUMN_STAGGER_MS else 0f

    private fun maxDelay() = MAX_ROWS * ROW_STAGGER_MS + COLUMN_STAGGER_MS

    private companion object {
        val NONE = TileTransform()
        const val ITEM_MS = 200f
        const val ROW_STAGGER_MS = 15f
        const val COLUMN_STAGGER_MS = 10f
        const val ORIGIN_EXTRA_MS = 35f
        const val MAX_ROWS = 8
        /** Fraction of the exit after which the app is launched. */
        const val LAUNCH_AT = 0.55f
    }
}
