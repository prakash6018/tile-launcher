package com.tilelauncher.live

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String?,
    val art: ImageBitmap?,
)

/** What a live tile shows for one notification: who it's from and what it says, or its picture. */
data class NotificationPreview(
    val title: String,
    val text: String?,
    val avatar: ImageBitmap?,
    val picture: ImageBitmap?,
)

/** Process-wide state published by [NotificationListener]; empty until the user grants notification access. */
object NotificationHub {
    internal val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    internal val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    internal val latest = MutableStateFlow<Map<String, List<NotificationPreview>>>(emptyMap())

    /** Unread notification count per package (all profiles merged). */
    val badges: StateFlow<Map<String, Int>> = counts
    val media: StateFlow<NowPlaying?> = nowPlaying
    /** Up to three newest notifications per package, newest first. */
    val previews: StateFlow<Map<String, List<NotificationPreview>>> = latest

    fun isEnabled(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

/** Feeds badge counts, notification previews and now-playing info to live tiles. */
class NotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watch(list.orEmpty())
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onSessionDestroyed() = publishMedia()
    }

    override fun onListenerConnected() {
        recount()
        val msm = getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        val me = ComponentName(this, NotificationListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me)
            watch(msm.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        watch(emptyList())
        NotificationHub.counts.value = emptyMap()
        NotificationHub.latest.value = emptyMap()
        imageCache.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    /** Images decoded per notification post, so a recount doesn't re-decode every avatar. */
    private val imageCache = HashMap<String, Pair<ImageBitmap?, ImageBitmap?>>()

    private fun recount() {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.isClearable && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        NotificationHub.counts.value = active.groupingBy { it.packageName }.eachCount()

        val shown = active
            .filter { it.notification.visibility != Notification.VISIBILITY_SECRET }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sortedByDescending { it.postTime }.take(3) }
        imageCache.keys.retainAll(shown.values.flatten().mapTo(HashSet()) { cacheKey(it) })
        NotificationHub.latest.value = shown
            .mapValues { (_, list) -> list.mapNotNull(::preview) }
            .filterValues { it.isNotEmpty() }
    }

    private fun cacheKey(sbn: StatusBarNotification) = "${sbn.key}@${sbn.postTime}"

    private fun preview(sbn: StatusBarNotification): NotificationPreview? {
        val n = sbn.notification
        val extras = n.extras
        // Chat apps (Teams, WhatsApp, Messages…) put the real sender and text in MessagingStyle.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val title = (last?.person?.name ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = (last?.text ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val conversation = style?.conversationTitle?.toString()?.takeIf { style.isGroupConversation }
        val text = if (conversation != null && body != null) "$conversation: $body" else body
        val (avatar, picture) = imageCache.getOrPut(cacheKey(sbn)) {
            val avatar = runCatching { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()
            @Suppress("DEPRECATION") // getParcelable(String, Class) needs API 33.
            val picture = runCatching { (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let(::scaled)?.asImageBitmap() }.getOrNull()
            avatar to picture
        }
        return NotificationPreview(title, text, avatar, picture)
    }

    private fun watch(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = list
        list.forEach { it.registerCallback(controllerCallback) }
        publishMedia()
    }

    private fun publishMedia() {
        val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val md = active?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        NotificationHub.nowPlaying.value = if (active == null || title.isNullOrBlank()) null else NowPlaying(
            packageName = active.packageName,
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            art = (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART))
                ?.let { scaled(it) }?.asImageBitmap(),
        )
    }

    private fun scaled(b: Bitmap): Bitmap {
        val max = 512
        if (b.width <= max && b.height <= max) return b
        val f = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }
}
