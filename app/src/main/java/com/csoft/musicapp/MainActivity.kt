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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.ImageButton
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Context
import com.google.android.material.navigation.NavigationView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MusicAdapter
    private val musicList = mutableListOf<MusicFile>()
    private lateinit var openDocumentTreeLauncher: ActivityResultLauncher<Uri?>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var navView: NavigationView
    private var playerService: MusicPlayerService? = null
    private var serviceBound: Boolean = false
    private var serviceListener: MusicPlayerService.ServiceListener? = null
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var loadingOverlay: View
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? MusicPlayerService.LocalBinder
            playerService = local?.getService()
            serviceBound = true
            // register UI listener
            playerService?.let { svc ->
                serviceListener = object : MusicPlayerService.ServiceListener {
                    override fun onMediaItemTransition(uri: String?) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            try {
                                val parsed = if (uri != null) Uri.parse(uri) else null
                                adapter.setPlayingUri(parsed)
                            } catch (e: Exception) {
                                Log.w(TAG, "onMediaItemTransition UI update failed", e)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            setPlayPauseIcon(isPlaying)
                        }
                    }

                    override fun onShuffleModeChanged(shuffleEnabled: Boolean) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            setShuffleIcon(shuffleEnabled)
                        }
                    }
                }
                serviceListener?.let { svc.registerListener(it) }

                // sync initial UI state (already on main thread)
                try {
                    val svc = playerService
                    if (svc != null) {
                        setPlayPauseIcon(svc.isPlaying())
                        setShuffleIcon(svc.isShuffleEnabled())
                        val cur = svc.getCurrentMediaUri()
                        if (cur is String && cur.isNotEmpty()) adapter.setPlayingUri(Uri.parse(cur))
                    } else {
                        setPlayPauseIcon(false)
                        setShuffleIcon(false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sync initial UI state failed", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
            serviceBound = false
        }
    }
    private lateinit var dbHelper: MusicDbHelper
    private lateinit var emptyHint: View

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "musicapp_prefs"
        private const val KEY_SAVED_DIRS = "saved_dirs"
        private const val KEY_MENU_ID_COUNTER = "menu_id_counter"
        private const val KEY_LAST_SELECTED = "last_selected_dir"
        private const val KEY_SHUFFLE_FOR_DIR_PREFIX = "shuffle_for_dir_"
        private const val DIR_MENU_GROUP = 100
        private const val HINT_ITEM_ID = 9999
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "MusicApp"

        drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
        loadingOverlay = findViewById(R.id.loading_overlay)

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

        // pull-to-refresh for current directory
        try {
            val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
            swipe.setOnRefreshListener {
                performRefresh(swipe)
            }
        } catch (e: Exception) {
            Log.w(TAG, "swipe refresh init failed", e)
        }

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
            Intent(this, MusicPlayerService::class.java).also { it.action = MusicPlayerService.ACTION_SKIP_PREV; startService(it) }
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
            val lastDir = getPrefs().getString(KEY_LAST_SELECTED, null)
            val currentShuffle = if (lastDir != null) getShuffleStateForDir(lastDir) else false
            val newShuffle = !currentShuffle

            applyShuffleStateToService(newShuffle)
            lastDir?.let { saveShuffleStateForDir(it, newShuffle) }
        }

        // initial UI state
        setPlayPauseIcon(false)
        setShuffleIcon(false)

        // NOTE: Playback updates (notification, media session) are handled in MusicPlayerService.

        emptyHint = findViewById(R.id.empty_hint_text)

        // Notification & media session handled by MusicPlayerService

        // Migrate LoadingActivity behavior: request permission and preload saved dirs
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            proceedAfterPermission()
        }

        proceedAfterPermission()

        openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: Exception) {
                        Log.w(TAG, "takePersistableUriPermission failed", e)
                    }

                val pickedDir = DocumentFile.fromTreeUri(this, uri)
                if (pickedDir != null && pickedDir.isDirectory) {
                    // add to drawer and persist
                    val rawName = pickedDir.name ?: uri.lastPathSegment ?: "Directorio"
                    // build list of existing saved names to compute a unique display name
                    val prefs = getPrefs()
                    val existingNames = mutableListOf<String>()
                    val jsonSaved = prefs.getString(KEY_SAVED_DIRS, null)
                    if (!jsonSaved.isNullOrEmpty()) {
                        try {
                            val arr = JSONArray(jsonSaved)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i)
                                val n = obj?.optString("name")
                                if (!n.isNullOrEmpty()) existingNames.add(n)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "malformed saved dirs JSON (when computing unique name)", e)
                        }
                    }

                    val displayName = nextDirectoryName(rawName, existingNames)
                    val assignedId = addDirectoryToSaved(displayName, uri.toString())
                    Log.d(TAG, "added directory: name=$displayName uri=${uri.toString()} assignedId=$assignedId")
                    // if menu already contains this id, avoid re-adding (prevents renaming existing item)
                    val menu = navView.menu
                    val existingItem = try { menu.findItem(assignedId) } catch (e: Exception) { null }
                    if (existingItem != null) {
                        // already saved: select existing item, persist last selection and show a brief message
                        try { navView.setCheckedItem(assignedId) } catch (e: Exception) { /* ignore */ }
                        emptyHint.visibility = View.GONE
                        getPrefs().edit().putString(KEY_LAST_SELECTED, uri.toString()).apply()
                        lifecycleScope.launch(Dispatchers.Main) {
                            try { Toast.makeText(this@MainActivity, "Directorio ya agregado", Toast.LENGTH_SHORT).show() } catch (e: Exception) { Log.w(TAG, "failed showing toast for existing dir", e) }
                        }
                    } else {
                        addDirectoryToDrawer(navView, displayName, uri, assignedId)
                        // hide empty hint and save as last selected
                        emptyHint.visibility = View.GONE
                        getPrefs().edit().putString(KEY_LAST_SELECTED, uri.toString()).apply()
                        try {
                            navView.setCheckedItem(assignedId)
                        } catch (e: Exception) {
                            // ignore
                        }

                        // show loading overlay and run enrichment (scan + persist) off UI thread
                        lifecycleScope.launch(Dispatchers.Main) {
                            Log.d(TAG, "showing loading overlay (pickedDir)")
                            try { loadingOverlay.bringToFront(); loadingOverlay.requestLayout(); loadingOverlay.invalidate() } catch (e: Exception) { Log.w(TAG, "bringToFront failed", e) }
                            loadingOverlay.visibility = android.view.View.VISIBLE
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            val results = mutableListOf<MusicFile>()
                            scanDocumentFile(pickedDir, results)
                            // sort tracks alphabetically by title (case-insensitive) before persisting and showing
                            results.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                            // persist tracks in DB
                            dbHelper.insertTracks(uri.toString(), results)
                            withContext(Dispatchers.Main) {
                                adapter.update(results)
                                Log.d(TAG, "scheduling hide of loading overlay after adapter update (pickedDir)")
                                recyclerView.post { try { loadingOverlay.visibility = android.view.View.GONE; Log.d(TAG, "loading overlay hidden (pickedDir)") } catch (e: Exception) { Log.w(TAG, "failed hiding loading overlay", e) } }
                            }
                        }
                    }
                }
            }
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            Log.d(TAG, "navView item clicked: id=${menuItem.itemId} data=${menuItem.intent?.data}")
            when (menuItem.itemId) {
                R.id.action_add_directory -> {
                    openDocumentTreeLauncher.launch(null)
                }
                else -> {
                    // Clear playback queue on directory switch
                    try {
                        if (serviceBound && playerService != null) {
                            playerService?.clearQueue()
                        } else {
                            Intent(this, MusicPlayerService::class.java).also {
                                it.action = MusicPlayerService.ACTION_CLEAR_QUEUE
                                startService(it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "failed clearing queue on directory switch", e)
                    }

                    // Load tracks for this directory from DB (do not rescan)
                    val intent = menuItem.intent
                    val dataUri = intent?.data
                    if (dataUri != null) {
                        val dirUriStr = dataUri.toString()
                        val shuffleState = getShuffleStateForDir(dirUriStr)
                        try {
                            applyShuffleStateToService(shuffleState)
                        } catch (e: Exception) {
                            Log.w(TAG, "failed applying shuffle state on directory switch", e)
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            val saved = dbHelper.getTracksForDir(dirUriStr)
                            withContext(Dispatchers.Main) {
                                renderDirectoryTracks(saved, dirUriStr, menuItem)
                                adapter.setPlayingUri(null)
                            }
                        }
                    }
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun renderDirectoryTracks(saved: List<MusicFile>, dirUriStr: String, menuItem: MenuItem? = null) {
        if (saved.isNotEmpty()) {
            adapter.update(saved)
            emptyHint.visibility = View.GONE
            getPrefs().edit().putString(KEY_LAST_SELECTED, dirUriStr).apply()
            menuItem?.let {
                it.isChecked = true
                navView.setCheckedItem(it.itemId)
            }
            recyclerView.post { try { loadingOverlay.visibility = android.view.View.GONE } catch (e: Exception) { Log.w(TAG, "failed hiding loading overlay", e) } }
        } else {
            Toast.makeText(this@MainActivity, "No hay pistas guardadas para este directorio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTracksForDir(dirUriStr: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = dbHelper.getTracksForDir(dirUriStr)
            withContext(Dispatchers.Main) {
                renderDirectoryTracks(saved, dirUriStr)
            }
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
                            Log.w(TAG, "metadata extraction failed for $name", e)
                            val dot = name.lastIndexOf('.')
                            if (dot > 0) title = name.substring(0, dot)
                        }

                out.add(MusicFile(title, artist, doc.uri, name))
            }
        }
    }

    private fun performRefresh(swipe: SwipeRefreshLayout) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dirUriStr = getPrefs().getString(KEY_LAST_SELECTED, null)
            if (dirUriStr.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    swipe.isRefreshing = false
                    Toast.makeText(this@MainActivity, "No hay directorio seleccionado", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val doc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(dirUriStr))
                if (doc == null || !doc.isDirectory) {
                    withContext(Dispatchers.Main) {
                        swipe.isRefreshing = false
                        Toast.makeText(this@MainActivity, "Directorio no accesible", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val scanned = mutableListOf<MusicFile>()
                scanDocumentFile(doc, scanned)
                // sort alphabetically by title
                scanned.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

                // replace DB entries for this dir atomically
                dbHelper.replaceTracksForDir(dirUriStr, scanned)

                withContext(Dispatchers.Main) {
                    adapter.update(scanned)
                    swipe.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                withContext(Dispatchers.Main) {
                    swipe.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Error al refrescar directorio", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getPrefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun getShuffleKeyForDir(dirUri: String): String = KEY_SHUFFLE_FOR_DIR_PREFIX + dirUri

    private fun getShuffleStateForDir(dirUri: String): Boolean = getPrefs().getBoolean(getShuffleKeyForDir(dirUri), false)

    private fun saveShuffleStateForDir(dirUri: String, enabled: Boolean) {
        getPrefs().edit().putBoolean(getShuffleKeyForDir(dirUri), enabled).apply()
    }

    private fun clearShuffleStateForDir(dirUri: String) {
        getPrefs().edit().remove(getShuffleKeyForDir(dirUri)).apply()
    }

    private fun applyShuffleStateToService(shuffleState: Boolean) {
        if (serviceBound && playerService != null) {
            playerService?.setShuffleEnabled(shuffleState)
        } else {
            Intent(this, MusicPlayerService::class.java).also {
                it.action = MusicPlayerService.ACTION_SET_SHUFFLE
                it.putExtra(MusicPlayerService.EXTRA_SHUFFLE_ENABLED, shuffleState)
                startService(it)
            }
        }
        setShuffleIcon(shuffleState)
    }

    private fun nextDirectoryName(base: String, existingNames: Collection<String>): String {
        if (!existingNames.contains(base)) return base
        val regex = Regex("^" + Regex.escape(base) + "(?: (\\d+))?$")
        var maxNum = 0
        for (name in existingNames) {
            val m = regex.matchEntire(name) ?: continue
            val numStr = m.groupValues.getOrNull(1)
            val num = numStr?.toIntOrNull()
            if (num != null) {
                if (num > maxNum) maxNum = num
            }
        }
        return if (maxNum == 0) "$base 2" else "$base ${maxNum + 1}"
    }

    private fun loadSavedDirectories(navView: NavigationView) {
        // show loading overlay while we prepare the drawer
        showLoadingOverlay()

        val prefs = getPrefs()
        val json = prefs.getString(KEY_SAVED_DIRS, null)
        if (json.isNullOrEmpty()) {
            // no saved dirs -> show hint in main activity
            val menu = navView.menu
            menu.removeGroup(DIR_MENU_GROUP)
            menu.removeItem(HINT_ITEM_ID)
            menu.setGroupCheckable(DIR_MENU_GROUP, true, true)
            emptyHint.visibility = View.VISIBLE
            // nothing to load -> hide overlay
            hideLoadingOverlay()
            return
        }

        val names = ArrayList<String>()
        val uris = ArrayList<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val name = obj?.optString("name") ?: "Directorio"
                val uriStr = obj?.optString("uri")
                if (!uriStr.isNullOrEmpty()) {
                    names.add(name)
                    uris.add(uriStr)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "malformed saved dirs JSON", e)
        }

        populateMenuWithDirs(navView, navView.menu, names, uris, prefs.getString(KEY_LAST_SELECTED, null))
        // populateMenuWithDirs will call loadTracksForDir when appropriate which will hide the overlay.
    }

    private fun populateDrawerFromIntent(navView: NavigationView) {
        val intent = intent
        val names = intent.getStringArrayListExtra("drawer_names")
        val uris = intent.getStringArrayListExtra("drawer_uris")
        val lastSelected = intent.getStringExtra("last_selected")

        val menu = navView.menu
        // delegate to helper if we have names/uris
        if (names != null && uris != null && names.size == uris.size && names.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.Main) {
                try { loadingOverlay.bringToFront(); loadingOverlay.requestLayout(); loadingOverlay.invalidate() } catch (e: Exception) { Log.w(TAG, "bringToFront failed", e) }
                loadingOverlay.visibility = android.view.View.VISIBLE
                Log.d(TAG, "showing loading overlay (populateDrawerFromIntent)")
            }
            populateMenuWithDirs(navView, menu, names, uris, lastSelected)
        } else {
            // fallback to prefs-based loading
            loadSavedDirectories(navView)
        }
    }

    private fun proceedAfterPermission() {
        showLoadingOverlay()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getPrefs()
                val json = prefs.getString(KEY_SAVED_DIRS, null)

                val names = ArrayList<String>()
                val uris = ArrayList<String>()

                if (!json.isNullOrEmpty()) {
                    try {
                        val arr = org.json.JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i)
                            val name = obj?.optString("name") ?: "Directorio"
                            val uriStr = obj?.optString("uri")
                            if (!uriStr.isNullOrEmpty()) {
                                try {
                                    val uri = Uri.parse(uriStr)
                                    val df = DocumentFile.fromTreeUri(this@MainActivity, uri)
                                    if (df != null && df.isDirectory) {
                                        names.add(name)
                                        uris.add(uriStr)
                                    }
                                } catch (e: Exception) {
                                    // ignore invalid entries
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // malformed JSON -> ignore
                    }
                }

                val last = prefs.getString(KEY_LAST_SELECTED, null)
                if (!last.isNullOrEmpty()) {
                    try {
                        val db = MusicDbHelper(this@MainActivity)
                        db.getTracksForDir(last)
                    } catch (e: Exception) {
                        // ignore DB preload errors
                    }
                }

                withContext(Dispatchers.Main) {
                    val menu = navView.menu
                    if (names.isNotEmpty() && uris.isNotEmpty() && names.size == uris.size) {
                        populateMenuWithDirs(navView, menu, names, uris, last)
                    } else {
                        // fallback to prefs-based loading
                        loadSavedDirectories(navView)
                    }

                    // Ensure shuffle state is restored for last selected directory on startup
                    if (!last.isNullOrEmpty()) {
                        try {
                            applyShuffleStateToService(getShuffleStateForDir(last))
                        } catch (e: Exception) {
                            Log.w(TAG, "failed applying saved shuffle state after permission", e)
                        }
                    }

                    // If adapter already has items hide overlay after layout, otherwise loaders (loadTracksForDir)
                    recyclerView.post { try { if (recyclerView.childCount > 0) { loadingOverlay.visibility = android.view.View.GONE; Log.d(TAG, "loading overlay hidden (proceedAfterPermission immediate)") } } catch (e: Exception) { Log.w(TAG, "failed hiding loading overlay", e) } }
                }
            } finally {
                // ensure overlay hidden in case of unexpected errors
                hideLoadingOverlay()
            }
        }
    }

    private fun addDirectoryToSaved(name: String, uriStr: String): Int {
        val prefs = getPrefs()
        val json = prefs.getString(KEY_SAVED_DIRS, null)
        val arr = if (json != null) JSONArray(json) else JSONArray()
        // avoid duplicates
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null && obj.optString("uri") == uriStr) {
                // return existing stored id if present, otherwise fallback to hashCode
                return obj.optInt("id", uriStr.hashCode())
            }
        }
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("uri", uriStr)
        // assign a stable unique id for menu item to avoid hashCode collisions
        val nextId = prefs.getInt(KEY_MENU_ID_COUNTER, 1)
        obj.put("id", nextId)
        // update counter and array in a single editor to avoid races
        val editor = prefs.edit()
        editor.putInt(KEY_MENU_ID_COUNTER, nextId + 1)
        arr.put(obj)
        editor.putString(KEY_SAVED_DIRS, arr.toString())
        editor.apply()
        return nextId
    }

    private fun addDirectoryToDrawer(navView: NavigationView, name: String, uri: Uri, menuId: Int? = null) {
        val menu = navView.menu
        addDirectoryMenuItem(menu, name, uri, menuId)
        // ensure group is checkable when adding dynamically
        menu.setGroupCheckable(DIR_MENU_GROUP, true, true)
    }

    private fun addDirectoryMenuItem(menu: Menu, name: String, uri: Uri, itemIdOverride: Int? = null) {
        // add under group so we can clear later if needed
        val itemId = itemIdOverride ?: getMenuItemIdForUri(uri.toString())
        val item = menu.add(DIR_MENU_GROUP, itemId, Menu.NONE, "")
        Log.d(TAG, "addDirectoryMenuItem: itemId=$itemId uri=${uri.toString()} name=$name")
        val intent = Intent()
        intent.data = uri
        item.intent = intent
        // no icon on the MenuItem itself so our actionView can occupy full width
        // allow the item to be checkable so navView.setCheckedItem works
        item.isCheckable = true

        try {
            val actionView = layoutInflater.inflate(R.layout.nav_dir_item, null)
            // ensure the inflated view fills available menu width
            try { actionView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) } catch (e: Exception) { Log.w(TAG, "failed setting actionView.layoutParams", e) }
            val tv = actionView.findViewById<TextView>(R.id.dir_name)
            val btn = actionView.findViewById<ImageButton>(R.id.btn_delete)
            tv.text = name

            // ensure delete button does not steal focus from menu selection
            try { btn.isFocusable = false; btn.isFocusableInTouchMode = false } catch (e: Exception) { Log.w(TAG, "failed setting delete button focusable", e) }

            btn.setOnClickListener {
                val dirUriStr = uri.toString()

                // Si la ruta actual de reproducción es de este directorio, limpiar cola.
                try {
                    val currentUri = playerService?.getCurrentMediaUri()
                    val isDirectoryCurrentlyPlaying = !currentUri.isNullOrEmpty() && currentUri.startsWith(dirUriStr)
                    val isMenuItemChecked = (navView.checkedItem?.itemId == itemId)
                    if (isDirectoryCurrentlyPlaying || isMenuItemChecked) {
                        if (serviceBound && playerService != null) {
                            playerService?.clearQueue()
                        } else {
                            Intent(this@MainActivity, MusicPlayerService::class.java).also {
                                it.action = MusicPlayerService.ACTION_CLEAR_QUEUE
                                startService(it)
                            }
                        }
                        adapter.setPlayingUri(null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "failed clearing queue for removed dir $dirUriStr", e)
                }

                // remove from saved prefs
                try {
                    val prefs = getPrefs()
                    val json = prefs.getString(KEY_SAVED_DIRS, null)
                    if (!json.isNullOrEmpty()) {
                        val arr = JSONArray(json)
                        val newArr = JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i)
                            if (obj != null && obj.optString("uri") != dirUriStr) {
                                newArr.put(obj)
                            }
                        }
                        prefs.edit().putString(KEY_SAVED_DIRS, newArr.toString()).apply()
                    }
                    clearShuffleStateForDir(dirUriStr)
                } catch (e: Exception) {
                    Log.w(TAG, "failed removing saved dir from prefs: $dirUriStr", e)
                }

                // delete tracks in DB off UI thread
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dbHelper.deleteTracksForDir(dirUriStr)
                    } catch (e: Exception) { Log.w(TAG, "failed deleting tracks for dir: $dirUriStr", e) }
                }

                // remove menu item and select first available
                    try {
                        menu.removeItem(itemId)
                    } catch (e: Exception) { Log.w(TAG, "failed removing menu item: $itemId", e) }

                // find first item in our group
                var firstItem: MenuItem? = null
                for (i in 0 until menu.size()) {
                    val it = menu.getItem(i)
                    if (it.groupId == DIR_MENU_GROUP) { firstItem = it; break }
                }

                if (firstItem != null) {
                    val firstUri = firstItem.intent?.data?.toString()
                    if (!firstUri.isNullOrEmpty()) {
                        loadTracksForDir(firstUri)
                        firstItem.isChecked = true
                        navView.setCheckedItem(firstItem.itemId)
                        getPrefs().edit().putString(KEY_LAST_SELECTED, firstUri).apply()
                        emptyHint.visibility = View.GONE
                    } else {
                        adapter.update(mutableListOf())
                        emptyHint.visibility = View.VISIBLE
                        getPrefs().edit().remove(KEY_LAST_SELECTED).apply()
                    }
                } else {
                    adapter.update(mutableListOf())
                    emptyHint.visibility = View.VISIBLE
                    getPrefs().edit().remove(KEY_LAST_SELECTED).apply()
                }
            }

            actionView.setOnClickListener {
                // forward click to the menu so selection behaves normally
                try { menu.performIdentifierAction(item.itemId, 0) } catch (e: Exception) { Log.w(TAG, "performIdentifierAction failed for ${item.itemId}", e) }
            }

            item.actionView = actionView
        } catch (e: Exception) {
            // fallback: keep plain title if inflation fails
            item.title = name
        }
    }

    private fun populateMenuWithDirs(navView: NavigationView, menu: Menu, names: List<String>, uris: List<String>, lastSelected: String?) {
        // clear previous entries
        menu.removeGroup(DIR_MENU_GROUP)
        menu.removeItem(HINT_ITEM_ID)
        menu.setGroupCheckable(DIR_MENU_GROUP, true, true)

        for (i in names.indices) {
            try {
                val name = names[i]
                val uri = Uri.parse(uris[i])
                addDirectoryMenuItem(menu, name, uri)
            } catch (e: Exception) {
                Log.w(TAG, "failed adding directory menu item: ${'$'}i", e)
            }
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

        // select lastSelected if present, otherwise last in group
        var selected: MenuItem? = null
        if (!lastSelected.isNullOrEmpty()) {
            for (i in 0 until menu.size()) {
                val it = menu.getItem(i)
                val dataUri = it.intent?.data
                if (dataUri != null && dataUri.toString() == lastSelected) {
                    selected = it
                    break
                }
            }
        }
        if (selected == null) {
            for (i in menu.size() - 1 downTo 0) {
                val it = menu.getItem(i)
                if (it.groupId == DIR_MENU_GROUP) { selected = it; break }
            }
        }
        if (selected != null) {
            val dirUri = selected.intent?.data?.toString()
            if (!dirUri.isNullOrEmpty()) {
                val shuffleState = getShuffleStateForDir(dirUri)
                try {
                    applyShuffleStateToService(shuffleState)
                } catch (e: Exception) {
                    Log.w(TAG, "failed applying shuffle state for selected dir", e)
                }

                loadTracksForDir(dirUri)
                selected.isChecked = true
                navView.setCheckedItem(selected.itemId)
            }
        }
    }

    private fun getMenuItemIdForUri(uriStr: String): Int {
        try {
            val prefs = getPrefs()
            val json = prefs.getString(KEY_SAVED_DIRS, null)
            if (!json.isNullOrEmpty()) {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null && obj.optString("uri") == uriStr) {
                        val id = obj.optInt("id", Int.MIN_VALUE)
                        if (id != Int.MIN_VALUE) return id
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getMenuItemIdForUri failed", e)
        }
        // fallback to hashCode if no stored id available
        return uriStr.hashCode()
    }

    private fun setPlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.pause_circle_24px else R.drawable.play_circle_24px)
    }

    private fun setShuffleIcon(shuffleEnabled: Boolean) {
        if (shuffleEnabled) {
            btnShuffle.setImageResource(R.drawable.shuffle_on_24px)
            btnShuffle.alpha = 1f
        } else {
            btnShuffle.setImageResource(R.drawable.shuffle_24px)
            btnShuffle.alpha = 0.6f
        }
    }

    private fun showLoadingOverlay() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                loadingOverlay.bringToFront(); loadingOverlay.requestLayout(); loadingOverlay.invalidate()
            } catch (e: Exception) {
                Log.w(TAG, "bringToFront failed", e)
            }
            loadingOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideLoadingOverlay() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                loadingOverlay.visibility = View.GONE
            } catch (e: Exception) {
                Log.w(TAG, "failed hiding loading overlay", e)
            }
        }
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
            // Modo aleatorio: crear una permutación aleatoria de la lista
            // pero asegurando que el ítem seleccionado sea el primer elemento.
            if (musicList.isEmpty()) {
                uris.add(musicFile.uri.toString())
                titles.add(musicFile.title)
                artists.add(musicFile.artist ?: "")
                startIndex = 0
            } else {
                // construir lista de índices restantes y mezclar
                val remaining = musicList.indices.toMutableList()
                if (startIndex >= 0) remaining.remove(startIndex)
                remaining.shuffle()

                // lista ordenada que tendrá primero el seleccionado
                val orderedUris = ArrayList<String>()
                val orderedTitles = ArrayList<String>()
                val orderedArtists = ArrayList<String>()

                if (startIndex >= 0) {
                    val sel = musicList[startIndex]
                    orderedUris.add(sel.uri.toString())
                    orderedTitles.add(sel.title)
                    orderedArtists.add(sel.artist ?: "")
                } else {
                    // si el item seleccionado no está en la lista, lo añadimos primero
                    orderedUris.add(musicFile.uri.toString())
                    orderedTitles.add(musicFile.title)
                    orderedArtists.add(musicFile.artist ?: "")
                }

                for (i in remaining) {
                    val mf = musicList[i]
                    // evitar duplicados si la uri coincide con el seleccionado añadido manualmente
                    if (mf.uri == musicFile.uri) continue
                    orderedUris.add(mf.uri.toString())
                    orderedTitles.add(mf.title)
                    orderedArtists.add(mf.artist ?: "")
                }

                uris.addAll(orderedUris)
                titles.addAll(orderedTitles)
                artists.addAll(orderedArtists)
                startIndex = 0
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
            Log.w(TAG, "adapter.setPlayingUri failed for ${musicFile.uri}", e)
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            try {
                serviceListener?.let { playerService?.unregisterListener(it) }
            } catch (e: Exception) { Log.w(TAG, "failed unregistering service listener", e) }
            try { unbindService(serviceConnection) } catch (e: Exception) { Log.w(TAG, "failed unbinding service", e) }
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
