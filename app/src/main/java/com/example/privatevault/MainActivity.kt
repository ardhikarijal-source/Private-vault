package com.example.privatevault

import android.os.Bundle
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import com.example.privatevault.data.*
import com.example.privatevault.ui.*
import java.io.File

sealed interface Screen {
    data object Home : Screen; data object Settings : Screen; data object Fav : Screen; data object Trash : Screen; data object RootFiles : Screen
    data class Folder(val dir: File) : Screen
    data class Search(val query: String, val results: List<VaultFile>, val back: Screen) : Screen
    data class View(val files: List<VaultFile>, val index: Int, val back: Screen) : Screen
}

class MainActivity : FragmentActivity() {
    private var updateDownloadId: Long = -1L
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != updateDownloadId) return
            val installed = runCatching { AppUpdater.installDownloaded(this@MainActivity, id) }.getOrDefault(false)
            if (!installed) Toast.makeText(this@MainActivity, "Unduhan pembaruan gagal. Silakan coba lagi.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(updateReceiver) }
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 8/9 memerlukan izin tulis untuk folder publik Downloads.
        // Android 10+ memakai MediaStore sehingga tidak meminta izin storage luas.
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
        val updateFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, updateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateReceiver, updateFilter)
        }
        // Blokir screenshot & thumbnail di recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val repo = VaultRepository(this); val pm = PinManager(this)
        repo.clearCache() // bersihkan sisa file sementara
        setContent {
            VaultTheme {
                var locked by remember { mutableStateOf(true) }
                var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
                var checkingUpdates by remember { mutableStateOf(false) }
                val updateScope = rememberCoroutineScope()
                val checkUpdatesManually: () -> Unit = {
                    if (!checkingUpdates) {
                        checkingUpdates = true
                        updateScope.launch {
                            val result = runCatching { AppUpdater.check(this@MainActivity) }
                            checkingUpdates = false
                            result.onSuccess { update ->
                                if (update != null) availableUpdate = update
                                else Toast.makeText(this@MainActivity, "Aplikasi sudah menggunakan versi terbaru.", Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "Tidak dapat memeriksa pembaruan. Periksa koneksi internet.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    availableUpdate = runCatching { AppUpdater.check(this@MainActivity) }.getOrNull()
                }
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var version by remember { mutableIntStateOf(0) }
                var folderScrollIndex by remember { mutableIntStateOf(0) }
                var folderScrollOffset by remember { mutableIntStateOf(0) }
                var rootScrollIndex by remember { mutableIntStateOf(0) }
                var rootScrollOffset by remember { mutableIntStateOf(0) }
                var favScrollIndex by remember { mutableIntStateOf(0) }
                var favScrollOffset by remember { mutableIntStateOf(0) }
                val changed: () -> Unit = { version++; Unit }
                // Kunci otomatis saat aplikasi ditutup / ke background
                var stopAt by remember { mutableLongStateOf(0L) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_STOP) stopAt = System.currentTimeMillis()
                        if (e == Lifecycle.Event.ON_START) {
                            // Kembali dari pemilih file bukan alasan untuk mengunci (jika tidak, impor ikut batal).
                            val external = ExternalFlow.active; ExternalFlow.active = false
                            if (!external && stopAt > 0 && System.currentTimeMillis() - stopAt >= pm.autoLockMs) {
                                locked = true; screen = Screen.Home; repo.clearCache(); CbzCache.clear()
                            }
                            stopAt = 0L
                        }
                    }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                androidx.activity.compose.BackHandler(enabled = !locked && screen != Screen.Home) { screen = when (val current = screen) { is Screen.View -> current.back; else -> Screen.Home } }
                var clipboard by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
                if (locked) {
                    LockScreen(pm) { locked = false }
                } else {
                    val rootTab = screen == Screen.Home || screen == Screen.RootFiles || screen == Screen.Fav || screen == Screen.Settings
                    Scaffold(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF0B1220),
                        bottomBar = {
                            if (rootTab) NavigationBar(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                                    .border(1.dp, androidx.compose.ui.graphics.Color(0x665F79A8), androidx.compose.foundation.shape.RoundedCornerShape(26.dp)),
                                containerColor = androidx.compose.ui.graphics.Color(0xFF0D1527)
                            ) {
                                NavigationBarItem(screen == Screen.Home, { screen = Screen.Home }, { Icon(Icons.Default.Home, null) }, label = { Text("Beranda") })
                                NavigationBarItem(screen == Screen.RootFiles, { screen = Screen.RootFiles }, { Icon(Icons.Default.Image, null) }, label = { Text("Gambar") })
                                NavigationBarItem(screen == Screen.Fav, { screen = Screen.Fav }, { Icon(Icons.Default.FavoriteBorder, null) }, label = { Text("Favorit") })
                                NavigationBarItem(screen == Screen.Settings, { screen = Screen.Settings }, { Icon(Icons.Default.Settings, null) }, label = { Text("Pengaturan") })
                            }
                        }
                    ) { outerPadding ->
                        Box(Modifier.fillMaxSize().padding(outerPadding)) {
                            when (val s = screen) {
                                Screen.Home -> HomeScreen(repo, version,
                                    { screen = Screen.Folder(it) },
                                    { file, files -> screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.Home) },
                                    { screen = Screen.Fav }, { screen = Screen.Settings }, { screen = Screen.Trash }, { screen = Screen.RootFiles }, changed,
                                    showBottomNav = false,
                                    onSearch = { query, results -> if (results.isNotEmpty()) screen = Screen.Search(query, results, Screen.Home) else Toast.makeText(this@MainActivity, "Tidak ada file yang cocok.", Toast.LENGTH_SHORT).show() })
                                Screen.Settings -> SettingsScreen(pm, repo, changed, { screen = Screen.Home }, checkUpdatesManually, checkingUpdates)
                                Screen.Trash -> TrashScreen(repo, { screen = Screen.Home }, changed)
                                Screen.RootFiles -> FolderScreen(repo, null, version, { screen = Screen.Home },
                                    { file, files, index, offset ->
                                        rootScrollIndex = index; rootScrollOffset = offset
                                        screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.RootFiles)
                                    },
                                    changed, rootScrollIndex, rootScrollOffset, rootFiles = true, onHome = { screen = Screen.Home }, onRootFiles = { screen = Screen.RootFiles },
                                    onFav = { screen = Screen.Fav }, onSettings = { screen = Screen.Settings },
                                    clipboard = clipboard, onClipboardChange = { clipboard = it }, showBottomNav = false)
                                Screen.Fav -> FolderScreen(repo, null, version, { screen = Screen.Home },
                                    { file, files, index, offset ->
                                        favScrollIndex = index; favScrollOffset = offset
                                        screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.Fav)
                                    },
                                    changed, favScrollIndex, favScrollOffset, onHome = { screen = Screen.Home }, onRootFiles = { screen = Screen.RootFiles },
                                    onFav = { screen = Screen.Fav }, onSettings = { screen = Screen.Settings },
                                    clipboard = clipboard, onClipboardChange = { clipboard = it }, showBottomNav = false)
                                is Screen.Folder -> FolderScreen(repo, s.dir, version, { screen = Screen.Home },
                                    { file, files, index, offset ->
                                        folderScrollIndex = index; folderScrollOffset = offset
                                        screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), s)
                                    }, changed, folderScrollIndex, folderScrollOffset,
                                    clipboard = clipboard, onClipboardChange = { clipboard = it }, showBottomNav = false)
                                is Screen.Search -> SearchScreen(s.query, s.results, repo, { screen = s.back }) { file, files ->
                                    screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), s)
                                }
                                is Screen.View -> ViewerScreen(repo, s.files, s.index, { screen = s.back }, changed)
                            }
                        }
                    }
                    OpsHost() // dialog progres/hasil backup & pemulihan
                    availableUpdate?.let { update ->
                        AlertDialog(
                            onDismissRequest = { availableUpdate = null },
                            title = { Text("Pembaruan tersedia") },
                            text = { Text("Dhika App versi ${update.versionName} tersedia. Perbarui aplikasi sekarang? Data vault tetap tersimpan.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (!AppUpdater.canInstallPackages(this@MainActivity)) {
                                        Toast.makeText(this@MainActivity, "Izinkan pemasangan aplikasi dari sumber ini, lalu tekan Update kembali.", Toast.LENGTH_LONG).show()
                                        AppUpdater.openInstallPermission(this@MainActivity)
                                    } else {
                                        updateDownloadId = AppUpdater.enqueueDownload(this@MainActivity, update.apkUrl)
                                        availableUpdate = null
                                        Toast.makeText(this@MainActivity, "Pembaruan sedang diunduh. Buka notifikasi unduhan jika pemasang tidak muncul otomatis.", Toast.LENGTH_LONG).show()
                                    }
                                }) { Text("Update") }
                            },
                            dismissButton = {
                                TextButton(onClick = { availableUpdate = null }) { Text("Nanti") }
                            }
                        )
                    }
                }

            }
        }
    }
}
