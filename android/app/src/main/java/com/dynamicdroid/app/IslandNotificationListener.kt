package com.dynamicdroid.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

/**
 * Turns notifications into island activities. Filters out the noise (silent, ongoing,
 * group summaries, repeat posts) that used to make the island flash in and out.
 */
class IslandNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var mediaNotification: StatusBarNotification? = null
    private var controller: MediaController? = null
    private val lastMessageText = HashMap<String, String>()

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artAccent: Int? = null

    private val publishMedia = Runnable { publishCurrentMedia() }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()
        override fun onSessionDestroyed() {
            mediaNotification?.key?.let(IslandBus::remove)
            setController(null)
            mediaNotification = null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandBus.mediaControl = ::handleMedia
        runCatching { activeNotifications }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.filter { it.isOngoing }
            ?.forEach(::onNotificationPosted)
    }

    override fun onListenerDisconnected() {
        IslandBus.mediaControl = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val token = sessionToken(n)
        val isMedia = token != null || n.category == Notification.CATEGORY_TRANSPORT

        if (isMedia) {
            mediaNotification = sbn
            setController(findMediaController(sbn, token))
            schedulePublish()
            return
        }

        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val mode = when {
            n.category == Notification.CATEGORY_CALL -> IslandMode.CALL
            n.category == Notification.CATEGORY_NAVIGATION && sbn.isOngoing -> IslandMode.NAVIGATION
            sbn.isOngoing && (chronometer || n.category == Notification.CATEGORY_ALARM || n.category == "stopwatch") -> IslandMode.TIMER
            sbn.isOngoing -> return // downloads, "app is running", etc.
            else -> IslandMode.MESSAGE
        }

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.takeIf { it.isNotBlank() }
            ?: AppStyle.label(this, sbn.packageName)
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()

        if (mode == IslandMode.MESSAGE) {
            if (!isAlerting(sbn)) return
            val signature = "$title|$text"
            if (lastMessageText[sbn.key] == signature) return
            lastMessageText[sbn.key] = signature
            if (lastMessageText.size > 64) lastMessageText.keys.firstOrNull()?.let(lastMessageText::remove)
        }

        val actions = n.actions.orEmpty()
            .filter { it.actionIntent != null && (mode == IslandMode.CALL || it.remoteInputs.isNullOrEmpty()) }
            .map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) }
            .filter { it.label.isNotBlank() }

        val nativeCountDown = Build.VERSION.SDK_INT >= 24 && extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val parsedRemaining = if (mode == IslandMode.TIMER && !chronometer) {
            ClockTimeline.parseRemaining(text) ?: ClockTimeline.parseRemaining(title)
        } else null
        val paused = mode == IslandMode.TIMER && !chronometer &&
            (actions.any { it.label.equals("Resume", ignoreCase = true) || it.label.equals("Start", ignoreCase = true) } ||
                text.contains("paused", ignoreCase = true))
        val base = when {
            chronometer || mode == IslandMode.CALL -> n.`when`.takeIf { it > 0L }
            parsedRemaining != null && !paused -> System.currentTimeMillis() + parsedRemaining
            else -> null
        }

        IslandBus.post(LiveActivity(
            key = sbn.key,
            packageName = sbn.packageName,
            mode = mode,
            title = title,
            subtitle = text,
            appLabel = AppStyle.label(this, sbn.packageName),
            appIcon = AppStyle.icon(this, sbn.packageName),
            artwork = largeIcon(n),
            accent = AppStyle.accent(this, sbn.packageName),
            contentIntent = n.contentIntent,
            actions = actions,
            chronometerBase = base,
            chronometerCountDown = nativeCountDown || parsedRemaining != null,
            pausedTimerMs = if (paused) parsedRemaining else null,
        ))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastMessageText.remove(sbn.key)
        IslandBus.remove(sbn.key)
        if (sbn.key == mediaNotification?.key) {
            mediaNotification = null
            setController(null)
        }
    }

    override fun onDestroy() {
        setController(null)
        IslandBus.mediaControl = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- filtering

    private fun isAlerting(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        if (!currentRanking.getRanking(sbn.key, ranking)) return true
        return ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT && !ranking.isSuspended
    }

    // ---------------------------------------------------------------- media

    private fun schedulePublish() {
        // Players fire bursts of callbacks; coalesce them so the island updates once.
        main.removeCallbacks(publishMedia)
        main.postDelayed(publishMedia, 60L)
    }

    private fun handleMedia(command: String, position: Long?): Boolean {
        val c = controller ?: return false
        val controls = c.transportControls
        when (command) {
            IslandBus.MEDIA_TOGGLE ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            IslandBus.MEDIA_PREVIOUS -> controls.skipToPrevious()
            IslandBus.MEDIA_NEXT -> controls.skipToNext()
            IslandBus.MEDIA_SEEK -> controls.seekTo((position ?: 0L).coerceAtLeast(0L))
            else -> return false
        }
        return true
    }

    private fun findMediaController(sbn: StatusBarNotification, token: MediaSession.Token?): MediaController? {
        token?.let { t -> runCatching { MediaController(this, t) }.getOrNull()?.let { return it } }
        return runCatching {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, IslandNotificationListener::class.java))
                .filter { it.packageName == sbn.packageName }
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun sessionToken(n: Notification): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        else n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)

    private fun setController(next: MediaController?) {
        if (controller?.sessionToken == next?.sessionToken && next != null) return
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, main)
    }

    private fun publishCurrentMedia() {
        val sbn = mediaNotification ?: return
        val n = sbn.notification
        val extras = n.extras
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: return
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val state = controller?.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L

        // Artwork + its colour only change with the track, so cache them.
        val key = "$title|$artist"
        if (key != artKey || artBitmap == null) {
            artKey = key
            artBitmap = mediaArtwork(metadata, n)
            artAccent = artBitmap?.let(AppStyle::fromBitmap)
        }

        IslandBus.post(LiveActivity(
            key = sbn.key,
            packageName = sbn.packageName,
            mode = IslandMode.MUSIC,
            title = title,
            subtitle = artist,
            appLabel = AppStyle.label(this, sbn.packageName),
            appIcon = AppStyle.icon(this, sbn.packageName),
            artwork = artBitmap,
            accent = artAccent ?: AppStyle.accent(this, sbn.packageName),
            contentIntent = n.contentIntent ?: controller?.sessionActivity,
            actions = n.actions.orEmpty().filter { it.actionIntent != null }
                .map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) },
            isPlaying = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = duration,
            hasMediaSession = controller != null,
        ))
    }

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.toBitmap(160, 160)
    }.getOrNull()

    private fun mediaArtwork(metadata: MediaMetadata?, n: Notification): Bitmap? {
        val raw = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: artworkFromUri(metadata)
            ?: largeIcon(n)
            ?: return null
        return runCatching {
            val soft = if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
            if (soft.width > 320 || soft.height > 320) Bitmap.createScaledBitmap(soft, 320, 320, true) else soft
        }.getOrNull()
    }

    private fun artworkFromUri(metadata: MediaMetadata?): Bitmap? {
        val uriText = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: return null
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 400) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
}
