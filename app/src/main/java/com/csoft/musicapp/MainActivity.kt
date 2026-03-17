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
import android.widget.ImageButton
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Context
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
    private var playerService: MusicPlayerService? = null
    private var serviceBound: Boolean = false
    private var serviceListener: MusicPlayerService.ServiceListener? = null
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicPlayerService.LocalBinder
            playerService = local?.getService()
            serviceBound = true
            // register UI listener
            playerService?.let { svc ->
                serviceListener = object : MusicPlayerService.ServiceListener {
                    override fun onMediaItemTransition(uri: String?) {
                        runOnUiThread {
                            try {
                                val parsed = if (uri != null) Uri.parse(uri) else null
                                adapter.setPlayingUri(parsed)
                            } catch (e: Exception) {
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        runOnUiThread {
                            if (isPlaying) btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                            else btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }

                    override fun onShuffleModeChanged(shuffleEnabled: Boolean) {
                        runOnUiThread {
                            btnShuffle.alpha = if (shuffleEnabled) 1f else 0.6f
                        }
                    }
                }
                serviceListener?.let { svc.registerListener(it) }

                // sync initial UI state
                runOnUiThread {
                    try {
                        val svc = playerService
                        if (svc != null) {
                            if (svc.isPlaying()) btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                            else btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            btnShuffle.alpha = if (svc.isShuffleEnabled()) 1f else 0.6f
                            val cur = svc.getCurrentMediaUri()
                            if (cur is String && cur.isNotEmpty()) adapter.setPlayingUri(Uri.parse(cur))
                        } else {
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            btnShuffle.alpha = 0.6f
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
            serviceBound = false
        }
    }
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

        // Start and bind to MusicPlayerService
        val svcIntent = Intent(this, MusicPlayerService::class.java)
        startService(svcIntent)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Custom floating controls
        btnPrev = findViewById<ImageButton>(R.id.btn_prev)
        btnPlayPause = findViewById<ImageButton>(R.id.btn_play_pause)
        btnNext = findViewById<ImageButton>(R.id.btn_next)
        btnShuffle = findViewById<ImageButton>(R.id.btn_shuffle)

        btnPrev.setOnClickListener {
            if (serviceBound) playerService?.let { Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_SKIP_PREV; startService(it) } }
            else Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_SKIP_PREV; startService(it) }
        }
        btnPlayPause.setOnClickListener {
            if (serviceBound && playerService != null) {
                if (playerService!!.isPlaying()) playerService!!.pause() else playerService!!.play()
            } else {
                Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_PLAY_PAUSE; startService(it) }
            }
        }
        btnNext.setOnClickListener {
            Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_SKIP_NEXT; startService(it) }
        }
        btnShuffle.setOnClickListener {
            Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_TOGGLE_SHUFFLE; startService(it) }
            btnShuffle.alpha = if (btnShuffle.alpha < 1f) 1f else 0.6f
        }

        // initial UI state
        btnShuffle.alpha = 0.6f
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)


        // NOTE: Playback updates (notification, media session) are handled in MusicPlayerService.

        emptyHint = findViewById(R.id.empty_hint_text)

        // Notification & media session handled by MusicPlayerService

        // Populate drawer: prefer data prepared by LoadingActivity, fallback to prefs
        populateDrawerFromIntent(navView)

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
                        // sort tracks alphabetically by title (case-insensitive) before persisting and showing
                        results.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        // persist tracks in DB
                        dbHelper.insertTracks(uri.toString(), results)
                        runOnUiThread {
                            adapter.update(results)
                            loading.visibility = android.view.View.GONE
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
        // make our group checkable (single selection)
        menu.setGroupCheckable(DIR_MENU_GROUP, true, true)
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

    private fun populateDrawerFromIntent(navView: NavigationView) {
        val intent = intent
        val names = intent.getStringArrayListExtra("drawer_names")
        val uris = intent.getStringArrayListExtra("drawer_uris")
        val lastSelected = intent.getStringExtra("last_selected")

        val menu = navView.menu
        // clear previous entries
        menu.removeGroup(DIR_MENU_GROUP)
        menu.removeItem(HINT_ITEM_ID)
        menu.setGroupCheckable(DIR_MENU_GROUP, true, true)

        if (names != null && uris != null && names.size == uris.size && names.isNotEmpty()) {
            for (i in 0 until names.size) {
                try {
                    val name = names[i]
                    val uri = Uri.parse(uris[i])
                    addDirectoryMenuItem(menu, name, uri)
                } catch (e: Exception) {
                    // ignore individual failures
                }
            }

            // mark last selected if present
            if (!lastSelected.isNullOrEmpty()) {
                for (i in 0 until menu.size()) {
                    val it = menu.getItem(i)
                    val dataUri = it.intent?.data
                    if (dataUri != null && dataUri.toString() == lastSelected) {
                        it.isChecked = true
                        navView.setCheckedItem(it.itemId)
                        loadTracksForDir(lastSelected)
                        break
                    }
                }
            } else {
                // if nothing selected, preserve MainActivity behavior to show hint or last item
                // count entries in our group
                var groupCount = 0
                for (i in 0 until menu.size()) {
                    if (menu.getItem(i).groupId == DIR_MENU_GROUP) groupCount++
                }
                if (groupCount == 0) {
                    emptyHint.visibility = View.VISIBLE
                } else {
                    emptyHint.visibility = View.GONE
                    // load last item by default
                    for (i in menu.size() - 1 downTo 0) {
                        val it = menu.getItem(i)
                        if (it.groupId == DIR_MENU_GROUP) {
                            val dirUri = it.intent?.data?.toString()
                            if (!dirUri.isNullOrEmpty()) {
                                loadTracksForDir(dirUri)
                                it.isChecked = true
                                navView.setCheckedItem(it.itemId)
                            }
                            break
                        }
                    }
                }
            }
        } else {
            // fallback to prefs-based loading
            loadSavedDirectories(navView)
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
        // ensure group is checkable when adding dynamically
        menu.setGroupCheckable(DIR_MENU_GROUP, true, true)
    }

    private fun addDirectoryMenuItem(menu: Menu, name: String, uri: Uri) {
        // add under group so we can clear later if needed
        val itemId = uri.hashCode()
        val item = menu.add(DIR_MENU_GROUP, itemId, Menu.NONE, name)
        val intent = Intent()
        intent.data = uri
        item.intent = intent
        item.setIcon(android.R.drawable.ic_menu_slideshow)
        // allow the item to be checkable so navView.setCheckedItem works
        item.isCheckable = true
    }

    private fun play(musicFile: MusicFile) {
        // Si el modo aleatorio está desactivado, la cola es toda la lista
        val uris = ArrayList<String>()
        val titles = ArrayList<String>()
        val artists = ArrayList<String>()
        val isShuffle = playerService?.isShuffleEnabled() == true
        var startIndex = musicList.indexOfFirst { it.uri == musicFile.uri }
        if (!isShuffle) {
            for (mf in musicList) {
                uris.add(mf.uri.toString())
                titles.add(mf.title)
                artists.add(mf.artist ?: "")
            }
        } else {
            // Si está en modo aleatorio, mantener el comportamiento anterior
            if (startIndex < 0) {
                uris.add(musicFile.uri.toString())
                titles.add(musicFile.title)
                artists.add(musicFile.artist ?: "")
                startIndex = 0
            } else {
                for (mf in musicList.subList(startIndex, musicList.size)) {
                    uris.add(mf.uri.toString())
                    titles.add(mf.title)
                    artists.add(mf.artist ?: "")
                }
            }
        }

        // Enviar el índice inicial al servicio
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            action = MusicPlayerService.ACTION_PLAY_QUEUE
            putStringArrayListExtra(MusicPlayerService.EXTRA_URIS, uris)
            putStringArrayListExtra(MusicPlayerService.EXTRA_TITLES, titles)
            putStringArrayListExtra(MusicPlayerService.EXTRA_ARTISTS, artists)
            putExtra(MusicPlayerService.EXTRA_START_INDEX, if (!isShuffle) startIndex else 0)
        }
        startService(intent)

        // highlight current playing item in the list
        try {
            adapter.setPlayingUri(musicFile.uri)
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            try {
                serviceListener?.let { playerService?.unregisterListener(it) }
            } catch (e: Exception) { }
            try { unbindService(serviceConnection) } catch (e: Exception) { }
            serviceBound = false
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
