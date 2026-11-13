package com.example.privatevault.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.calculateZoom
import androidx.compose.ui.input.pointer.calculatePan
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.privatevault.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(repo: VaultRepository, files: List<VaultFile>, initialIndex: Int, onBack: () -> Unit, onChanged: () -> Unit) {
    val safeFiles = remember(files) { files.ifEmpty { emptyList() } }
    if (safeFiles.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(safeFiles.indices)) { safeFiles.size }
    var fs by remember { mutableStateOf(false) }
    val act = LocalContext.current as? Activity
    val view = LocalView.current
    LaunchedEffect(fs) { setFullscreen(act, view, fs) }
    DisposableEffect(Unit) { onDispose { setFullscreen(act, view, false) } }
    BackHandler(fs) { fs = false }
    val current = safeFiles[pager.currentPage]
    Scaffold(containerColor = Color.Black,
        topBar = { if (!fs) TopAppBar(title = { Text(current.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1A))) },
        bottomBar = { if (!fs) BottomAppBar(containerColor = Color(0xFF0A0F1A)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val fav = repo.isFav(current)
                IconButton({ repo.setFav(current, !fav); onChanged() }) { Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (fav) Color.Red else Color.White) }
                IconButton({ repo.delete(current); onChanged(); onBack() }) { Icon(Icons.Default.Delete, null) }
            } } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { safeFiles[it].file.absolutePath }) { page ->
                val f = safeFiles[page]
                key(f.file.absolutePath) {
                    val plain by produceState<File?>(null, f.file.absolutePath) { value = withContext(Dispatchers.IO) { repo.open(f) } }
                    DisposableEffect(f.file.absolutePath) { onDispose { plain?.delete() } }
                    plain?.let { p ->
                        when (f.type) {
                            MediaType.PHOTO -> ZoomImage(p)
                            MediaType.VIDEO -> VideoPlayer(p) { fs = !fs }
                            MediaType.COMIC -> CbzReader(p)
                            null -> Unit
                        }
                    } ?: CircularProgressIndicator()
                }
            }
            Text("${pager.currentPage + 1} / ${safeFiles.size}", Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White)
        }
    }
}

private fun setFullscreen(act: Activity?, view: View, on: Boolean) {
    val w = act?.window ?: return
    val c = WindowCompat.getInsetsController(w, view)
    if (on) {
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        c.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun ZoomImage(file: File) {
    var scale by remember(file) { mutableStateOf(1f) }
    var off by remember(file) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.foundation.Image(
        painter = coil.compose.rememberAsyncImagePainter(file),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().clipToBounds()
            .pointerInput(file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            scale = next
                            off = if (next == 1f) androidx.compose.ui.geometry.Offset.Zero else off + event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)
    )
}

@Composable
fun VideoPlayer(file: File, onFullscreen: () -> Unit) {
    val toggleFs by rememberUpdatedState(onFullscreen)
    val ctx = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false); setFullscreenButtonClickListener { toggleFs() } } }, Modifier.fillMaxSize())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CbzReader(file: File) {
    val names = remember(file) { ZipFile(file).use { z -> z.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }.map { it.name }.sortedWith(compareBy { it.lowercase() }).toList() } }
    if (names.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("CBZ tidak berisi gambar") }; return }
    val pager = rememberPagerState { names.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val bmp by produceState<Bitmap?>(null, page, file) {
                value = withContext(Dispatchers.IO) { ZipFile(file).use { z ->
                    val e = z.getEntry(names[page]); val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    z.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) } } }
            }
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit) } ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        }
        Text("${pager.currentPage + 1} / ${names.size}", Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White)
    }
}
