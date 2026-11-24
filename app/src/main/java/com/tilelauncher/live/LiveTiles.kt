package com.tilelauncher.live

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.tilelauncher.data.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/** One side of a live tile. */
sealed interface TileFace {
    /** Headline with supporting lines: clock, weather, next appointment, now playing. [image] fills the tile behind the text. */
    data class Text(val headline: String, val lines: List<String> = emptyList(), val image: ImageBitmap? = null) : TileFace
    data class Photo(val image: ImageBitmap) : TileFace
    /** People-style grid of small photos that swap individually. */
    data class Mosaic(val images: List<ImageBitmap>) : TileFace
    /** A notification: sender, message and avatar, like WP's mail and messaging tiles. */
    data class Message(val sender: String, val text: String?, val avatar: ImageBitmap?) : TileFace
}

/** Faces a tile flips through. Unless [replacesFront], the app icon is shown between faces. */
data class LiveContent(val faces: List<TileFace>, val replacesFront: Boolean = false)

/**
 * Decides which live content each tile shows and produces it. Sources are shared and only
 * run while some tile is collecting them.
 */
class LiveTiles(private val context: Context, private val scope: CoroutineScope) {

    private data class Roles(val dialer: String?, val clock: String?, val calendar: String?, val gallery: String?, val contacts: String?)

    private val roles = MutableStateFlow(resolveRoles())
    private val refresh = MutableStateFlow(0)

    val badges: StateFlow<Map<String, Int>> = NotificationHub.badges

    private val carrier = MutableStateFlow(readCarrier())
    private val previewsEnabled = MutableStateFlow(true)

    /** Re-reads default apps and permission-gated sources, e.g. after a permission grant. */
    fun refresh() {
        scope.launch(Dispatchers.Default) { roles.value = resolveRoles() }
        refresh.value++
    }

    /** Cheap; called on every resume so SIM swaps and roaming show up. */
    fun refreshCarrier() {
        carrier.value = readCarrier()
    }

    fun setPreviewsEnabled(enabled: Boolean) {
        previewsEnabled.value = enabled
    }

    private val appLabels = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Package → app name, for the Me tile's "from Teams, Outlook" lines. */
    fun setAppLabels(labels: Map<String, String>) {
        appLabels.value = labels
    }

    private val summarizer = MeSummarizer(context)

    fun close() = summarizer.close()

    /** The phone tile is labelled with the mobile operator, as on WP. Null means "use the app name". */
    fun labelFor(app: AppEntry): Flow<String?> =
        combine(roles, carrier) { r, c -> if (app.packageName == r.dialer) c else null }

    private fun readCarrier(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        return listOf(tm.networkOperatorName, tm.simOperatorName).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private val clock: StateFlow<LiveContent?> = flow {
        while (true) {
            emit(clockContent())
            val now = System.currentTimeMillis()
            delay(DateUtils.MINUTE_IN_MILLIS - now % DateUtils.MINUTE_IN_MILLIS)
        }
    }.share()

    val calendar: StateFlow<LiveContent?> = refresh.transformLatest {
        if (!granted(Manifest.permission.READ_CALENDAR)) { emit(null); return@transformLatest }
        contentChanges(CalendarContract.Events.CONTENT_URI, periodMs = 5 * DateUtils.MINUTE_IN_MILLIS)
            .collect { emit(calendarContent()) }
    }.flowOn(Dispatchers.IO).share()

    private val photos: StateFlow<LiveContent?> = refresh.transformLatest {
        if (!granted(photoPermission())) { emit(null); return@transformLatest }
        contentChanges(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, periodMs = 30 * DateUtils.MINUTE_IN_MILLIS)
            .collect { emit(photosContent()) }
    }.flowOn(Dispatchers.IO).share()

    private val people: StateFlow<LiveContent?> = refresh.transformLatest {
        if (!granted(Manifest.permission.READ_CONTACTS)) { emit(null); return@transformLatest }
        contentChanges(ContactsContract.Contacts.CONTENT_URI, periodMs = 60 * DateUtils.MINUTE_IN_MILLIS)
            .collect { emit(peopleContent()) }
    }.flowOn(Dispatchers.IO).share()

    private val weatherSource = WeatherSource(context)
    val weather: StateFlow<LiveContent?> = refresh.transformLatest {
        while (true) {
            emit(weatherSource.fetch())
            delay(30 * DateUtils.MINUTE_IN_MILLIS)
        }
    }.flowOn(Dispatchers.IO).share()

    private val music: Flow<NowPlaying?> = NotificationHub.media

    /** Calendar faces for Microsoft apps, keyed by package: Outlook's agenda, Teams' next meeting. */
    private val microsoftMeetings: StateFlow<Map<String, List<TileFace>>> = refresh.transformLatest {
        if (!granted(Manifest.permission.READ_CALENDAR)) { emit(emptyMap()); return@transformLatest }
        contentChanges(CalendarContract.Events.CONTENT_URI, periodMs = 5 * DateUtils.MINUTE_IN_MILLIS)
            .collect { emit(MicrosoftCalendar.faces(context, ::whenText)) }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val profilePhoto: StateFlow<ImageBitmap?> = refresh.transformLatest {
        emit(if (granted(Manifest.permission.READ_CONTACTS)) readProfilePhoto() else null)
    }.flowOn(Dispatchers.IO).share()

    private class MeInputs(
        val previews: Map<String, List<NotificationPreview>>,
        val badges: Map<String, Int>,
        val next: TileFace.Text?,
        val weather: TileFace.Text?,
        val labels: Map<String, String>,
        val previewsOn: Boolean,
        val photo: ImageBitmap?,
    )

    /**
     * The Me tile: a greeting with what's new, what's next and the weather; an on-device AI
     * summary when the phone supports it; then a face per busiest app.
     */
    val me: StateFlow<LiveContent?> = combine(
        combine(NotificationHub.previews, NotificationHub.badges, previewsEnabled, ::Triple),
        calendar,
        weather,
        appLabels,
        profilePhoto,
    ) { (previews, badges, previewsOn), cal, weatherContent, labels, photo ->
        MeInputs(
            previews = previews,
            badges = badges,
            next = cal?.faces?.firstOrNull() as? TileFace.Text,
            weather = (weatherContent?.faces?.firstOrNull() as? TileFace.Text)?.takeIf { it.headline.endsWith("°") },
            labels = labels,
            previewsOn = previewsOn,
            photo = photo,
        )
    }.transformLatest { inputs ->
        emit(meContent(inputs, summary = null))
        if (!inputs.previewsOn) return@transformLatest
        delay(1_500) // let bursts of notifications settle before running the model
        summarizer.summarize(digestText(inputs))?.takeIf { it.isNotEmpty() }?.let { emit(meContent(inputs, it)) }
    }.flowOn(Dispatchers.Default).share()

    private fun meContent(inputs: MeInputs, summary: List<String>?): LiveContent {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "good morning"
            in 12..16 -> "good afternoon"
            else -> "good evening"
        }
        // Only real apps: system packages ("android", system UI) aren't worth a line.
        val busiest = inputs.badges.entries.filter { it.value > 0 && it.key in inputs.labels }.sortedByDescending { it.value }
        val total = busiest.sumOf { it.value }
        val unreadLine = if (total == 0) "nothing new" else
            "$total new · " + busiest.take(3).joinToString(", ") { inputs.labels[it.key] ?: it.key.substringAfterLast('.') }
        val lines = listOfNotNull(
            unreadLine,
            inputs.next?.let { "next: ${it.headline} · ${it.lines.lastOrNull().orEmpty()}" },
            inputs.weather?.let { "${it.headline} ${it.lines.firstOrNull().orEmpty()}".trim() },
        )
        val faces = mutableListOf<TileFace>(TileFace.Text(greeting, lines, inputs.photo))
        summary?.let { faces += TileFace.Text("for you", it) }
        if (inputs.previewsOn) {
            busiest.take(3).forEach { (pkg, count) ->
                val notes = inputs.previews[pkg].orEmpty()
                val first = notes.firstOrNull() ?: return@forEach
                val senders = notes.map { it.title }.distinct().joinToString(", ")
                faces += TileFace.Message(
                    sender = "${inputs.labels[pkg] ?: pkg.substringAfterLast('.')} · $count",
                    text = listOfNotNull(senders, first.text).joinToString(": "),
                    avatar = first.avatar,
                )
            }
        }
        return LiveContent(faces, replacesFront = true)
    }

    private fun digestText(inputs: MeInputs): String = buildString {
        inputs.previews.entries
            .filter { it.key in inputs.labels }
            .sortedByDescending { inputs.badges[it.key] ?: 0 }
            .flatMap { (pkg, notes) -> notes.map { pkg to it } }
            .take(12)
            .forEach { (pkg, note) ->
                append(inputs.labels[pkg] ?: pkg).append(" notification from ").append(note.title)
                note.text?.let { append(": ").append(it) }
                append(".\n")
            }
        inputs.next?.let { append("Next calendar event: ").append(it.headline).append(", ").append(it.lines.lastOrNull().orEmpty()).append(".\n") }
    }

    private fun readProfilePhoto(): ImageBitmap? = runCatching {
        context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile.PHOTO_THUMBNAIL_URI),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.let { uri -> context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream) }
            ?.asImageBitmap()
    }.getOrNull()

    private fun notificationFaces(pkg: String): Flow<List<TileFace>> =
        combine(NotificationHub.previews, previewsEnabled) { all, on ->
            if (!on) emptyList()
            else all[pkg].orEmpty().map { p ->
                p.picture?.let { TileFace.Photo(it) } ?: TileFace.Message(p.title, p.text, p.avatar)
            }
        }

    /** Live content for an app tile, or null when it only shows its icon. */
    fun contentFor(app: AppEntry): Flow<LiveContent?> {
        val pkg = app.packageName
        val role: Flow<LiveContent?> = roles.transformLatest { r ->
            val source = when (pkg) {
                r.clock -> clock
                r.calendar -> calendar
                r.gallery -> photos
                r.contacts -> people
                else -> flowOf(null)
            }
            source.collect { emit(it) }
        }
        val meetings = microsoftMeetings.map { it[pkg].orEmpty() }
        return combine(role, music, notificationFaces(pkg), meetings) { roleContent, playing, notes, agenda ->
            when {
                // Whatever app is playing media shows now-playing instead.
                playing?.packageName == pkg ->
                    LiveContent(listOf(TileFace.Text(playing.title, listOfNotNull(playing.artist), playing.art)))
                // The clock stays a clock.
                roleContent?.replacesFront == true -> roleContent
                else -> (notes + agenda + roleContent?.faces.orEmpty()).takeIf { it.isNotEmpty() }?.let { LiveContent(it) }
            }
        }
    }

    private fun <T> Flow<T?>.share(): StateFlow<T?> =
        stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun contentChanges(uri: Uri, periodMs: Long): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        val ticker = launch {
            while (true) {
                send(Unit)
                delay(periodMs)
            }
        }
        awaitClose {
            ticker.cancel()
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.conflate()

    private fun resolveRoles(): Roles {
        val pm = context.packageManager
        fun pkg(intent: Intent) = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
        fun category(c: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, c)
        return Roles(
            dialer = pkg(Intent(Intent.ACTION_DIAL)),
            clock = pkg(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            calendar = pkg(category(Intent.CATEGORY_APP_CALENDAR)),
            gallery = pkg(category(Intent.CATEGORY_APP_GALLERY)),
            contacts = pkg(category(Intent.CATEGORY_APP_CONTACTS)),
        )
    }

    private fun clockContent(): LiveContent {
        val now = Date()
        val lines = mutableListOf(
            DateFormat.format("EEEE", now).toString().lowercase(),
            DateFormat.getLongDateFormat(context).format(now),
        )
        context.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
            lines += "alarm " + DateFormat.getTimeFormat(context).format(Date(it.triggerTime))
        }
        return LiveContent(listOf(TileFace.Text(DateFormat.getTimeFormat(context).format(now), lines)), replacesFront = true)
    }

    private fun calendarContent(): LiveContent? {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + 7 * DateUtils.DAY_IN_MILLIS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val faces = runCatching {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext() && size < 3) {
                            val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                            val begin = c.getLong(1)
                            val allDay = c.getInt(2) == 1
                            if (!allDay && begin < now) continue
                            add(TileFace.Text(title, listOfNotNull(c.getString(3)?.takeIf { it.isNotBlank() }, whenText(begin, allDay))))
                        }
                    }
                }
        }.getOrNull().orEmpty()
        return faces.takeIf { it.isNotEmpty() }?.let { LiveContent(it) }
    }

    internal fun whenText(begin: Long, allDay: Boolean): String {
        val day = Calendar.getInstance().apply { timeInMillis = begin }
        val today = Calendar.getInstance()
        val dayText = when {
            sameDay(day, today) -> "today"
            sameDay(day, today.apply { add(Calendar.DAY_OF_YEAR, 1) }) -> "tomorrow"
            else -> DateFormat.format("EEEE", begin).toString().lowercase()
        }
        return if (allDay) "$dayText, all day" else "$dayText " + DateFormat.getTimeFormat(context).format(Date(begin))
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun photoPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun photosContent(): LiveContent? {
        val ids = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c -> buildList { while (c.moveToNext() && size < 12) add(c.getLong(0)) } }
        }.getOrNull().orEmpty()
        val images = ids.mapNotNull { id -> thumbnail(id)?.asImageBitmap() }
        return images.takeIf { it.isNotEmpty() }?.let { LiveContent(it.map(TileFace::Photo)) }
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(id: Long): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            context.contentResolver.loadThumbnail(uri, Size(640, 640), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }.getOrNull()

    private fun peopleContent(): LiveContent? {
        val uris = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts.PHOTO_THUMBNAIL_URI} IS NOT NULL",
                null,
                "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { c -> buildList { while (c.moveToNext() && size < 24) add(Uri.parse(c.getString(0))) } }
        }.getOrNull().orEmpty()
        val images = uris.mapNotNull { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()?.asImageBitmap()
        }
        return images.takeIf { it.isNotEmpty() }?.let { LiveContent(listOf(TileFace.Mosaic(it))) }
    }
}
