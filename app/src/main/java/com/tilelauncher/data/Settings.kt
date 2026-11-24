package com.tilelauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class ThemeMode { DARK, LIGHT, SYSTEM }

enum class IconStyle { GLYPH, COLOR }

data class LauncherSettings(
    val accent: Int = 0xFF0050EF.toInt(),
    val theme: ThemeMode = ThemeMode.DARK,
    val columns: Int = 4,
    /** Bumped whenever the background image file changes; 0 means no background. */
    val backgroundVersion: Long = 0,
    val parallax: Boolean = true,
    val liveTiles: Boolean = true,
    /** Show notification senders and text on tiles. */
    val notificationPreviews: Boolean = true,
    val iconStyle: IconStyle = IconStyle.GLYPH,
    val iconPack: String? = null,
    val onboarded: Boolean = false,
)

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = _settings

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        write(next)
        _settings.value = next
    }

    private fun read() = LauncherSettings(
        accent = prefs.getInt("accent", LauncherSettings().accent),
        theme = enumOr(prefs.getString("theme", null), ThemeMode.DARK),
        columns = prefs.getInt("columns", 4),
        backgroundVersion = prefs.getLong("backgroundVersion", 0),
        parallax = prefs.getBoolean("parallax", true),
        liveTiles = prefs.getBoolean("liveTiles", true),
        notificationPreviews = prefs.getBoolean("notificationPreviews", true),
        iconStyle = enumOr(prefs.getString("iconStyle", null), IconStyle.GLYPH),
        iconPack = prefs.getString("iconPack", null),
        onboarded = prefs.getBoolean("onboarded", false),
    )

    private fun write(s: LauncherSettings) {
        prefs.edit()
            .putInt("accent", s.accent)
            .putString("theme", s.theme.name)
            .putInt("columns", s.columns)
            .putLong("backgroundVersion", s.backgroundVersion)
            .putBoolean("parallax", s.parallax)
            .putBoolean("liveTiles", s.liveTiles)
            .putBoolean("notificationPreviews", s.notificationPreviews)
            .putString("iconStyle", s.iconStyle.name)
            .putString("iconPack", s.iconPack)
            .putBoolean("onboarded", s.onboarded)
            .apply()
    }

    companion object {
        /** Settings that travel in a layout backup (device-specific ones like the background stay behind). */
        fun toJson(s: LauncherSettings): JSONObject = JSONObject()
            .put("accent", s.accent)
            .put("theme", s.theme.name)
            .put("columns", s.columns)
            .put("parallax", s.parallax)
            .put("liveTiles", s.liveTiles)
            .put("notificationPreviews", s.notificationPreviews)
            .put("iconStyle", s.iconStyle.name)

        fun fromJson(o: JSONObject, base: LauncherSettings) = base.copy(
            accent = o.optInt("accent", base.accent),
            theme = enumOr(o.optString("theme"), base.theme),
            columns = o.optInt("columns", base.columns).takeIf { it == 4 || it == 6 } ?: base.columns,
            parallax = o.optBoolean("parallax", base.parallax),
            liveTiles = o.optBoolean("liveTiles", base.liveTiles),
            notificationPreviews = o.optBoolean("notificationPreviews", base.notificationPreviews),
            iconStyle = enumOr(o.optString("iconStyle"), base.iconStyle),
        )

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
