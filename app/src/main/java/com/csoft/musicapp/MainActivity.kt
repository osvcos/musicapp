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
import android.media.MediaMetadataRetriever
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem

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
    private lateinit var dbHelper: MusicDbHelper
    private val PREFS_NAME = "musicapp_prefs"
    private val KEY_SAVED_DIRS = "saved_dirs"
    private val KEY_LAST_SELECTED = "last_selected_dir"
    private val DIR_MENU_GROUP = 100
    private val HINT_ITEM_ID = 9999
    private lateinit var emptyHint: View

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
            play(musicFile)
        }
        recyclerView.adapter = adapter

        dbHelper = MusicDbHelper(this)

        playerView = findViewById(R.id.player_view)
        playerView.setControllerShowTimeoutMs(0)
        playerView.showController()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        emptyHint = findViewById(R.id.empty_hint_text)

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

        // Populate drawer with saved directories
        loadSavedDirectories(navView)

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
                    // add to drawer and persist
                    val displayName = pickedDir.name ?: uri.lastPathSegment ?: "Directorio"
                    addDirectoryToSaved(displayName, uri.toString())
                    addDirectoryToDrawer(navView, displayName, uri)
                    // hide empty hint and save as last selected
                    emptyHint.visibility = View.GONE
                    getPrefs().edit().putString(KEY_LAST_SELECTED, uri.toString()).apply()
                    try {
                        navView.setCheckedItem(uri.hashCode())
                    } catch (e: Exception) {
                        // ignore
                    }

                    // show loading overlay and run enrichment (scan + persist) off UI thread
                    val loading = findViewById<android.view.View>(R.id.loading_overlay)
                    runOnUiThread { loading.visibility = android.view.View.VISIBLE }

                    Thread {
                        val results = mutableListOf<MusicFile>()
                        scanDocumentFile(pickedDir, results)
                        // persist tracks in DB
                        dbHelper.insertTracks(uri.toString(), results)
                        runOnUiThread {
                            adapter.update(results)
                            loading.visibility = android.view.View.GONE
                            Toast.makeText(this, "${'$'}{results.size} archivos enriquecidos y guardados", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
            }
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_add_directory -> {
                    openDocumentTreeLauncher.launch(null)
                }
                else -> {
                    // Load tracks for this directory from DB (do not rescan)
                    val intent = menuItem.intent
                    val dataUri = intent?.data
                    if (dataUri != null) {
                        val dirUriStr = dataUri.toString()
                        Thread {
                            val saved = dbHelper.getTracksForDir(dirUriStr)
                            runOnUiThread {
                                if (saved.isNotEmpty()) {
                                    adapter.update(saved)
                                    Toast.makeText(this, "${'$'}{saved.size} pistas cargadas desde la base de datos", Toast.LENGTH_SHORT).show()
                                                    // hide empty hint when we have tracks
                                                    emptyHint.visibility = View.GONE
                                    // persist last selected dir and mark item checked
                                    getPrefs().edit().putString(KEY_LAST_SELECTED, dirUriStr).apply()
                                    menuItem.isChecked = true
                                    navView.setCheckedItem(menuItem.itemId)
                                } else {
                                    Toast.makeText(this, "No hay pistas guardadas para este directorio", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun loadTracksForDir(dirUriStr: String) {
        Thread {
            val saved = dbHelper.getTracksForDir(dirUriStr)
            runOnUiThread {
                if (saved.isNotEmpty()) {
                    adapter.update(saved)
                    Toast.makeText(this, "${'$'}{saved.size} pistas cargadas desde la base de datos", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No hay pistas guardadas para este directorio", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
                // extract title and artist metadata; fallback title to filename without extension
                var title = name
                var artist: String? = null
                try {
                    val uri = doc.uri
                    val retriever = MediaMetadataRetriever()
                    try {
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                        pfd?.use {
                            retriever.setDataSource(it.fileDescriptor)
                            val titleMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            val artistMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            if (!titleMeta.isNullOrBlank()) {
                                title = titleMeta
                            } else {
                                val dot = name.lastIndexOf('.')
                                if (dot > 0) title = name.substring(0, dot)
                            }
                            if (!artistMeta.isNullOrBlank()) {
                                artist = artistMeta
                            }
                        }
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    val dot = name.lastIndexOf('.')
                    if (dot > 0) title = name.substring(0, dot)
                }

                out.add(MusicFile(title, artist, doc.uri))
            }
        }
    }

    private fun getPrefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadSavedDirectories(navView: NavigationView) {
        val prefs = getPrefs()
        val json = prefs.getString(KEY_SAVED_DIRS, null)
        val menu = navView.menu
        // clear previous entries
        menu.removeGroup(DIR_MENU_GROUP)
        menu.removeItem(HINT_ITEM_ID)
        if (json.isNullOrEmpty()) {
            // no saved dirs -> show hint in main activity
            emptyHint.visibility = View.VISIBLE
            return
        }
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name")
                val uriStr = obj.optString("uri")
                if (uriStr.isNullOrEmpty()) continue
                val uri = Uri.parse(uriStr)
                addDirectoryMenuItem(menu, name, uri)
            }

            // count entries in our group
            var groupCount = 0
            for (i in 0 until menu.size()) {
                if (menu.getItem(i).groupId == DIR_MENU_GROUP) groupCount++
            }
            if (groupCount == 0) {
                emptyHint.visibility = View.VISIBLE
                return
            } else {
                emptyHint.visibility = View.GONE
            }

            // If there are saved directories, select last selected (or last in list)
            val lastSelected = prefs.getString(KEY_LAST_SELECTED, null)
            var selected: MenuItem? = null
            if (!lastSelected.isNullOrEmpty()) {
                for (i in 0 until menu.size()) {
                    val it = menu.getItem(i)
                    val intent = it.intent
                    val dataUri = intent?.data
                    if (dataUri != null && dataUri.toString() == lastSelected) {
                        selected = it
                        break
                    }
                }
            }
            if (selected == null) {
                // pick last menu item in our group
                for (i in menu.size() - 1 downTo 0) {
                    val it = menu.getItem(i)
                    if (it.groupId == DIR_MENU_GROUP) {
                        selected = it
                        break
                    }
                }
            }
            if (selected != null) {
                val dirUri = selected.intent?.data?.toString()
                if (!dirUri.isNullOrEmpty()) {
                    // load tracks and mark checked
                    loadTracksForDir(dirUri)
                    selected.isChecked = true
                    navView.setCheckedItem(selected.itemId)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun addDirectoryToSaved(name: String, uriStr: String) {
        val prefs = getPrefs()
        val json = prefs.getString(KEY_SAVED_DIRS, null)
        val arr = if (json != null) JSONArray(json) else JSONArray()
        // avoid duplicates
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null && obj.optString("uri") == uriStr) return
        }
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("uri", uriStr)
        arr.put(obj)
        prefs.edit().putString(KEY_SAVED_DIRS, arr.toString()).apply()
    }

    private fun addDirectoryToDrawer(navView: NavigationView, name: String, uri: Uri) {
        val menu = navView.menu
        addDirectoryMenuItem(menu, name, uri)
    }

    private fun addDirectoryMenuItem(menu: Menu, name: String, uri: Uri) {
        // add under group so we can clear later if needed
        val itemId = uri.hashCode()
        val item = menu.add(DIR_MENU_GROUP, itemId, Menu.NONE, name)
        val intent = Intent()
        intent.data = uri
        item.intent = intent
        item.setIcon(android.R.drawable.ic_menu_slideshow)
    }

    private fun play(musicFile: MusicFile) {
        playerView.visibility = View.VISIBLE
        playerView.showController()
        val metaBuilder = MediaMetadata.Builder().setTitle(musicFile.title)
        if (!musicFile.artist.isNullOrBlank()) metaBuilder.setArtist(musicFile.artist)
        val mediaItem = MediaItem.Builder()
            .setUri(musicFile.uri)
            .setMediaMetadata(metaBuilder.build())
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
