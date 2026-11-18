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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(repo: VaultRepository, files: List<VaultFile>, initialIndex: Int, onBack: () -> Unit, onChanged: () -> Unit) {
    val safeFiles = remember(files) { files.ifEmpty { emptyList() } }
    if (safeFiles.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(safeFiles.indices)) { safeFiles.size }
    var fs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val act = LocalContext.current as? Activity
    val view = LocalView.current
    LaunchedEffect(fs) { setFullscreen(act, view, fs) }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage != initialIndex) fs = true }
    DisposableEffect(Unit) { onDispose { setFullscreen(act, view, false) } }
    BackHandler(fs) { fs = false }
    val current = safeFiles[pager.currentPage]
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Konfirmasi Hapus") }, text = { Text("Apakah anda ingin benar menghapusnya? File akan masuk ke Sampah selama 30 hari.") }, confirmButton = { TextButton(onClick = { repo.delete(current); confirmDelete = false; onChanged(); onBack() }) { Text("Ya") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Tidak") } })
    Scaffold(containerColor = Color.Black,
        topBar = { if (!fs) TopAppBar(title = { Text(current.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1A))) },
        bottomBar = { if (!fs) BottomAppBar(containerColor = Color(0xFF0A0F1A)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val fav = repo.isFav(current)
                IconButton({ repo.setFav(current, !fav); onChanged() }) { Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (fav) Color.Red else Color.White) }
                IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, null) }
            } } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { safeFiles[it].file.absolutePath }) { page ->
                val f = safeFiles[page]
                key(f.file.absolutePath) {
                    val plain by produceState<File?>(null, f.file.absolutePath) { value = withContext(Dispatchers.IO) { repo.open(f) } }
                    DisposableEffect(f.file.absolutePath) { onDispose { plain?.delete() } }
                    plain?.let { p ->
                        when (f.type) {
                            MediaType.PHOTO -> ZoomImage(p) { fs = true }
                            MediaType.VIDEO -> VideoPlayer(p) { fs = !fs }
                            MediaType.COMIC -> CbzReader(p, fs) { fs = true }
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
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        c.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun ZoomImage(file: File, onInteraction: () -> Unit = {}) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offsetX by remember(file) { mutableFloatStateOf(0f) }
    var offsetY by remember(file) { mutableFloatStateOf(0f) }
    androidx.compose.foundation.Image(
        painter = coil.compose.rememberAsyncImagePainter(file),
        contentDescription = "Foto, cubit untuk memperbesar",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().clipToBounds()
             .pointerInput(file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (event.changes.any { (it.position - it.previousPosition).getDistance() > 1f }) onInteraction()
                        if (active.size >= 2) {
                            val a = active[0]; val b = active[1]
                            val oldDx = a.previousPosition.x - b.previousPosition.x
                            val oldDy = a.previousPosition.y - b.previousPosition.y
                            val newDx = a.position.x - b.position.x
                            val newDy = a.position.y - b.position.y
                            val oldDistance = kotlin.math.sqrt(oldDx * oldDx + oldDy * oldDy)
                            val newDistance = kotlin.math.sqrt(newDx * newDx + newDy * newDy)
                            val zoom = if (oldDistance > 0f) newDistance / oldDistance else 1f
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            val factor = next / scale
                            val panX = ((a.position.x + b.position.x) - (a.previousPosition.x + b.previousPosition.x)) / 2f
                            val panY = ((a.position.y + b.position.y) - (a.previousPosition.y + b.previousPosition.y)) / 2f
                            scale = next
                            offsetX = if (scale == 1f) 0f else (offsetX * factor + panX).coerceIn(-size.width.toFloat(), size.width.toFloat())
                            offsetY = if (scale == 1f) 0f else (offsetY * factor + panY).coerceIn(-size.height.toFloat(), size.height.toFloat())
                            event.changes.forEach { it.consume() }
                        } else if (active.size == 1 && scale > 1f) {
                            val change = active.first()
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            val maxX = size.width.toFloat() * (scale - 1f) / 2f
                            val maxY = size.height.toFloat() * (scale - 1f) / 2f
                            val atHorizontalEdge = (dx > 0f && offsetX >= maxX - 1f) || (dx < 0f && offsetX <= -maxX + 1f)
                            // Saat foto sudah mencapai tepi dan pengguna terus menggeser ke luar,
                            // jangan konsumsi gesture agar HorizontalPager dapat berpindah foto.
                            if (!atHorizontalEdge || kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                                offsetX = (offsetX + dx).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + dy).coerceIn(-maxY, maxY)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                    else scale = 2f
                })
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
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
fun CbzReader(file: File, fullscreen: Boolean = false, onInteraction: () -> Unit = {}) {
    val names = remember(file) {
        runCatching {
            ZipFile(file).use { z ->
                z.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                    .map { it.name }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                    .toList()
            }
        }.getOrElse { emptyList() }
    }
    var verticalMode by remember(file) { mutableStateOf(false) }
    val pager = rememberPagerState { names.size }
    var previousPage by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != previousPage) onInteraction()
        previousPage = pager.currentPage
    }
    val verticalListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollConnection = remember(onInteraction) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f) onInteraction()
                return Offset.Zero
            }
        }
    }
    if (names.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("CBZ tidak berisi gambar atau arsip tidak dapat dibaca", color = Color.White) }
        return
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) Row(
            Modifier.fillMaxWidth().background(Color(0xFF101010)).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !verticalMode,
                onClick = { verticalMode = false },
                label = { Text("Swipe ke samping") }
            )
            Spacer(Modifier.width(10.dp))
            FilterChip(
                selected = verticalMode,
                onClick = { verticalMode = true },
                label = { Text("Strip panjang") }
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (verticalMode) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = verticalListState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(names.size, key = { names[it] }) { index ->
                        val bmp by produceState<Bitmap?>(null, index, file) {
                            value = loadCbzPage(file, names[index])
                        }
                        if (bmp != null) {
                            Image(
                                bmp!!.asImageBitmap(),
                                contentDescription = "Halaman ${index + 1}",
                                modifier = Modifier.fillMaxWidth().wrapContentHeight().background(Color.White),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                if (!fullscreen) Text(
                    "${(verticalListState.firstVisibleItemIndex + 1).coerceAtMost(names.size)} / ${names.size}",
                    Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White
                )
                Canvas(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp)
                        .pointerInput(names.size) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    onInteraction()
                                    scope.launch { verticalListState.scrollToItem((p.y / size.height * names.size).toInt().coerceIn(0, names.lastIndex)) }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    onInteraction()
                                    scope.launch { verticalListState.scrollToItem((change.position.y / size.height * names.size).toInt().coerceIn(0, names.lastIndex)) }
                                }
                            )
                        }
                ) {
                    val h = size.height
                    val thumbH = (h / names.size.coerceAtLeast(1)).coerceAtLeast(30.dp.toPx())
                    val y = (h - thumbH) * (verticalListState.firstVisibleItemIndex.toFloat() / names.size.coerceAtLeast(1))
                    drawRoundRect(Color(0x55888888), Offset(size.width / 2 - 2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    drawRoundRect(Color.White, Offset(size.width / 2 - 3.dp.toPx(), y), androidx.compose.ui.geometry.Size(6.dp.toPx(), thumbH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                }
            } else {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val bmp by produceState<Bitmap?>(null, page, file) {
                        value = loadCbzPage(file, names[page])
                    }
                    bmp?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit)
                    } ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                }
                if (!fullscreen) Text(
                    "${pager.currentPage + 1} / ${names.size}",
                    Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White
                )
            }
        }
    }
}

private suspend fun loadCbzPage(file: File, entryName: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ZipFile(file).use { z ->
            val entry = z.getEntry(entryName) ?: return@use null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= 1800) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            z.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()
}
