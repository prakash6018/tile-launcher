package com.tilelauncher.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Current conditions from Open-Meteo (free, no API key) for the device's coarse location. */
class WeatherSource(private val context: Context) {

    private var last: LiveContent? = null

    suspend fun fetch(): LiveContent {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return message("turn on location in settings")
        }
        val location = location() ?: return last ?: message("location unavailable")
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1" +
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val content = runCatching {
            val json = get(url)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val temp = current.getDouble("temperature_2m").roundToInt()
            val high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            val low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
            val lines = listOfNotNull(
                describe(current.getInt("weather_code")),
                city(location),
                "$high° / $low°",
            )
            LiveContent(listOf(TileFace.Text("$temp°", lines)), replacesFront = true)
        }.getOrNull()
        if (content != null) last = content
        return content ?: last ?: message("weather unavailable")
    }

    private fun message(text: String) = LiveContent(listOf(TileFace.Text("weather", listOf(text))), replacesFront = true)

    @Suppress("MissingPermission") // Checked in fetch().
    private suspend fun location(): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val known = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (known != null) return known
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                lm.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    @Suppress("DEPRECATION") // The listener variant needs API 33; this runs on a background thread anyway.
    private fun city(location: Location): String? = runCatching {
        Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()?.lowercase()

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "showers"
        85, 86 -> "snow showers"
        in 95..99 -> "thunderstorms"
        else -> "—"
    }
}
