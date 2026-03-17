package com.csoft.musicapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray

import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class LoadingActivity : AppCompatActivity() {

    private var appOpenAd: AppOpenAd? = null
    private var isAdShowing = AtomicBoolean(false)
    private var isAdLoaded = false
    private val adUnitId = "ca-app-pub-3940256099942544/9257395921" // Test ID, reemplaza por el tuyo en producción

    private val PREFS_NAME = "musicapp_prefs"
    private val KEY_SAVED_DIRS = "saved_dirs"
    private val KEY_LAST_SELECTED = "last_selected_dir"
    private val CHANNEL_ID = "music_playback_channel"

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        // Inicializar Google Mobile Ads SDK
        /* MobileAds.initialize(this) { }
        loadAppOpenAd() */
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            proceedAfterPermission()
        }

        /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                proceedAfterPermission()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            proceedAfterPermission()
        } */
        // No llamar a proceedAfterPermission() aquí, se llamará tras mostrar el anuncio o si falla
        proceedAfterPermission() // Delete when ads are enabled
    }

    private fun loadAppOpenAd() {
        val request = AdRequest.Builder().build()
        // La API actual de AppOpenAd.load solo acepta 4 argumentos: context, adUnitId, adRequest, callback
        AppOpenAd.load(
            this,
            adUnitId,
            request,
            object : AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isAdLoaded = true
                    showAppOpenAdIfAvailable()
                }
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.d("LoadingActivity", "AppOpenAd failed to load: ${loadAdError.message}")
                    isAdLoaded = false
                    proceedAfterPermission()
                }
            }
        )
    }

    private fun showAppOpenAdIfAvailable() {
        if (isAdLoaded && !isAdShowing.get() && appOpenAd != null) {
            isAdShowing.set(true)
            appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    isAdShowing.set(false)
                    appOpenAd = null
                    // Continúa con el flujo normal después de cerrar el anuncio
                    proceedAfterPermission()
                }
                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    isAdShowing.set(false)
                    appOpenAd = null
                    proceedAfterPermission()
                }
                override fun onAdShowedFullScreenContent() {
                    // No hacer nada
                }
            }
            appOpenAd?.show(this)
        } else {
            // Si no hay anuncio, continuar normalmente
            proceedAfterPermission()
        }
    }

    private fun proceedAfterPermission() {
        // Si el anuncio ya se mostró o no está disponible, continuar
        // if (isAdShowing.get()) return
        /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        } */

        Thread {
            try {
                val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val json = prefs.getString(KEY_SAVED_DIRS, null)

                val names = ArrayList<String>()
                val uris = ArrayList<String>()

                if (!json.isNullOrEmpty()) {
                    try {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i)
                            val name = obj?.optString("name") ?: "Directorio"
                            val uriStr = obj?.optString("uri")
                            if (!uriStr.isNullOrEmpty()) {
                                try {
                                    val uri = Uri.parse(uriStr)
                                    val df = DocumentFile.fromTreeUri(this, uri)
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
                        val db = MusicDbHelper(this)
                        db.getTracksForDir(last)
                    } catch (e: Exception) {
                        // ignore DB preload errors
                    }
                }

                runOnUiThread {
                    val i = Intent(this@LoadingActivity, MainActivity::class.java)
                    if (names.isNotEmpty() && uris.isNotEmpty() && names.size == uris.size) {
                        i.putStringArrayListExtra("drawer_names", names)
                        i.putStringArrayListExtra("drawer_uris", uris)
                    }
                    if (!last.isNullOrEmpty()) i.putExtra("last_selected", last)
                    startActivity(i)
                    finish()
                }
            } finally {
                // nothing here because we already handled runOnUiThread above
            }
        }.start()
    }
}
