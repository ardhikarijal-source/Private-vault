package com.example.privatevault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.privatevault.data.*
import com.example.privatevault.ui.*
import java.io.File

sealed interface Screen {
    data object Home : Screen; data object Settings : Screen; data object Fav : Screen; data object Trash : Screen; data object RootFiles : Screen
    data class Folder(val dir: File) : Screen; data class View(val files: List<VaultFile>, val index: Int, val back: Screen) : Screen
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blokir screenshot & thumbnail di recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val repo = VaultRepository(this); val pm = PinManager(this)
        repo.clearCache() // bersihkan sisa file sementara
        setContent {
            VaultTheme {
                var locked by remember { mutableStateOf(true) }
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var version by remember { mutableIntStateOf(0) }
                var folderScrollIndex by remember { mutableIntStateOf(0) }
                var folderScrollOffset by remember { mutableIntStateOf(0) }
                val changed: () -> Unit = { version++; Unit }
                // Kunci otomatis saat aplikasi ditutup / ke background
                var stopAt by remember { mutableLongStateOf(0L) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val o = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_STOP) stopAt = System.currentTimeMillis()
                        if (e == Lifecycle.Event.ON_START && stopAt > 0 && System.currentTimeMillis() - stopAt >= pm.autoLockMs) {
                            locked = true; screen = Screen.Home; repo.clearCache()
                        }
                    }
                    owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
                }
                androidx.activity.compose.BackHandler(enabled = !locked && screen != Screen.Home) { screen = when (val current = screen) { is Screen.View -> current.back; else -> Screen.Home } }
                if (locked) LockScreen(pm) { locked = false } else when (val s = screen) {
                    Screen.Home -> HomeScreen(repo, version, { screen = Screen.Folder(it) }, { file, files -> screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.Home) }, { screen = Screen.Fav }, { screen = Screen.Settings }, { screen = Screen.Trash }, { screen = Screen.RootFiles }, changed)
                    Screen.Settings -> SettingsScreen(pm) { screen = Screen.Home }
                    Screen.Trash -> TrashScreen(repo, { screen = Screen.Home }, changed)
                    Screen.RootFiles -> FolderScreen(repo, null, version, { screen = Screen.Home }, { file, files, _, _ -> screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.RootFiles) }, changed, rootFiles = true, onHome = { screen = Screen.Home }, onRootFiles = { screen = Screen.RootFiles }, onFav = { screen = Screen.Fav }, onSettings = { screen = Screen.Settings })
                    Screen.Fav -> FolderScreen(repo, null, version, { screen = Screen.Home }, { file, files, _, _ -> screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), Screen.Fav) }, changed, onHome = { screen = Screen.Home }, onRootFiles = { screen = Screen.RootFiles }, onFav = { screen = Screen.Fav })
                    is Screen.Folder -> FolderScreen(repo, s.dir, version, { screen = Screen.Home }, { file, files, index, offset -> folderScrollIndex = index; folderScrollOffset = offset; screen = Screen.View(files, files.indexOfFirst { it.file == file.file }.coerceAtLeast(0), s) }, changed, folderScrollIndex, folderScrollOffset)
                    is Screen.View -> ViewerScreen(repo, s.files, s.index, { screen = s.back }, changed)
                }
            }
        }
    }
}
