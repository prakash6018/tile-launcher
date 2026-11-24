package com.tilelauncher.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

data class IconPackInfo(val packageName: String, val label: String)

/** An installed icon pack in the common ADW/Nova `appfilter.xml` format. */
class IconPack private constructor(
    private val resources: Resources,
    private val drawables: Map<ComponentName, Int>,
) {
    fun drawableFor(component: ComponentName, densityDpi: Int): Drawable? =
        drawables[component]?.let { runCatching { resources.getDrawableForDensity(it, densityDpi, null) }.getOrNull() }

    companion object {
        private val PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme",
        )

        fun installed(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            return PACK_ACTIONS
                .flatMap { pm.queryIntentActivities(Intent(it), 0) }
                .map { it.activityInfo.packageName }
                .distinct()
                .mapNotNull { pkg ->
                    runCatching { IconPackInfo(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
                }
                .sortedBy { it.label.lowercase() }
        }

        @SuppressLint("DiscouragedApi") // Icon packs are only addressable by resource name.
        fun load(context: Context, packageName: String): IconPack? = runCatching {
            val res = context.packageManager.getResourcesForApplication(packageName)
            val xmlId = res.getIdentifier("appfilter", "xml", packageName)
            val parser: XmlPullParser = if (xmlId != 0) {
                res.getXml(xmlId)
            } else {
                val bytes = res.assets.open("appfilter.xml").use { it.readBytes() }
                Xml.newPullParser().apply { setInput(bytes.inputStream(), null) }
            }
            val map = HashMap<ComponentName, Int>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                val component = parser.getAttributeValue(null, "component")
                    ?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                    ?.let { ComponentName.unflattenFromString(it) } ?: continue
                val name = parser.getAttributeValue(null, "drawable") ?: continue
                val id = res.getIdentifier(name, "drawable", packageName)
                if (id != 0) map[component] = id
            }
            IconPack(res, map)
        }.getOrNull()
    }
}
