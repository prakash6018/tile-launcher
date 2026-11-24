package com.tilelauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Renders app icons for tiles. Sources, in order:
 * 1. the selected icon pack,
 * 2. the adaptive icon's themed monochrome layer (API 33+), tinted white, unless the style is COLOR,
 * 3. the full-color icon.
 */
class IconLoader(private val context: Context) {

    /** [glyph] icons are designed to sit directly on the accent and are drawn larger. */
    class TileIcon(val bitmap: ImageBitmap, val glyph: Boolean)

    private val cache = LruCache<AppKey, TileIcon>(256)
    private val sizePx = (96 * context.resources.displayMetrics.density).toInt()
    private val densityDpi = context.resources.displayMetrics.densityDpi

    @Volatile private var style = IconStyle.GLYPH
    @Volatile private var pack: IconPack? = null
    private var packName: String? = null

    private val _version = MutableStateFlow(0)
    /** Changes whenever cached icons are invalidated, so callers can reload. */
    val version: StateFlow<Int> = _version

    suspend fun configure(style: IconStyle, iconPack: String?) {
        if (style == this.style && iconPack == packName) return
        val loaded = withContext(Dispatchers.IO) { iconPack?.let { IconPack.load(context, it) } }
        this.style = style
        this.pack = loaded
        this.packName = iconPack
        invalidate()
    }

    fun invalidate() {
        cache.evictAll()
        _version.value++
    }

    fun cached(app: AppEntry): TileIcon? = cache.get(app.key)

    suspend fun load(app: AppEntry): TileIcon = cache.get(app.key) ?: withContext(Dispatchers.IO) {
        val fromPack = pack?.drawableFor(app.key.component, densityDpi)
        val icon = if (fromPack != null) {
            TileIcon(renderFull(fromPack), glyph = true)
        } else {
            val drawable = app.info.getIcon(densityDpi)
            val mono = if (style == IconStyle.GLYPH) monochromeLayer(drawable) else null
            if (mono != null) TileIcon(renderMonochrome(mono), glyph = true)
            else TileIcon(renderFull(drawable), glyph = false)
        }
        cache.put(app.key, icon)
        icon
    }

    private fun monochromeLayer(drawable: Drawable): Drawable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawable is AdaptiveIconDrawable) {
            drawable.monochrome?.mutate()
        } else {
            null
        }

    private fun renderMonochrome(layer: Drawable): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // Adaptive layers are 108dp with a 72dp visible zone, so draw at 1.5x and crop to the safe zone.
        val inset = sizePx / 4
        layer.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
        layer.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        layer.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    private fun renderFull(drawable: Drawable): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
