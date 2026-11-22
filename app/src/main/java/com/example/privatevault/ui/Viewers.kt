package com.example.privatevault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.privatevault.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(repo: VaultRepository, files: List<VaultFile>, initialIndex: Int, onBack: () -> Unit, onChanged: () -> Unit) {
    val safeFiles = remember(files) { files.ifEmpty { emptyList() } }
    if (safeFiles.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(safeFiles.indices)) { safeFiles.size }
    var controlsVisible by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(false) }
    var favorite by remember(safeFiles[pager.currentPage].file.absolutePath) { mutableStateOf(repo.isFav(safeFiles[pager.currentPage])) }
    val current = safeFiles[pager.currentPage]
    val scope = rememberCoroutineScope()

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Hapus file?") },
        text = { Text("File akan dipindahkan ke Sampah selama 30 hari.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch { withContext(Dispatchers.IO) { repo.delete(current) }; onChanged(); onBack() }
        }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } }
    )
    if (infoVisible) AlertDialog(
        onDismissRequest = { infoVisible = false },
        title = { Text("Informasi File") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nama: ${current.name}")
            Text("Jenis: ${current.file.extension.uppercase()}")
            Text("Ukuran: ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, current.file.length())}")
            Text("Lokasi: ${current.file.parentFile?.name ?: "Penyimpanan"}")
            Text("Diubah: ${java.util.Date(current.file.lastModified())}")
        } },
        confirmButton = { TextButton({ infoVisible = false }) { Text("Tutup") } }
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { safeFiles[it].file.absolutePath }) { page ->
            val f = safeFiles[page]
            key(f.file.absolutePath) {
                // first = sudah selesai dicoba, second = file hasil dekripsi (null jika gagal)
                val opened by produceState<Pair<Boolean, File?>>(false to null, f.file.absolutePath) {
                    value = true to withContext(Dispatchers.IO) { runCatching { repo.open(f) }.getOrNull() }
                }
                val openedFile by rememberUpdatedState(opened.second)
                DisposableEffect(f.file.absolutePath) { onDispose { openedFile?.delete() } }
                val plain = opened.second
                if (plain != null) {
                    when (f.type) {
                        MediaType.PHOTO -> ZoomImage(plain,
                            onInteraction = { controlsVisible = false },
                            onTap = { controlsVisible = !controlsVisible })
                        MediaType.VIDEO -> VideoPlayer(plain, active = pager.currentPage == page,
                            onControlsVisibility = { controlsVisible = it })
                        MediaType.COMIC -> CbzReader(plain,
                            onInteraction = { controlsVisible = false },
                            controlsVisible = controlsVisible,
                            onToggleControls = { controlsVisible = !controlsVisible })
                        null -> Unit
                    }
                } else Box(Modifier.fillMaxSize(), Alignment.Center) {
                    if (!opened.first) CircularProgressIndicator() else Text("File tidak dapat dibuka", color = Color.White)
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Bayangan tipis di atas supaya tombol tetap terbaca di atas foto terang.
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))))
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .background(Color(0xAA101827), androidx.compose.foundation.shape.CircleShape)
                ) { Icon(Icons.Default.ArrowBack, "Kembali", tint = Color.White) }
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(Color(0xCC111827), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        favorite = !favorite
                        repo.setFav(current, favorite)
                        onChanged()
                    }) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit", tint = if (favorite) Color(0xFFFF6685) else Color.White) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Hapus", tint = Color.White) }
                    IconButton(onClick = { infoVisible = true }) { Icon(Icons.Default.Info, "Informasi", tint = Color.White) }
                }
                // Penghitung file dipindah ke pojok kanan atas agar tidak menabrak slider halaman komik di bawah.
                if (safeFiles.size > 1) Text(
                    "${pager.currentPage + 1} / ${safeFiles.size}",
                    Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 14.dp)
                        .background(Color(0xAA101827), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ZoomImage(file: File, onInteraction: () -> Unit = {}, onTap: () -> Unit = {}) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offsetX by remember(file) { mutableFloatStateOf(0f) }
    var offsetY by remember(file) { mutableFloatStateOf(0f) }
    val ctx = LocalContext.current
    val request = remember(file) {
        coil.request.ImageRequest.Builder(ctx).data(file).size(2048, 2048)
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED).build() // jangan simpan foto brankas di cache memori
    }
    coil.compose.AsyncImage(
        model = request,
        imageLoader = ViewerImages.loader(ctx),
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
                            val limitX = size.width.toFloat() * (scale - 1f) / 2f
                            val limitY = size.height.toFloat() * (scale - 1f) / 2f
                            offsetX = if (scale == 1f) 0f else (offsetX * factor + panX).coerceIn(-limitX, limitX)
                            offsetY = if (scale == 1f) 0f else (offsetY * factor + panY).coerceIn(-limitY, limitY)
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
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                        else scale = 2f
                    }
                )
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
fun VideoPlayer(file: File, active: Boolean = true, onControlsVisibility: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = active
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(active) { player.playWhenReady = active }
    // Jangan biarkan suara video terus berjalan saat aplikasi ke background / terkunci.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val visibilityCallback by rememberUpdatedState(onControlsVisibility)
    AndroidView({ PlayerView(it).apply {
        this.player = player
        useController = true
        controllerShowTimeoutMs = 2500
        setShowNextButton(false)
        setShowPreviousButton(false)
        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            visibilityCallback(visibility == View.VISIBLE)
        })
    } }, Modifier.fillMaxSize())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CbzReader(
    file: File,
    fullscreen: Boolean = false,
    onInteraction: () -> Unit = {},
    controlsVisible: Boolean = true,
    onToggleControls: () -> Unit = {}
) {
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
    val verticalListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentIndex = if (verticalMode) verticalListState.firstVisibleItemIndex else pager.currentPage
    val scrollConnection = remember(onInteraction) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f) onInteraction()
                return Offset.Zero
            }
        }
    }
    var previousPage by remember(file) { mutableIntStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != previousPage) onInteraction()
        previousPage = pager.currentPage
    }
    if (names.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("CBZ tidak berisi gambar atau arsip tidak dapat dibaca", color = Color.White) }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(file, verticalMode) {
        detectTapGestures(onTap = { onToggleControls() })
    }) {
        if (verticalMode) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = verticalListState,
                modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(names.size, key = { names[it] }) { index ->
                    val bmp by produceState<Bitmap?>(null, index, file) { value = loadCbzPage(file, names[index]) }
                    if (bmp != null) {
                        Image(bmp!!.asImageBitmap(), "Halaman ${index + 1}",
                            Modifier.fillMaxWidth().wrapContentHeight().background(Color.White),
                            contentScale = ContentScale.FillWidth)
                    } else Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (controlsVisible) Canvas(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 130.dp, bottom = 96.dp).width(28.dp)
                    .pointerInput(names.size) {
                        detectDragGestures(
                            onDragStart = { p ->
                                scope.launch { verticalListState.scrollToItem((p.y / size.height * names.size).toInt().coerceIn(0, names.lastIndex)) }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                scope.launch { verticalListState.scrollToItem((change.position.y / size.height * names.size).toInt().coerceIn(0, names.lastIndex)) }
                            },
                            onDragEnd = { onInteraction() },
                            onDragCancel = { onInteraction() }
                        )
                    }
            ) {
                val h = size.height
                val thumbH = (h / names.size.coerceAtLeast(1)).coerceAtLeast(30.dp.toPx())
                val y = (h - thumbH) * (verticalListState.firstVisibleItemIndex.toFloat() / names.size.coerceAtLeast(1))
                drawRoundRect(Color(0x55888888), Offset(size.width / 2 - 2.dp.toPx(), 0f),
                    androidx.compose.ui.geometry.Size(4.dp.toPx(), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(Color.White, Offset(size.width / 2 - 3.dp.toPx(), y),
                    androidx.compose.ui.geometry.Size(6.dp.toPx(), thumbH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val bmp by produceState<Bitmap?>(null, page, file) { value = loadCbzPage(file, names[page]) }
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit) }
                    ?: Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
        }
        androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) { Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = if (fullscreen) 10.dp else 76.dp)
                    .background(Color(0xCC101827), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = !verticalMode, onClick = { verticalMode = false }, label = { Text("Halaman") })
                FilterChip(selected = verticalMode, onClick = { verticalMode = true }, label = { Text("Gulir panjang") })
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)
                    .background(Color(0xDD101827), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                IconButton(onClick = {
                    if (verticalMode) scope.launch { verticalListState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0)) }
                    else scope.launch { pager.animateScrollToPage((currentIndex - 1).coerceAtLeast(0)) }
                }, enabled = currentIndex > 0) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                Text("${currentIndex + 1}", color = Color.White)
                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { value ->
                        val target = value.toInt().coerceIn(0, names.lastIndex)
                        if (verticalMode) scope.launch { verticalListState.scrollToItem(target) }
                        else scope.launch { pager.scrollToPage(target) }
                    },
                    valueRange = 0f..names.lastIndex.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFB8CBFF), activeTrackColor = Color(0xFF1D69D8), inactiveTrackColor = Color(0xFF667085))
                )
                Text("${names.size}", color = Color.White)
                IconButton(onClick = {
                    if (verticalMode) scope.launch { verticalListState.animateScrollToItem((currentIndex + 1).coerceAtMost(names.lastIndex)) }
                    else scope.launch { pager.animateScrollToPage((currentIndex + 1).coerceAtMost(names.lastIndex)) }
                }, enabled = currentIndex < names.lastIndex) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
            }
        } }
    }
}

/** Cache halaman komik (di memori saja) supaya membalik halaman tidak mendekode ulang. Dikosongkan saat aplikasi terkunci. */
object CbzCache {
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() = cache.evictAll()
}

/** Satu ImageLoader bersama, dengan dukungan GIF animasi. */
private object ViewerImages {
    private var instance: coil.ImageLoader? = null
    fun loader(ctx: android.content.Context): coil.ImageLoader = instance ?: coil.ImageLoader.Builder(ctx.applicationContext).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
    }.build().also { instance = it }
}

private suspend fun loadCbzPage(file: File, entryName: String): Bitmap? {
    val key = file.absolutePath + "#" + entryName
    val cachedPage = CbzCache.get(key)
    if (cachedPage != null) return cachedPage
    return withContext(Dispatchers.IO) {
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
        }.getOrNull()?.also { CbzCache.put(key, it) }
    }
}
