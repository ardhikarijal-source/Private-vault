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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember(ctx) { ctx.findActivity() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Mode landscape: sembunyikan status bar & navigation bar agar konten penuh layar; kembalikan saat portrait/keluar.
    LaunchedEffect(landscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED // kembali mengikuti pengaturan sistem
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    val toggleOrientation = {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

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
                DisposableEffect(f.file.absolutePath) { onDispose { opened.second?.delete() } }
                val plain = opened.second
                if (plain != null) {
                    when (f.type) {
                        MediaType.PHOTO -> ZoomImage(plain,
                            onInteraction = { controlsVisible = false },
                            onTap = { controlsVisible = !controlsVisible })
                        MediaType.VIDEO -> VideoPlayer(plain, positionKey = f.file.absolutePath, active = pager.currentPage == page,
                            controlsVisible = controlsVisible, onControlsChange = { controlsVisible = it },
                            landscape = landscape, onToggleOrientation = toggleOrientation)
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
                // Tombol putar layar (portrait <-> landscape), berguna saat rotasi otomatis di HP mati.
                IconButton(
                    onClick = toggleOrientation,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .background(Color(0xAA101827), androidx.compose.foundation.shape.CircleShape)
                ) { Icon(Icons.Default.ScreenRotation, "Putar layar", tint = Color.White) }
                // Penghitung file hanya untuk foto; video dan komik punya kontrol sendiri di bagian bawah.
                if (safeFiles.size > 1 && current.type == MediaType.PHOTO) Text(
                    "${pager.currentPage + 1} / ${safeFiles.size}",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                        .background(Color(0xAA101827), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
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

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
}

/** Posisi terakhir tiap video (hanya di memori) supaya dilanjutkan dari tempat terakhir ditonton. */
private object VideoPositions {
    private val map = HashMap<String, Long>()
    fun get(key: String): Long? = map[key]
    fun put(key: String, pos: Long, duration: Long) { if (duration > 0 && pos > 3_000 && pos < duration - 3_000) map[key] = pos else map.remove(key) }
    fun clear() = map.clear()
}

/**
 * Pemutar video dengan kontrol sendiri: putar/jeda, mundur/maju 10 dtk (juga ketuk dua kali di sisi layar),
 * bilah geser, kecepatan 0,5x-2x, ulangi, bisukan, layar penuh/landscape, geser vertikal kiri = kecerahan,
 * kanan = volume, dan melanjutkan dari posisi terakhir.
 */
@Composable
fun VideoPlayer(
    file: File,
    positionKey: String,
    active: Boolean,
    controlsVisible: Boolean,
    onControlsChange: (Boolean) -> Unit,
    landscape: Boolean,
    onToggleOrientation: () -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember(ctx) { ctx.findActivity() }
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            VideoPositions.get(positionKey)?.let { seekTo(it) }
            prepare()
            playWhenReady = active
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekShown by remember { mutableStateOf(false) }
    var seekForward by remember { mutableStateOf(true) }
    var seekTick by remember { mutableIntStateOf(0) }
    var hudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var hudValue by remember { mutableFloatStateOf(0f) }
    var hudTick by remember { mutableIntStateOf(0) }
    val controls by rememberUpdatedState(controlsVisible)
    val setControls by rememberUpdatedState(onControlsChange)

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    var brightness by remember {
        mutableFloatStateOf(((activity?.window?.attributes?.screenBrightness ?: -1f).takeIf { it >= 0f }) ?: 0.5f)
    }
    var volumeFraction by remember {
        mutableFloatStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1))
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L).let { if (dur > 0) it.coerceAtMost(dur) else it }
        player.seekTo(target); position = target
    }
    fun togglePlay() {
        if (ended) { player.seekTo(0); player.play() } else if (player.isPlaying) player.pause() else player.play()
        interaction++
    }
    fun setBrightness(v: Float) {
        brightness = v.coerceIn(0.02f, 1f)
        activity?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = brightness; w.attributes = lp }
        hudIcon = Icons.Default.BrightnessMedium; hudValue = brightness; hudTick++
    }
    fun setVolume(f: Float) {
        volumeFraction = f.coerceIn(0f, 1f)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (volumeFraction * max).roundToInt(), 0)
        hudIcon = if (volumeFraction == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp; hudValue = volumeFraction; hudTick++
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            VideoPositions.put(positionKey, player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose { // kembalikan kecerahan layar ke pengaturan sistem
            activity?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; w.attributes = lp }
        }
    }
    DisposableEffect(isPlaying) { view.keepScreenOn = isPlaying; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(active) { player.playWhenReady = active }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(player) {
        while (true) {
            if (controls && !dragging) position = player.currentPosition
            if (duration <= 0L) duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    // Kontrol hilang sendiri setelah beberapa detik saat video diputar.
    LaunchedEffect(controls, isPlaying, dragging, interaction, speedMenu) {
        if (controls && isPlaying && !dragging && !speedMenu) { delay(3500); setControls(false) }
    }
    LaunchedEffect(seekTick) { if (seekTick > 0) { seekShown = true; delay(600); seekShown = false } }
    LaunchedEffect(hudTick) { if (hudTick > 0) { delay(900); hudIcon = null } }
    // Jangan biarkan suara video terus berjalan saat aplikasi ke background / terkunci.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply {
            this.player = player
            useController = false
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        } }, Modifier.fillMaxSize())

        // Lapisan gestur: ketuk = tampil/sembunyi kontrol, ketuk dua kali = loncat 10 dtk, geser vertikal = kecerahan/volume.
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { setControls(!controls) },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2f
                        seekBy(if (forward) 10_000L else -10_000L)
                        seekForward = forward; seekTick++
                    })
            }
            .pointerInput(Unit) {
                var leftSide = true
                detectVerticalDragGestures(
                    onDragStart = { leftSide = it.x < size.width / 2f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val delta = -dy / size.height * 1.2f
                        if (leftSide) setBrightness(brightness + delta) else setVolume(volumeFraction + delta)
                    })
            })

        if (seekShown) Row(
            Modifier.align(if (seekForward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp)
                .background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (seekForward) Icons.Default.Forward10 else Icons.Default.Replay10, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(if (seekForward) "+10 dtk" else "-10 dtk", color = Color.White, fontSize = 13.sp)
        }
        hudIcon?.let { icon ->
            Column(Modifier.align(Alignment.Center).background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { hudValue }, modifier = Modifier.width(96.dp), color = Accent, trackColor = Color(0x55FFFFFF))
            }
        }

        androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ seekBy(-10_000L); interaction++ }, Modifier.size(52.dp)) { Icon(Icons.Default.Replay10, "Mundur 10 detik", tint = Color.White, modifier = Modifier.size(34.dp)) }
                    Box(Modifier.size(68.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x99101827)).clickable { togglePlay() }, contentAlignment = Alignment.Center) {
                        if (buffering && !isPlaying) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                        else Icon(if (ended) Icons.Default.Replay else if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar atau jeda", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton({ seekBy(10_000L); interaction++ }, Modifier.size(52.dp)) { Icon(Icons.Default.Forward10, "Maju 10 detik", tint = Color.White, modifier = Modifier.size(34.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val maxPos = duration.coerceAtLeast(1L).toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(if (dragging) dragValue.toLong() else position), color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = (if (dragging) dragValue else position.toFloat()).coerceIn(0f, maxPos),
                            onValueChange = { dragging = true; dragValue = it },
                            onValueChangeFinished = { player.seekTo(dragValue.toLong()); position = dragValue.toLong(); dragging = false; interaction++ },
                            valueRange = 0f..maxPos,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFB8CBFF), activeTrackColor = Accent, inactiveTrackColor = Color(0x66FFFFFF))
                        )
                        Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton({ speedMenu = true }) {
                                    Icon(Icons.Default.Speed, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text((if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "x", color = Color.White)
                                }
                                DropdownMenu(speedMenu, { speedMenu = false }) {
                                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                                        val label = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "x"
                                        DropdownMenuItem({ Text(if (sp == speed) "$label  ✓" else label) }, { speed = sp; speedMenu = false })
                                    }
                                }
                            }
                            IconButton({ loop = !loop; interaction++ }) { Icon(if (loop) Icons.Default.RepeatOne else Icons.Default.Repeat, "Ulangi video", tint = if (loop) Accent else Color.White) }
                            IconButton({ muted = !muted; interaction++ }) { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Bisukan", tint = Color.White) }
                        }
                        IconButton(onToggleOrientation) { Icon(if (landscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Layar penuh / landscape", tint = Color.White) }
                    }
                }
            }
        }
    }
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
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") }
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
    val zoom = remember(file) { CbzZoom() }
    zoom.vertical = verticalMode
    val zoomed by remember(zoom) { derivedStateOf { zoom.scale > 1.001f } }
    LaunchedEffect(verticalMode) { zoom.reset() }
    LaunchedEffect(pager.currentPage) { if (!verticalMode) zoom.reset() } // halaman baru mulai dari ukuran normal
    val density = LocalDensity.current
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().cbzZoom(zoom, zoomed, onTap = onToggleControls, onInteraction = onInteraction)) {
        if (verticalMode) {
            // Saat diperbesar, konten terpotong di bagian bawah; tambah ruang agar halaman terakhir tetap bisa dibaca.
            val extraBottom = with(density) { (zoom.height * (1f - 1f / zoom.scale)).toDp() }
            androidx.compose.foundation.lazy.LazyColumn(
                state = verticalListState,
                modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                contentPadding = PaddingValues(bottom = extraBottom),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(names.size, key = { names[it] }) { index ->
                    CbzPage(file, names[index], index, vertical = true)
                }
            }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed) { page ->
                CbzPage(file, names[page], page, vertical = false)
            }
        }
        }
        if (verticalMode) {
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
        }
        androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) { Box(Modifier.fillMaxSize()) {
            // Zoom: cubit dua jari, ketuk dua kali, atau pakai tombol ini.
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                    .background(Color(0xCC101827), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = { zoom.zoomBy(1.5f) }, enabled = zoom.scale < 4.99f) { Icon(Icons.Default.ZoomIn, "Perbesar", tint = Color.White) }
                Text("${(zoom.scale * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.clickable { zoom.reset() }.padding(vertical = 4.dp, horizontal = 6.dp))
                IconButton(onClick = { zoom.zoomBy(1f / 1.5f) }, enabled = zoom.scale > 1.001f) { Icon(Icons.Default.ZoomOut, "Perkecil", tint = Color.White) }
            }
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

/** Status zoom pembaca komik. Skala 1x-5x; offset dibatasi agar konten tidak keluar dari layar. */
@Stable
class CbzZoom {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var width = 0f
    var height = 0f
    /** Mode gulir panjang: zoom berpusat di tepi atas dan hanya bergeser horizontal (vertikal dipegang daftar). */
    var vertical = false

    fun reset() { scale = 1f; offsetX = 0f; offsetY = 0f }

    fun clamp() {
        val mx = width * (scale - 1f) / 2f
        val my = if (vertical) 0f else height * (scale - 1f) / 2f
        offsetX = offsetX.coerceIn(-mx, mx)
        offsetY = offsetY.coerceIn(-my, my)
    }

    /** Ubah skala dengan faktor [f] di sekitar titik fokus ([cx], [cy] relatif terhadap pusat layar), plus geseran [panX]/[panY]. */
    fun zoomBy(f: Float, cx: Float = 0f, cy: Float = 0f, panX: Float = 0f, panY: Float = 0f) {
        val next = (scale * f).coerceIn(1f, 5f)
        val real = next / scale
        scale = next
        if (scale <= 1.001f) { reset(); return }
        offsetX = real * offsetX + (1f - real) * cx + panX
        offsetY = if (vertical) 0f else real * offsetY + (1f - real) * cy + panY
        clamp()
    }
}

/**
 * Zoom + geser untuk pembaca komik: cubit dua jari memperbesar/memperkecil, ketuk dua kali memperbesar/mengembalikan,
 * seret saat diperbesar untuk menggeser, ketuk sekali menampilkan/menyembunyikan kontrol.
 * Cubitan dibaca di fase Initial sehingga daftar/pager di dalamnya tidak ikut bergulir.
 */
private fun Modifier.cbzZoom(z: CbzZoom, zoomed: Boolean, onTap: () -> Unit, onInteraction: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val interact by rememberUpdatedState(onInteraction)
    this
        .onSizeChanged { z.width = it.width.toFloat(); z.height = it.height.toFloat(); z.clamp() }
        .clipToBounds()
        .pointerInput(z) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val active = event.changes.filter { it.pressed }
                    if (active.size >= 2) {
                        val a = active[0]; val b = active[1]
                        val oldDistance = (a.previousPosition - b.previousPosition).getDistance()
                        val newDistance = (a.position - b.position).getDistance()
                        val factor = if (oldDistance > 0f) newDistance / oldDistance else 1f
                        val cx = (a.position.x + b.position.x) / 2f - size.width / 2f
                        val cy = (a.position.y + b.position.y) / 2f - size.height / 2f
                        val panX = ((a.position.x + b.position.x) - (a.previousPosition.x + b.previousPosition.x)) / 2f
                        val panY = ((a.position.y + b.position.y) - (a.previousPosition.y + b.previousPosition.y)) / 2f
                        z.zoomBy(factor, cx, cy, panX, panY)
                        interact()
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(z) {
            detectTapGestures(
                onTap = { tap() },
                onDoubleTap = { p ->
                    if (z.scale > 1.001f) z.reset()
                    else z.zoomBy(2.5f, p.x - size.width / 2f, p.y - size.height / 2f)
                }
            )
        }
        .then(
            if (!zoomed) Modifier else Modifier.pointerInput(z, z.vertical) {
                if (z.vertical) detectHorizontalDragGestures { change, dx -> change.consume(); z.offsetX += dx; z.clamp() }
                else detectDragGestures { change, drag -> change.consume(); z.offsetX += drag.x; z.offsetY += drag.y; z.clamp() }
            }
        )
        .graphicsLayer {
            scaleX = z.scale; scaleY = z.scale
            translationX = z.offsetX; translationY = z.offsetY
            transformOrigin = TransformOrigin(0.5f, if (z.vertical) 0f else 0.5f)
        }
}

/** Cache halaman komik (di memori saja) supaya membalik halaman tidak mendekode ulang. Dikosongkan saat aplikasi terkunci. */
object CbzCache {
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() { cache.evictAll(); VideoPositions.clear() }
}

/** Satu ImageLoader bersama, dengan dukungan GIF animasi. */
private object ViewerImages {
    private var instance: coil.ImageLoader? = null
    fun loader(ctx: android.content.Context): coil.ImageLoader = instance ?: coil.ImageLoader.Builder(ctx.applicationContext).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
    }.build().also { instance = it }
}

@Composable
private fun CbzPage(file: File, entryName: String, page: Int, vertical: Boolean) {
    val context = LocalContext.current
    val animated = remember(entryName) { entryName.substringAfterLast('.', "").lowercase() in setOf("gif", "webp") }
    if (animated) {
        val bytes by produceState<ByteArray?>(null, file, entryName) {
            value = withContext(Dispatchers.IO) {
                runCatching { ZipFile(file).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return@use null
                    zip.getInputStream(entry).use { it.readBytes() }
                } }.getOrNull()
            }
        }
        if (bytes != null) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(bytes).memoryCachePolicy(coil.request.CachePolicy.DISABLED).build(),
                imageLoader = ViewerImages.loader(context),
                contentDescription = "Halaman ${page + 1}",
                modifier = if (vertical) Modifier.fillMaxWidth().wrapContentHeight().background(Color.White)
                    else Modifier.fillMaxSize().background(Color.White),
                contentScale = if (vertical) ContentScale.FillWidth else ContentScale.Fit
            )
        } else Box(if (vertical) Modifier.fillMaxWidth().height(180.dp) else Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val bmp by produceState<Bitmap?>(null, page, file) { value = loadCbzPage(file, entryName) }
        bmp?.let {
            Image(it.asImageBitmap(), "Halaman ${page + 1}",
                if (vertical) Modifier.fillMaxWidth().wrapContentHeight().background(Color.White)
                else Modifier.fillMaxSize().background(Color.White),
                contentScale = if (vertical) ContentScale.FillWidth else ContentScale.Fit)
        } ?: Box(if (vertical) Modifier.fillMaxWidth().height(180.dp) else Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
    }
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
