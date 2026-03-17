package com.csoft.musicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class MusicPlayerService : Service() {

    interface ServiceListener {
        fun onMediaItemTransition(uri: String?)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onShuffleModeChanged(shuffleEnabled: Boolean)
    }

    private val listeners = mutableListOf<ServiceListener>()

    fun registerListener(l: ServiceListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun unregisterListener(l: ServiceListener) {
        listeners.remove(l)
    }

    companion object {
        const val ACTION_PLAY_QUEUE = "com.csoft.musicapp.action.PLAY_QUEUE"
        const val ACTION_PLAY_PAUSE = "com.csoft.musicapp.action.PLAY_PAUSE"
        const val ACTION_PAUSE = "com.csoft.musicapp.action.PAUSE"
        const val ACTION_SKIP_NEXT = "com.csoft.musicapp.action.SKIP_NEXT"
        const val ACTION_SKIP_PREV = "com.csoft.musicapp.action.SKIP_PREV"
        const val ACTION_TOGGLE_SHUFFLE = "com.csoft.musicapp.action.TOGGLE_SHUFFLE"
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_TITLES = "extra_titles"
        const val EXTRA_ARTISTS = "extra_artists"
        const val EXTRA_START_INDEX = "extra_start_index"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "music_playback_channel"
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSessionCompat? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()

        mediaSession = MediaSessionCompat(this, "MusicAppSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    player.play()
                }

                override fun onPause() {
                    player.pause()
                }

                override fun onSkipToNext() {
                    player.seekToNextMediaItem()
                }

                override fun onSkipToPrevious() {
                    player.seekToPreviousMediaItem()
                }

                override fun onSeekTo(pos: Long) {
                    player.seekTo(pos)
                }
            })
            isActive = true
        }

        createNotificationChannel()
        setupNotificationManager()

        player.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                    val pos = player.currentPosition
                    val pb = PlaybackStateCompat.Builder()
                        .setState(state, pos, if (isPlaying) 1f else 0f)
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        )
                        .build()
                    mediaSession?.setPlaybackState(pb)
                    // notify listeners
                    listeners.forEach { try { it.onIsPlayingChanged(isPlaying) } catch (_: Exception) {} }
                } catch (e: Exception) {
                    // ignore
                }
            }

            override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                listeners.forEach { try { it.onMediaItemTransition(uri) } catch (_: Exception) {} }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                listeners.forEach { try { it.onShuffleModeChanged(shuffleModeEnabled) } catch (_: Exception) {} }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_QUEUE -> {
                    val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
                    val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
                    val artists = intent.getStringArrayListExtra(EXTRA_ARTISTS) ?: arrayListOf()
                    val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    playQueue(uris, titles, artists, startIndex)
                    // Ensure service is foreground while playing
                    startForeground(NOTIFICATION_ID, buildEmptyNotification())
                }
                ACTION_PLAY_PAUSE -> {
                    if (player.isPlaying) player.pause() else player.play()
                }
                ACTION_PAUSE -> player.pause()
                ACTION_SKIP_NEXT -> player.seekToNextMediaItem()
                ACTION_SKIP_PREV -> player.seekToPreviousMediaItem()
                ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private fun playQueue(uris: List<String>, titles: List<String>, artists: List<String>, startIndex: Int) {
        val mediaItems = uris.mapIndexed { idx, u ->
            val mb = MediaMetadata.Builder().setTitle(titles.getOrNull(idx) ?: "MusicApp")
            val art = artists.getOrNull(idx)
            if (!art.isNullOrBlank()) mb.setArtist(art)
            MediaItem.Builder().setUri(u).setMediaMetadata(mb.build()).build()
        }
        player.setMediaItems(mediaItems, /* resetPosition= */true)
        player.prepare()
        player.seekTo(startIndex, 0)
        player.play()
    }

    fun pause() {
        player.pause()
        try {
            stopForeground(false)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun play() {
        player.play()
        try {
            startForeground(NOTIFICATION_ID, buildEmptyNotification())
        } catch (e: Exception) {
            // ignore
        }
    }
    fun isPlaying(): Boolean = player.isPlaying

    fun isShuffleEnabled(): Boolean = player.shuffleModeEnabled

    fun getCurrentMediaUri(): String? {
        return player.currentMediaItem?.localConfiguration?.uri?.toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun setupNotificationManager() {
        val mediaDescriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: com.google.android.exoplayer2.Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.title ?: "MusicApp"
            }

            override fun createCurrentContentIntent(player: com.google.android.exoplayer2.Player): PendingIntent? {
                val intent = Intent(this@MusicPlayerService, MainActivity::class.java)
                return PendingIntent.getActivity(this@MusicPlayerService, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }

            override fun getCurrentContentText(player: com.google.android.exoplayer2.Player): CharSequence? {
                return player.currentMediaItem?.mediaMetadata?.artist
            }

            override fun getCurrentLargeIcon(player: com.google.android.exoplayer2.Player, callback: PlayerNotificationManager.BitmapCallback): android.graphics.Bitmap? {
                return null
            }
        }

        playerNotificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.notification_channel_name)
            .setMediaDescriptionAdapter(mediaDescriptionAdapter)
            .setSmallIconResourceId(R.mipmap.ic_launcher)
            .build()

        playerNotificationManager?.setPlayer(player)
        mediaSession?.sessionToken?.let { token ->
            playerNotificationManager?.setMediaSessionToken(token)
        }
    }

    private fun buildEmptyNotification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
        }
        mediaSession = null
        player.release()
    }
}
