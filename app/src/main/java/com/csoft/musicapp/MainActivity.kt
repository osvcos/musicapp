package com.csoft.musicapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.navigation.NavigationView
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MusicAdapter
    private val musicList = mutableListOf<MusicFile>()
    private lateinit var openDocumentTreeLauncher: ActivityResultLauncher<Uri?>
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var playerNotificationManager: PlayerNotificationManager? = null
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "music_playback_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "MusicApp"

        drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView: NavigationView = findViewById<NavigationView>(R.id.nav_view)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = MusicAdapter(musicList) { musicFile ->
            play(musicFile.uri)
        }
        recyclerView.adapter = adapter

        playerView = findViewById(R.id.player_view)
        playerView.setControllerShowTimeoutMs(0)
        playerView.showController()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // Request notification permission on Android 13+ and set up PlayerNotificationManager when granted
        val requestPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupNotificationManager()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                setupNotificationManager()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            setupNotificationManager()
        }

        openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    // ignore if permission not available
                }

                val pickedDir = DocumentFile.fromTreeUri(this, uri)
                if (pickedDir != null && pickedDir.isDirectory) {
                    val results = mutableListOf<MusicFile>()
                    scanDocumentFile(pickedDir, results)
                    adapter.update(results)
                    Toast.makeText(this, "${'$'}{results.size} archivos encontrados", Toast.LENGTH_SHORT).show()
                }
            }
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_add_directory -> {
                    openDocumentTreeLauncher.launch(null)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun scanDocumentFile(doc: DocumentFile, out: MutableList<MusicFile>) {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) {
                scanDocumentFile(child, out)
            }
        } else if (doc.isFile) {
            val type = doc.type ?: ""
            val name = doc.name ?: "Unknown"
            val lower = name.lowercase()
            val isAudio = type.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".aac")
            if (isAudio) {
                out.add(MusicFile(name, doc.uri))
            }
        }
    }

    private fun play(uri: Uri) {
        playerView.visibility = View.VISIBLE
        playerView.showController()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Unknown").build())
            .build()
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null
        player?.release()
        player = null
    }

    private fun setupNotificationManager() {
        val mediaDescriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: com.google.android.exoplayer2.Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.title ?: "MusicApp"
            }

            override fun createCurrentContentIntent(player: com.google.android.exoplayer2.Player): android.app.PendingIntent? {
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                return android.app.PendingIntent.getActivity(this@MainActivity, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            }

            override fun getCurrentContentText(player: com.google.android.exoplayer2.Player): CharSequence? {
                return player.currentMediaItem?.mediaMetadata?.artist
            }

            override fun getCurrentLargeIcon(player: com.google.android.exoplayer2.Player, callback: PlayerNotificationManager.BitmapCallback): android.graphics.Bitmap? {
                return null
            }
        }

        // Ensure notification channel exists on O+
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

        playerNotificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.notification_channel_name)
            .setMediaDescriptionAdapter(mediaDescriptionAdapter)
            .setSmallIconResourceId(R.mipmap.ic_launcher)
            .build()

        playerNotificationManager?.setPlayer(player)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
