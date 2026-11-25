/** Satu halaman CBZ yang sudah diekstrak ke berkas cache lokal sementara. */
@Stable
class CbzPageInfo(val name: String, val file: File, val width: Int, val height: Int, val animated: Boolean) {
    val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0.7f
}

class CbzBook(val dir: File, val pages: List<CbzPageInfo>)

/**
 * Pintu masuk pembaca CBZ. Loading HANYA terjadi sekali saat pertama dibuka: semua halaman diekstrak ke cache
 * lokal, setelah itu pembaca tetap terbuka tanpa loading ulang saat menggulir naik/turun.
 */
@Composable
fun CbzReader(
    file: File,
    fullscreen: Boolean = false,
    onInteraction: () -> Unit = {},
    controlsVisible: Boolean = true,
    onToggleControls: () -> Unit = {},
    onSetCover: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    var done by remember(file) { mutableIntStateOf(0) }
    var total by remember(file) { mutableIntStateOf(0) }
    var finished by remember(file) { mutableStateOf(false) }
    val book by produceState<CbzBook?>(null, file.absolutePath) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                val names = ZipFile(file).use { z -> ComicPages.pages(z) }
                total = names.size
                prepareCbzBook(ctx, file, names) { done = it }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        finished = true
    }
    val ready = book
    DisposableEffect(ready) {
        onDispose {
            if (ready != null) {
                CbzCache.evictPrefix(ready.dir.absolutePath)
                Thread { ready.dir.deleteRecursively() }.start()
            }
        }
    }
    when {
        ready != null && ready.pages.isNotEmpty() ->
            CbzReaderContent(file, ready, fullscreen, onInteraction, controlsVisible, onToggleControls, onSetCover)
        finished -> Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Text("CBZ tidak berisi gambar atau arsip tidak dapat dibaca", color = Color.White)
        }
        else -> Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color.White)
                Text(if (total > 0) "Memuat halaman $done / $total" else "Membuka komik…", color = Color.White, fontSize = 13.sp)
                if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.width(180.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CbzReaderContent(
    file: File,
    book: CbzBook,
    fullscreen: Boolean,
    onInteraction: () -> Unit,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onSetCover: (String) -> Unit
) {
    var coverCandidate by remember(file) { mutableStateOf<String?>(null) }
    val names = remember(book) { book.pages.map { it.name } }
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
    // Dekode halaman di sekitar posisi baca lebih dulu (dari berkas lokal) supaya tidak ada spinner saat menggulir.
    LaunchedEffect(book, currentIndex) {
        withContext(Dispatchers.Default) {
            val from = (currentIndex - 2).coerceAtLeast(0)
            val to = (currentIndex + 8).coerceAtMost(book.pages.lastIndex)
            for (i in from..to) {
                val p = book.pages[i]
                if (!p.animated) loadCbzBitmap(p, p.file.absolutePath)
            }
        }
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
                    CbzPage(book, index, vertical = true)
                }
            }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed) { page ->
                CbzPage(book, page, vertical = false)
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
            // Zoom tetap aktif lewat pinch dua jari dan ketuk dua kali; tombol samping dihilangkan.
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = if (fullscreen) 10.dp else 76.dp)
                    .background(Color(0xCC101827), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = !verticalMode, onClick = { verticalMode = false }, label = { Text("Halaman") })
                FilterChip(selected = verticalMode, onClick = { verticalMode = true }, label = { Text("Gulir panjang") })
                IconButton(onClick = { coverCandidate = names[currentIndex.coerceIn(0, names.lastIndex)] }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, "Jadikan sampul", tint = Color.White)
                }
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
    coverCandidate?.let { entry ->
        CbzCoverPicker(file, names, entry, onPick = { chosen ->
            coverCandidate = null
            onSetCover(chosen)
        }, onDismiss = { coverCandidate = null })
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
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    // Tetap diperlukan untuk membuang bitmap dari direktori buku saat pembaca ditutup.
    fun evictPrefix(prefix: String) { cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) } }
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
private fun CbzPage(book: CbzBook, index: Int, vertical: Boolean) {
    val page = book.pages[index]
    val context = LocalContext.current
    // Tinggi halaman sudah pasti (dari ukuran gambar asli): daftar tidak melompat saat gambar selesai dimuat.
    val box = if (vertical) Modifier.fillMaxWidth().aspectRatio(page.ratio) else Modifier.fillMaxSize()
    if (page.animated) {
        val request = remember(page.file) {
            coil.request.ImageRequest.Builder(context).data(page.file).crossfade(false).build()
        }
        coil.compose.AsyncImage(
            model = request,
            imageLoader = ViewerImages.loader(context),
            contentDescription = "Halaman ${index + 1}",
            modifier = box.background(Color.White),
            contentScale = if (vertical) ContentScale.FillWidth else ContentScale.Fit
        )
    } else {
        val key = page.file.absolutePath
        // Nilai awal diambil dari cache secara sinkron, jadi halaman yang sudah pernah dimuat langsung tampil tanpa spinner.
        val bmp by produceState<Bitmap?>(CbzCache.get(key), key) {
            if (value == null) value = loadCbzBitmap(page, key)
        }
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), "Halaman ${index + 1}", box.background(Color.White),
                contentScale = if (vertical) ContentScale.FillWidth else ContentScale.Fit)
        } else Box(box.background(Color(0xFF111111)))
    }
}

/** Dekode satu halaman statis dari berkas lokal (hasil ekstraksi), dengan cache memori. */
private suspend fun loadCbzBitmap(page: CbzPageInfo, key: String): Bitmap? {
    CbzCache.get(key)?.let { return it }
    return withContext(Dispatchers.Default) {
        runCatching {
            var sample = 1
            val longest = maxOf(page.width, page.height)
            while (longest / (sample * 2) >= 1800) sample *= 2
            BitmapFactory.decodeFile(page.file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }.getOrNull()?.also { CbzCache.put(key, it) }
    }
}

/**
 * Ekstrak semua halaman CBZ ke cache lokal secara paralel (4 jalur), baca ukuran tiap gambar,
 * lalu dekode beberapa halaman pertama. Dipanggil sekali saat CBZ dibuka.
 */
private suspend fun prepareCbzBook(ctx: Context, source: File, names: List<String>, onProgress: (Int) -> Unit): CbzBook =
    withContext(Dispatchers.IO) {
        val root = File(ctx.cacheDir, "cbz_pages").apply { mkdirs() }
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { if (now - it.lastModified() > 30 * 60 * 1000L) it.deleteRecursively() }
        val dir = File(root, "b" + System.nanoTime()).apply { mkdirs() }
        try {
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val gate = Semaphore(4)
            val pages = ZipFile(source).use { zip ->
                coroutineScope {
                    names.mapIndexed { i, name ->
                        async {
                            gate.withPermit {
                                val entry = zip.getEntry(name)
                                if (entry == null) {
                                    onProgress(done.incrementAndGet())
                                    return@withPermit null
                                }
                                val ext = name.substringAfterLast('.', "bin").lowercase()
                                    .filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
                                val out = File(dir, String.format(java.util.Locale.US, "%05d.%s", i, ext))
                                zip.getInputStream(entry).use { input ->
                                    out.outputStream().use { o -> input.copyTo(o, 128 * 1024) }
                                }
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(out.absolutePath, bounds)
                                onProgress(done.incrementAndGet())
                                CbzPageInfo(name, out, bounds.outWidth, bounds.outHeight, ext == "gif" || ext == "webp")
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            pages.filter { !it.animated }.take(3).forEach { loadCbzBitmap(it, it.file.absolutePath) }
            CbzBook(dir, pages)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }
