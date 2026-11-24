package com.tilelauncher.ui.tile

import android.graphics.Rect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tilelauncher.data.AppEntry
import com.tilelauncher.data.IconLoader
import com.tilelauncher.data.StartItem
import com.tilelauncher.data.TileSize
import com.tilelauncher.live.TileFace
import com.tilelauncher.ui.theme.OnTile
import com.tilelauncher.ui.theme.SemiLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.random.Random

private const val MAX_TILT_DEGREES = 10f

/** Per-tile transform driven by the turnstile and edit mode. */
data class TileTransform(
    val rotationY: Float = 0f,
    val alpha: Float = 1f,
    val scale: Float = 1f,
    /** Rotation pivot as a fraction of tile width; negative values sit left of the tile (the screen edge). */
    val pivotFractionX: Float = 0.5f,
)

/** Start background showing through tiles. [shift] is read at draw time so scrolling redraws without recomposing. */
class Backdrop(val image: ImageBitmap, val shift: () -> Float)

/**
 * The shell shared by every tile: background (accent or backdrop), press tilt, flip and
 * turnstile transforms, and tap handling. Long presses are left to the grid, which owns drag.
 */
@Composable
fun TileFrame(
    width: Dp,
    height: Dp,
    color: Color,
    backdrop: Backdrop?,
    transform: TileTransform,
    flipRotation: () -> Float,
    tiltEnabled: Boolean,
    onTap: (sourceBounds: Rect?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var press by remember { mutableStateOf<Offset?>(null) }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentTilt by rememberUpdatedState(tiltEnabled)

    val tiltSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val p = press
    val w = coords[0]?.size?.width ?: 0
    val h = coords[0]?.size?.height ?: 0
    // Normalised press position in -1..1 from the tile's centre.
    val nx = if (p != null && w > 0) p.x / w * 2 - 1 else 0f
    val ny = if (p != null && h > 0) p.y / h * 2 - 1 else 0f
    val tiltX by animateFloatAsState(-ny * MAX_TILT_DEGREES, tiltSpring, label = "tiltX")
    val tiltY by animateFloatAsState(nx * MAX_TILT_DEGREES, tiltSpring, label = "tiltY")
    val pressScale by animateFloatAsState(if (p != null) 0.97f else 1f, tiltSpring, label = "pressScale")

    Box(
        modifier
            .size(width, height)
            .onGloballyPositioned { coords[0] = it }
            .graphicsLayer {
                rotationX = tiltX + flipRotation()
                rotationY = tiltY + transform.rotationY
                scaleX = pressScale * transform.scale
                scaleY = pressScale * transform.scale
                alpha = transform.alpha
                transformOrigin = TransformOrigin(transform.pivotFractionX, 0.5f)
                cameraDistance = 12 * density
                clip = true
            }
            .drawBehind { drawTileBackground(color, backdrop, coords[0]) }
            // A tap is a release before the long-press timeout. Moves are never consumed, so the
            // grid's long-press drag (and scrolling) keep working; detectTapGestures would swallow them.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (currentTilt) press = down.position
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    press = null
                    if (up != null) {
                        up.consume()
                        val r = coords[0]?.takeIf { it.isAttached }?.boundsInWindow()
                        currentOnTap(r?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })
                    }
                }
            },
        content = content,
    )
}

private fun DrawScope.drawTileBackground(color: Color, backdrop: Backdrop?, coords: LayoutCoordinates?) {
    if (backdrop == null || coords == null || !coords.isAttached) {
        drawRect(color)
        return
    }
    val img = backdrop.image
    val shift = backdrop.shift()
    val pos = coords.positionInWindow()
    val w = size.width.toInt().coerceIn(1, img.width)
    val h = size.height.toInt().coerceIn(1, img.height)
    val sx = pos.x.toInt().coerceIn(0, img.width - w)
    val sy = (pos.y + shift).toInt().coerceIn(0, img.height - h)
    drawImage(img, IntOffset(sx, sy), IntSize(w, h), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    drawRect(Color.Black, alpha = 0.15f)
}

// --- Live flipping ---

@Stable
class Flipper {
    var index by mutableIntStateOf(0)
    val rotation = Animatable(0f)
    private val mutex = Mutex()

    /** One flip to the next of [count] faces; flips never overlap. */
    suspend fun advance(count: Int) = mutex.withLock {
        if (count <= 1) return@withLock
        rotation.animateTo(90f, tween(220, easing = FastOutLinearInEasing))
        index = (index + 1) % count
        rotation.snapTo(-90f)
        rotation.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
    }
}

/** Flips through [count] faces at random 5–12 s intervals while [active], so tiles never flip in sync. */
@Composable
fun rememberFlipper(count: Int, active: Boolean): Flipper {
    val flipper = remember { Flipper() }
    LaunchedEffect(count, active) {
        if (flipper.index >= count) flipper.index = 0
        if (count <= 1 || !active) {
            flipper.rotation.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            delay(Random.nextLong(5_000, 12_000))
            flipper.advance(count)
        }
    }
    return flipper
}

// --- Tile contents ---

@Composable
fun rememberTileIcon(app: AppEntry, iconLoader: IconLoader): IconLoader.TileIcon? {
    val version by iconLoader.version.collectAsState()
    val icon by produceState(iconLoader.cached(app), app.key, version) { value = iconLoader.load(app) }
    return icon
}

@Composable
fun BoxScope.TileLabel(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        color = OnTile,
        fontSize = fontSize,
        fontWeight = SemiLight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 5.dp),
    )
}

/** Icon front of an app tile, with the unread count beside the glyph as on WP. */
@Composable
fun BoxScope.AppTileFront(icon: IconLoader.TileIcon?, size: TileSize, width: Dp, height: Dp, badge: Int) {
    val shortSide = min(width.value, height.value)
    val fraction = when {
        size == TileSize.SMALL -> if (icon?.glyph == true) 0.72f else 0.6f
        icon?.glyph == true -> 0.5f
        else -> 0.4f
    }
    val iconSize = (shortSide * fraction).dp
    if (size == TileSize.SMALL) {
        icon?.let { Image(it.bitmap, null, Modifier.align(Alignment.Center).size(iconSize)) }
        if (badge > 0) {
            Text(
                badge.toString(), color = OnTile, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 2.dp),
            )
        }
        return
    }
    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Image(it.bitmap, null, Modifier.size(iconSize)) }
        if (badge > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                if (badge > 99) "99+" else badge.toString(),
                color = OnTile,
                fontSize = (shortSide * 0.22f).sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

/** Back of a live tile. Leaves room at the bottom for the label. */
@Composable
fun BoxScope.TileFaceView(face: TileFace, size: TileSize, height: Dp, active: Boolean) {
    when (face) {
        is TileFace.Photo -> Image(face.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        is TileFace.Mosaic -> Mosaic(face.images, columns = if (size.w >= 4) 6 else 3, rows = if (size == TileSize.LARGE) 6 else 3, active = active)
        is TileFace.Message -> {
            val textLines = ((height.value - 88) / 18).toInt().coerceIn(1, 6)
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    face.avatar?.let {
                        Image(it, null, Modifier.size(36.dp), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(face.sender, color = OnTile, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                face.text?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(it, color = OnTile, fontSize = 14.sp, fontWeight = SemiLight, maxLines = textLines, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
        }
        is TileFace.Text -> {
            face.image?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            }
            val big = face.headline.length <= 6 && face.image == null
            val maxLines = ((height.value - 70) / 18).toInt().coerceIn(1, 5)
            Column(
                Modifier
                    .align(if (face.image != null) Alignment.BottomStart else Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 26.dp),
            ) {
                Text(
                    face.headline,
                    color = OnTile,
                    fontSize = if (big) (if (size.w >= 4) 56.sp else 46.sp) else 20.sp,
                    fontWeight = if (big) FontWeight.Light else FontWeight.Normal,
                    maxLines = if (big) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = if (big) 58.sp else 23.sp,
                )
                face.lines.take(maxLines).forEach {
                    Text(it, color = OnTile, fontSize = 14.sp, fontWeight = SemiLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Mosaic(images: List<ImageBitmap>, columns: Int, rows: Int, active: Boolean) {
    val cells = remember(images, columns, rows) {
        mutableStateListOf<Int>().apply { repeat(columns * rows) { add(it % images.size) } }
    }
    LaunchedEffect(cells, active) {
        if (!active || images.size <= 1) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(1_500, 3_500))
            cells[Random.nextInt(cells.size)] = Random.nextInt(images.size)
        }
    }
    Column(Modifier.fillMaxSize()) {
        repeat(rows) { r ->
            Row(Modifier.weight(1f)) {
                repeat(columns) { c ->
                    Crossfade(cells[r * columns + c], Modifier.weight(1f).fillMaxSize(), label = "mosaic") { i ->
                        Image(images[i], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

/** Folder tile: miniature tiles of what's inside. */
@Composable
fun BoxScope.FolderTileContent(children: List<StartItem.App>, size: TileSize, iconLoader: IconLoader) {
    val (columns, rows) = when (size) {
        TileSize.SMALL -> 2 to 2
        TileSize.MEDIUM -> 3 to 2
        TileSize.WIDE -> 6 to 2
        TileSize.LARGE -> 6 to 5
    }
    val gap = if (size == TileSize.SMALL) 2.dp else 4.dp
    Column(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(if (size == TileSize.SMALL) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0 until columns) {
                    val child = children.getOrNull(r * columns + c)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(if (child != null) Color.Black.copy(alpha = 0.22f) else Color.Transparent),
                    ) {
                        if (child != null) {
                            rememberTileIcon(child.app, iconLoader)?.let {
                                Image(it.bitmap, null, Modifier.align(Alignment.Center).fillMaxSize(if (it.glyph) 0.75f else 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}
