@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.privatevault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.example.privatevault.R
import androidx.compose.ui.res.painterResource
import com.example.privatevault.data.*
import java.io.File
import java.util.Date
import java.util.zip.ZipFile

/* ---------- LOCK ---------- */
@Composable
fun LockScreen(pm: PinManager, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val setup = !pm.hasPin
    val canBio = remember { androidx.biometric.BiometricManager.from(ctx).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS }
    val title = when { !setup -> "Masukkan PIN"; first == null -> "Buat PIN baru (4 digit)"; else -> "Ulangi PIN" }

    fun bio() {
        val a = ctx as? FragmentActivity ?: return
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Buka Dhika App").setNegativeButtonText("Gunakan PIN").build())
    }
    LaunchedEffect(Unit) { if (!setup && pm.biometric && canBio) bio() }

    fun press(k: String) {
        if (checking) return
        error = ""
        if (k == "⌫") pin = pin.dropLast(1) else if (pin.length < 4) pin += k
        if (pin.length == 4) {
            val entered = pin
            when {
                !setup -> {
                    checking = true
                    scope.launch {
                        val (valid, wait) = withContext(Dispatchers.IO) { val ok = pm.verify(entered); ok to pm.lockRemainingMs() }
                        checking = false
                        if (valid) onUnlocked() else {
                            error = if (wait > 0) "Terlalu banyak percobaan. Coba lagi dalam ${(wait + 999) / 1000} detik" else "PIN salah, coba lagi"
                            pin = ""
                        }
                    }
                }
                first == null -> { first = entered; pin = "" }
                first == entered -> {
                    checking = true
                    scope.launch {
                        withContext(Dispatchers.IO) { pm.set(entered) }
                        checking = false
                        onUnlocked()
                    }
                }
                else -> { error = "PIN tidak sama"; first = null; pin = "" }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo_full_white), "Logo Dhika", Modifier.width(240.dp))
        Spacer(Modifier.height(8.dp)); Text("Dhika App", fontSize = 14.sp, color = Muted)
        Text(title, color = Muted)
        Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { Box(Modifier.size(14.dp).clip(CircleShape).border(2.dp, Accent, CircleShape).background(if (it < pin.length) Accent else androidx.compose.ui.graphics.Color.Transparent)) }
        }
        Text(error, color = androidx.compose.ui.graphics.Color(0xFFFF6B73), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.heightIn(min = 36.dp))
        listOf("123", "456", "789", " 0⌫").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                row.forEach { ch -> if (ch == ' ') Spacer(Modifier.size(64.dp)) else
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Card).clickable { press(ch.toString()) }, contentAlignment = Alignment.Center) { Text(ch.toString(), fontSize = 22.sp) } }
            }
        }
        if (!setup && pm.biometric && canBio) TextButton(onClick = ::bio) { Icon(Icons.Default.Fingerprint, null); Text(" Gunakan biometrik") }
    }
}

/* ---------- THUMBNAIL (didekripsi dari thumbnail terenkripsi) ---------- */
/** Mengingat tab terakhir yang dibuka per folder, supaya tidak kembali ke "Foto" setelah menutup viewer. */
private object TabMemory {
    private val map = mutableMapOf<String, MediaType?>()
    fun get(key: String): MediaType? = if (map.containsKey(key)) map[key] else MediaType.PHOTO
    fun set(key: String, type: MediaType?) { map[key] = type }
}

/** Klik dengan efek "membal": item mengecil sedikit saat ditekan lalu kembali dengan pegas. */
private fun Modifier.bouncyClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, pressedScale: Float = 0.95f): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = 450f))
    this.graphicsLayer { scaleX = s; scaleY = s }
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
}

/** true saat pengguna menggulir ke atas (atau di posisi awal): dipakai untuk melipat tombol Tambah. */
@Composable
private fun LazyGridState.isScrollingUp(): Boolean {
    var prevIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var prevOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val up = if (prevIndex != firstVisibleItemIndex) prevIndex > firstVisibleItemIndex else prevOffset >= firstVisibleItemScrollOffset
            prevIndex = firstVisibleItemIndex; prevOffset = firstVisibleItemScrollOffset
            up
        }
    }.value
}

@Composable
private fun androidx.compose.foundation.lazy.LazyListState.isScrollingUp(): Boolean {
    var prevIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var prevOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val up = if (prevIndex != firstVisibleItemIndex) prevIndex > firstVisibleItemIndex else prevOffset >= firstVisibleItemScrollOffset
            prevIndex = firstVisibleItemIndex; prevOffset = firstVisibleItemScrollOffset
            up
        }
    }.value
}

private fun greetingNow(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 4..10 -> "Selamat pagi"
    in 11..14 -> "Selamat siang"
    in 15..17 -> "Selamat sore"
    else -> "Selamat malam"
}

/** Pemilih jenis media (Foto / Video / Komik) berbentuk pil, lengkap dengan jumlah file. */
@Composable
private fun MediaTabs(selected: MediaType?, counts: Map<MediaType?, Int>, onSelect: (MediaType?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).glassSurface(RoundedCornerShape(18.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Triple("Semua", null as MediaType?, Icons.Default.GridView), Triple("Foto", MediaType.PHOTO, Icons.Default.Image), Triple("Video", MediaType.VIDEO, Icons.Default.PlayCircle), Triple("Komik", MediaType.COMIC, Icons.Default.MenuBook))
            .forEach { (label, type, icon) ->
                val active = selected == type
                val bg by androidx.compose.animation.animateColorAsState(if (active) Accent else Color.Transparent)
                Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).clickable { onSelect(type) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = if (active) Color.White else Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$label ${counts[type] ?: 0}", color = if (active) Color.White else Muted, fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
    }
}

@Composable
private fun EmptyState(type: MediaType, hint: String) {
    val (icon, label) = when (type) {
        MediaType.PHOTO -> Icons.Default.Image to "foto"
        MediaType.VIDEO -> Icons.Default.PlayCircle to "video"
        MediaType.COMIC -> Icons.Default.MenuBook to "komik"
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Card2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Belum ada $label", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun FolderCover(repo: VaultRepository, file: VaultFile) {
    val bmp by produceState<android.graphics.Bitmap?>(null, file.file.absolutePath) { value = withContext(Dispatchers.IO) { repo.thumb(file) } }
    val image = remember(bmp) { bmp?.asImageBitmap() }
    if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    else Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(38.dp))
}

@Composable
fun Thumb(repo: VaultRepository, f: VaultFile, modifier: Modifier = Modifier, selected: Boolean? = null, onToggle: () -> Unit = {}, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(null, f.file.absolutePath) { value = withContext(Dispatchers.IO) { repo.thumb(f) } }
    val image = remember(bmp) { bmp?.asImageBitmap() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Item yang dipilih sedikit mengecil (efek pegas) supaya status pilihan terasa jelas.
    val shrink by animateFloatAsState(if (selected == true) 0.88f else 1f, spring(dampingRatio = 0.7f, stiffness = 400f))
    Box(modifier.aspectRatio(1f).graphicsLayer { scaleX = shrink; scaleY = shrink }.clip(RoundedCornerShape(14.dp)).background(Card2)
        .bouncyClick(onClick = onClick, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick() }),
        contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (bmp == null && f.type == MediaType.COMIC) Icon(Icons.Default.MenuBook, null, tint = Accent, modifier = Modifier.size(36.dp))
        if (f.type == MediaType.VIDEO) Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x77000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (f.type == MediaType.COMIC) Text("CBZ", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        if (selected == true) Box(Modifier.fillMaxSize().background(Color(0x552F7BFF)))
        if (selected != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
            .background(if (selected) Accent else Color(0x66000000)).border(2.dp, Color.White, CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Beri tahu pengguna hasil impor (berapa file masuk, berapa dilewati). */
private fun toastImport(ctx: android.content.Context, r: VaultRepository.ImportResult) {
    val parts = mutableListOf("${r.imported} file diamankan")
    if (r.originalsDeleted > 0) parts += "${r.originalsDeleted} file asli dihapus dari perangkat"
    if (r.skipped > 0) parts += "${r.skipped} dilewati (format tidak didukung atau gagal dibaca)"
    if (r.cancelled) parts += "impor dibatalkan; file yang belum diproses tetap di perangkat"
    android.widget.Toast.makeText(ctx, parts.joinToString(", "), android.widget.Toast.LENGTH_LONG).show()
}

private class HomeData(val media: List<VaultFile>, val folders: List<File>, val counts: Map<File, Int>, val covers: Map<File, VaultFile?>)

@Composable
private fun FileInfoDialog(file: VaultFile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Informasi File") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nama: ${file.name}")
            Text("Jenis: ${file.file.extension.uppercase()}")
            Text("Ukuran: ${android.text.format.Formatter.formatShortFileSize(ctx, file.file.length())}")
            Text("Folder: ${file.file.parentFile?.name ?: "-"}")
            Text("Diubah: ${Date(file.file.lastModified())}")
        } },
        confirmButton = { TextButton(onDismiss) { Text("Tutup") } })
}

@Composable
private fun FolderInfoDialog(folder: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember(folder) { folder.listFiles()?.filter { it.isFile } ?: emptyList() }
    val total = remember(files) { files.sumOf { it.length() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Informasi Folder") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nama: ${folder.name}")
            Text("Jumlah file: ${files.size}")
            Text("Ukuran total: ${android.text.format.Formatter.formatShortFileSize(ctx, total)}")
            Text("Dibuat/diubah: ${Date(folder.lastModified())}")
        } },
        confirmButton = { TextButton(onDismiss) { Text("Tutup") } })
}

@Composable
private fun CbzCoverDialog(repo: VaultRepository, file: VaultFile, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val entries by produceState<List<String>?>(null, file.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val plain = runCatching { repo.open(file) }.getOrNull() ?: return@withContext emptyList()
            try {
                ZipFile(plain).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") }
                        .map { it.name }.toList()
                }
            } catch (_: Exception) { emptyList() }
            finally { plain.delete() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pilih sampul CBZ") },
        text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                Text("Pilih salah satu gambar di dalam arsip untuk dijadikan thumbnail.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                when {
                    entries == null -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator() }
                    entries!!.isEmpty() -> Text("Tidak ditemukan gambar yang dapat digunakan.", color = Muted)
                    else -> LazyColumn {
                        items(entries!!) { name ->
                            TextButton(onClick = { onSelect(name) }, modifier = Modifier.fillMaxWidth()) {
                                Text(name.substringAfterLast('/'), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Tutup") } })
}

/* ---------- HOME ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: VaultRepository, version: Int, onFolder: (File) -> Unit, onFile: (VaultFile, List<VaultFile>) -> Unit, onFav: () -> Unit, onSettings: () -> Unit, onTrash: () -> Unit, onRootFiles: () -> Unit, onChanged: () -> Unit, showBottomNav: Boolean = true) {
    // Baca daftar file di thread IO (bukan di main thread); data lama tetap tampil selama memuat ulang.
    val home by produceState<HomeData?>(null, version) {
        value = withContext(Dispatchers.IO) {
            val fs = repo.folders(); val everything = repo.allFiles()
            val byFolder = everything.groupBy { it.file.parentFile }
            HomeData(everything, fs,
                fs.associateWith { d -> byFolder[d]?.size ?: 0 },
                fs.associateWith { d -> byFolder[d]?.firstOrNull { it.type != MediaType.COMIC } ?: byFolder[d]?.firstOrNull() })
        }
    }
    val all = home?.media ?: emptyList()
    val folders = home?.folders ?: emptyList()
    val typeCounts = remember(all) { all.groupingBy { it.type }.eachCount() }
    var q by remember { mutableStateOf("") }
    val cols = (LocalConfiguration.current.screenWidthDp / 92).coerceIn(4, 10) // lebih banyak kolom saat landscape
    val shown = remember(all, q, cols) { all.filter { it.name.contains(q, true) }.take(if (q.isBlank()) cols * 4 else 80).chunked(cols) }
    var chooseDest by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val removeOriginals = rememberOriginalsRemover()
    val backup = rememberBackupUi(repo, onChanged)
    val exportFiles = rememberExportUi(repo, onChanged)
    var target by remember { mutableStateOf<File?>(null) }
    val selectedFiles = remember { mutableStateListOf<VaultFile>() }
    androidx.activity.compose.BackHandler(enabled = selectedFiles.isNotEmpty()) { selectedFiles.clear() }
    var actionFile by remember { mutableStateOf<VaultFile?>(null) }
    var coverTarget by remember { mutableStateOf<VaultFile?>(null) }
    var infoFile by remember { mutableStateOf<VaultFile?>(null) }
    var deleteFile by remember { mutableStateOf<VaultFile?>(null) }
    var actionFolder by remember { mutableStateOf<File?>(null) }
    var renameFolderTarget by remember { mutableStateOf<File?>(null) }
    var infoFolder by remember { mutableStateOf<File?>(null) }
    var deleteFolder by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var importing by remember { mutableStateOf(false) }
    var importHidden by remember { mutableStateOf(false) }
    var cancelImport by remember { mutableStateOf(false) }
    val importCancelSignal = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var importDone by remember { mutableIntStateOf(0) }; var importTotal by remember { mutableIntStateOf(0) }; var importName by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(PickDocsForMove()) { uris: List<Uri> ->
        val f = target ?: repo.folders().firstOrNull() ?: repo.rootFolder()
        if (uris.isNotEmpty()) scope.launch {
            importCancelSignal.set(false); cancelImport = false; importHidden = false; importing = true
            try {
                val r = withContext(Dispatchers.IO) {
                    repo.import(f, uris, repo.moveOriginals, shouldContinue = { !importCancelSignal.get() }) { done, total, name ->
                        mainHandler.post { importDone = done; importTotal = total; importName = name }
                    }
                }
                toastImport(ctx, r)
                if (r.undeleted.isNotEmpty()) removeOriginals(r.undeleted)
                onChanged()
            } finally { importing = false; importHidden = false; cancelImport = false; importCancelSignal.set(false) }
        }
    }
    fun launchPicker(dest: File) { target = dest; ExternalFlow.active = true; picker.launch(arrayOf("*/*")) }
    Scaffold(containerColor = Bg,
        topBar = { if (selectedFiles.isNotEmpty()) TopAppBar(title = { Text("${selectedFiles.size} dipilih") },
            navigationIcon = { IconButton(onClick = { selectedFiles.clear() }) { Icon(Icons.Default.Close, "Batal pilih") } },
            actions = {
                IconButton(onClick = { val chosen = selectedFiles.toList(); selectedFiles.clear(); exportFiles(chosen) }) { Icon(Icons.Default.FileDownload, "Keluarkan file") }
                IconButton(onClick = { selectedFiles.forEach { repo.setFav(it, true) }; selectedFiles.clear(); onChanged() }) { Icon(Icons.Default.Favorite, "Favorit") }
                IconButton(onClick = { val chosen = selectedFiles.toList(); selectedFiles.clear(); scope.launch { withContext(Dispatchers.IO) { chosen.forEach(repo::delete) }; onChanged() } }) { Icon(Icons.Default.Delete, "Pindahkan ke Sampah") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = {
            when (folders.size) {
                0 -> { android.widget.Toast.makeText(ctx, "Buat folder dulu untuk menyimpan file", android.widget.Toast.LENGTH_SHORT).show(); newDialog = true }
                1 -> launchPicker(folders[0])
                else -> chooseDest = true
            }
        }, expanded = listState.isScrollingUp(), containerColor = Accent, contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Tambah") }) },
        bottomBar = { if (showBottomNav) NavigationBar(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(26.dp))
                .border(1.dp, Color(0x665F79A8), RoundedCornerShape(26.dp)),
            containerColor = Color0d
        ) {
                NavigationBarItem(true, {}, { Icon(Icons.Default.Home, null) }, label = { Text("Beranda") })
                NavigationBarItem(false, onRootFiles, { Icon(Icons.Default.Image, null) }, label = { Text("Gambar") })
                NavigationBarItem(false, onFav, { Icon(Icons.Default.FavoriteBorder, null) }, label = { Text("Favorit") })
                NavigationBarItem(false, onSettings, { Icon(Icons.Default.Settings, null) }, label = { Text("Pengaturan") })
            }
        }) { pad ->
        LazyColumn(state = listState, modifier = Modifier.padding(pad).background(Brush.verticalGradient(listOf(Color(0xFF10234A), Bg, Color(0xFF080E19)))).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.padding(top = 18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(remember { greetingNow() }.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Dhika App", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Semua file aman, dalam satu tempat", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onTrash, modifier = Modifier.glassSurface(RoundedCornerShape(50.dp)).size(46.dp)) { Icon(Icons.Default.DeleteOutline, "Sampah") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Triple("Foto", MediaType.PHOTO, Icons.Default.Image), Triple("Video", MediaType.VIDEO, Icons.Default.PlayCircle), Triple("Komik", MediaType.COMIC, Icons.Default.MenuBook)).forEach { (n, t, icon) ->
                        // Angka naik perlahan saat data selesai dimuat.
                        val animated by animateIntAsState(typeCounts[t] ?: 0, tween(700))
                        Column(Modifier.weight(1f).glassSurface(RoundedCornerShape(18.dp)).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x332F7BFF)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp)) }
                            Spacer(Modifier.height(8.dp))
                            Text("$animated", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(n, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Cari file…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Default.Close, "Hapus pencarian") } },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedContainerColor = Card, unfocusedContainerColor = Card)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Folder Saya", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("Akses cepat ke koleksi pribadi", color = Muted, fontSize = 11.sp)
                    }
                    Text("${folders.size} folder", color = Muted, fontSize = 11.sp)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(folders, key = { it.absolutePath }) { f ->
                        val cover = home?.covers?.get(f)
                        Column(Modifier.width(124.dp).glassSurface(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                            .bouncyClick(onClick = { onFolder(f) }, onLongClick = { actionFolder = f })) {
                            Box(Modifier.fillMaxWidth().height(86.dp).background(Brush.linearGradient(listOf(Color(0xFF1B2D57), Color(0xFF0F1A33)))), contentAlignment = Alignment.Center) {
                                if (cover != null) FolderCover(repo, cover) else Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(38.dp))
                                Text("${home?.counts?.get(f) ?: 0}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${home?.counts?.get(f) ?: 0} item", fontSize = 11.sp, color = Muted)
                            }
                        }
                    }
                    item {
                        Column(Modifier.width(124.dp).height(136.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Color(0x665F79A8), RoundedCornerShape(18.dp))
                            .bouncyClick(onClick = { newDialog = true }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x332F7BFF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = Accent) }
                            Spacer(Modifier.height(8.dp))
                            Text("Folder Baru", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
            item {
                Column {
                    Text("Media Terbaru", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Lanjutkan membuka foto, video, dan komik", color = Muted, fontSize = 11.sp)
                }
            }
            if (home != null && shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (q.isBlank()) "Brankas masih kosong.\nTekan + untuk menambah foto, video, atau komik." else "Tidak ada file yang cocok dengan “$q”.",
                        color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (q.isBlank()) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = backup.startRestore, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pulihkan dari backup")
                        }
                    }
                }
            }
            items(shown) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { file -> Thumb(repo, file, Modifier.weight(1f), selected = if (selectedFiles.isNotEmpty()) selectedFiles.contains(file) else null, onToggle = { if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file) }, onLongClick = { if (!selectedFiles.contains(file)) selectedFiles.add(file) }) { if (selectedFiles.isNotEmpty()) { if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file) } else onFile(file, all) } }; repeat(cols - row.size) { Spacer(Modifier.weight(1f)) } }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (importing && !importHidden) AlertDialog(onDismissRequest = { importHidden = true }, title = { Text("Mengimpor file") },
        text = { Column { LinearProgressIndicator(progress = { if (importTotal == 0) 0f else importDone.toFloat() / importTotal }, Modifier.fillMaxWidth()); Text("$importDone dari $importTotal file\n$importName", color = Muted) } },
        confirmButton = { TextButton({ importHidden = true }) { Text("Sembunyikan") } },
        dismissButton = { TextButton({ importCancelSignal.set(true); cancelImport = true }, enabled = !cancelImport) { Text(if (cancelImport) "Membatalkan…" else "Batal") } })
    if (importing && importHidden) TextButton(onClick = { importHidden = false },
        modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Impor berjalan · $importDone dari $importTotal · Tampilkan") }
    if (chooseDest) AlertDialog(onDismissRequest = { chooseDest = false }, title = { Text("Simpan ke folder") }, confirmButton = {},
        dismissButton = { TextButton({ chooseDest = false }) { Text("Batal") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { folders.forEach { d ->
            TextButton({ chooseDest = false; launchPicker(d) }, Modifier.fillMaxWidth()) { Text(d.name, maxLines = 1, modifier = Modifier.fillMaxWidth()) } } } })
    if (newDialog) NameDialog("Folder Baru", "") { n -> newDialog = false; if (n != null) { if (repo.createFolder(n)) onChanged() else android.widget.Toast.makeText(ctx, "Nama folder tidak valid atau sudah ada", android.widget.Toast.LENGTH_SHORT).show() } }
    actionFile?.let { file -> AlertDialog(onDismissRequest = { actionFile = null }, title = { Text(file.name, maxLines = 1) }, text = { Text("Pilih tindakan untuk file ini.") }, confirmButton = { Row { TextButton({ actionFile = null; infoFile = file }) { Text("Informasi") }; TextButton({ actionFile = null; deleteFile = file }) { Text("Hapus") } } }, dismissButton = { TextButton({ actionFile = null }) { Text("Tutup") } }) }
    infoFile?.let { FileInfoDialog(it) { infoFile = null } }
    deleteFile?.let { file -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("Hapus file?") }, text = { Text("File akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFile = null; scope.launch { withContext(Dispatchers.IO) { repo.delete(file) }; onChanged() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFile = null }) { Text("Tidak") } }) }
    actionFolder?.let { folder -> AlertDialog(onDismissRequest = { actionFolder = null }, title = { Text(folder.name) }, text = { Text("Pilih tindakan untuk folder ini.") }, confirmButton = { Row {
        TextButton({ actionFolder = null; renameFolderTarget = folder }) { Text("Ganti Nama") }
        TextButton({ actionFolder = null; infoFolder = folder }) { Text("Informasi") }
        TextButton({ actionFolder = null; deleteFolder = folder }) { Text("Hapus") }
    } }, dismissButton = { TextButton({ actionFolder = null }) { Text("Tutup") } }) }
    renameFolderTarget?.let { folder -> NameDialog("Ganti Nama Folder", folder.name) { name ->
        renameFolderTarget = null
        if (name != null) {
            if (repo.renameFolder(folder, name)) onChanged()
            else android.widget.Toast.makeText(ctx, "Nama folder tidak valid atau sudah digunakan", android.widget.Toast.LENGTH_SHORT).show()
        }
    } }
    infoFolder?.let { FolderInfoDialog(it) { infoFolder = null } }
    deleteFolder?.let { folder -> AlertDialog(onDismissRequest = { deleteFolder = null }, title = { Text("Hapus folder?") }, text = { Text("Isi folder akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFolder = null; scope.launch { withContext(Dispatchers.IO) { repo.deleteFolder(folder) }; onChanged() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFolder = null }) { Text("Tidak") } }) }
}
private val Color0d = androidx.compose.ui.graphics.Color(0xCC0D1527)

private fun Modifier.glassSurface(shape: RoundedCornerShape = RoundedCornerShape(20.dp)): Modifier =
    this.clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xCC263B61), Color(0x99202D49), Color(0xAA111B30))))
        .border(1.dp, Brush.linearGradient(listOf(Color(0x99B9D2FF), Color(0x224F83D8), Color(0x667A91B8))), shape)

@Composable
fun NameDialog(title: String, init: String, done: (String?) -> Unit) {
    var t by remember { mutableStateOf(init) }
    AlertDialog(onDismissRequest = { done(null) }, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Nama folder") }) },
        confirmButton = { TextButton({ done(t.ifBlank { null }) }) { Text("Simpan") } },
        dismissButton = { TextButton({ done(null) }) { Text("Batal") } })
}

/* ---------- FOLDER / FAVORIT ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(repo: VaultRepository, folder: File?, version: Int, onBack: () -> Unit, onFile: (VaultFile, List<VaultFile>, Int, Int) -> Unit, onChanged: () -> Unit, initialScrollIndex: Int = 0, initialScrollOffset: Int = 0, rootFiles: Boolean = false, onHome: () -> Unit = onBack, onRootFiles: () -> Unit = onBack, onFav: () -> Unit = onBack, onSettings: () -> Unit = onBack, clipboard: List<VaultFile> = emptyList(), onClipboardChange: (List<VaultFile>) -> Unit = {}, showBottomNav: Boolean = true) {
    val tabKey = if (rootFiles) "root" else folder?.absolutePath ?: "fav"
    var tab by remember { mutableStateOf(TabMemory.get(tabKey)) }
    val sel = remember { mutableStateListOf<VaultFile>() }
    androidx.activity.compose.BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var moveDlg by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember(tabKey) { mutableStateOf("Terbaru") }
    // Menu "Gambar" kini menampilkan semua jenis media (foto, video, komik) lewat tab, bukan hanya foto.
    val source = remember(version, rootFiles, folder) { if (rootFiles) repo.rootFiles() else if (folder == null) repo.favorites() else repo.files(folder) }
    val tabCounts = remember(source) { source.groupingBy { it.type }.eachCount() + (null to source.size) }
    val items = remember(source, tab, sortMode) {
        val filtered = if (tab == null) source else source.filter { it.type == tab }
        when (sortMode) {
            "Jenis file" -> filtered.sortedWith(compareBy<VaultFile> { it.type?.ordinal ?: 9 }.thenBy { it.name.lowercase() })
            "Nama file" -> filtered.sortedBy { it.name.lowercase() }
            else -> filtered.sortedByDescending { it.file.lastModified() }
        }
    }
    // Bila tab aktif kosong tetapi tab lain berisi, pindah otomatis supaya file yang baru diunggah langsung terlihat.
    LaunchedEffect(source) {
        if ((tabCounts[tab] ?: 0) == 0) {
            listOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.COMIC).firstOrNull { (tabCounts[it] ?: 0) > 0 }?.let { tab = it; TabMemory.set(tabKey, it) }
        }
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialScrollIndex, initialFirstVisibleItemScrollOffset = initialScrollOffset)
    val ctx = LocalContext.current
    val removeOriginals = rememberOriginalsRemover()
    val exportFiles = rememberExportUi(repo, onChanged)
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var importing by remember { mutableStateOf(false) }
    var importHidden by remember { mutableStateOf(false) }
    var cancelImport by remember { mutableStateOf(false) }
    val importCancelSignal = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var importDone by remember { mutableIntStateOf(0) }; var importTotal by remember { mutableIntStateOf(0) }; var importName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<VaultFile?>(null) }
    var coverTarget by remember { mutableStateOf<VaultFile?>(null) }
    var infoFile by remember { mutableStateOf<VaultFile?>(null) }
    var deleteFile by remember { mutableStateOf<VaultFile?>(null) }
    var actionFolder by remember { mutableStateOf(false) }
    var infoFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(PickDocsForMove()) { uris ->
        val destination = folder ?: if (rootFiles) repo.rootFolder() else null
        if (uris.isNotEmpty()) destination?.let { f -> scope.launch {
            importCancelSignal.set(false); cancelImport = false; importHidden = false; importing = true
            try {
                val r = withContext(Dispatchers.IO) {
                    repo.import(f, uris, repo.moveOriginals, shouldContinue = { !importCancelSignal.get() }) { done, total, name ->
                        mainHandler.post { importDone = done; importTotal = total; importName = name }
                    }
                }
                toastImport(ctx, r)
                if (r.undeleted.isNotEmpty()) removeOriginals(r.undeleted)
                onChanged()
            } finally { importing = false; importHidden = false; cancelImport = false; importCancelSignal.set(false) }
        } }
    }
    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(title = { Text(if (sel.isNotEmpty()) "${sel.size} dipilih" else if (rootFiles) "Di Luar Folder" else folder?.name ?: "Favorit") },
            navigationIcon = { if (sel.isNotEmpty()) IconButton({ sel.clear() }) { Icon(Icons.Default.Close, "Batal pilih") } else if (!rootFiles && folder != null) IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Kembali") } },
            actions = {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Urutkan") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Terbaru", "Jenis file", "Nama file").forEach { mode ->
                            DropdownMenuItem(text = { Text(if (sortMode == mode) "✓ $mode" else mode) }, onClick = {
                                sortMode = mode
                                if (mode == "Jenis file") { tab = null; TabMemory.set(tabKey, null) }
                                sortMenu = false
                            })
                        }
                    }
                }
                if (sel.size == 1 && sel.first().type == MediaType.COMIC) {
                    IconButton(onClick = { coverTarget = sel.first(); sel.clear() }) { Icon(Icons.Default.Image, "Pilih sampul CBZ") }
                }
                if (sel.isNotEmpty()) IconButton(onClick = { sel.clear(); sel.addAll(items) }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                if (sel.isNotEmpty()) IconButton(onClick = { val chosen = sel.toList(); sel.clear(); exportFiles(chosen) }) { Icon(Icons.Default.FileDownload, "Keluarkan file") }
                if ((folder == null || rootFiles) && sel.isNotEmpty()) {
                    IconButton(onClick = { moveDlg = true }) { Icon(Icons.Default.DriveFileMove, "Pindahkan ke folder") }
                    IconButton(onClick = { sel.forEach { repo.setFav(it, true) }; sel.clear(); onChanged() }) { Icon(Icons.Default.Favorite, "Favorit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Pindahkan ke Sampah") }
                }
                if (folder != null) Box { IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Ubah Nama") }, { menu = false; rename = true })
                        DropdownMenuItem({ Text("Informasi Folder") }, { menu = false; infoFolder = true })
                        DropdownMenuItem({ Text("Hapus Folder") }, { menu = false; deleteFolder = true })
                    } }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) },
        floatingActionButton = { if (folder != null || rootFiles) ExtendedFloatingActionButton(
            onClick = { ExternalFlow.active = true; picker.launch(arrayOf("*/*")) },
            expanded = gridState.isScrollingUp() || items.size < 12, containerColor = Accent, contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Tambah") }) },
        bottomBar = {
            if (showBottomNav && (folder == null || rootFiles)) NavigationBar(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(26.dp))
                    .border(1.dp, Color(0x665F79A8), RoundedCornerShape(26.dp)),
                containerColor = Color0d
            ) {
                NavigationBarItem(false, onHome, { Icon(Icons.Default.Home, null) }, label = { Text("Beranda") })
                NavigationBarItem(rootFiles, onRootFiles, { Icon(Icons.Default.Image, null) }, label = { Text("Gambar") })
                NavigationBarItem(!rootFiles, onFav, { Icon(Icons.Default.FavoriteBorder, null) }, label = { Text("Favorit") })
                NavigationBarItem(false, onSettings, { Icon(Icons.Default.Settings, null) }, label = { Text("Pengaturan") })
            } else if (folder != null && (sel.isNotEmpty() || clipboard.isNotEmpty())) BottomAppBar(containerColor = Color0d) {
                if (sel.isNotEmpty()) {
                    listOf(
                        Triple("Pindahkan", Icons.Default.DriveFileMove) { moveDlg = true },
                        Triple("Salin", Icons.Default.ContentCopy) { onClipboardChange(sel.toList()); sel.clear() },
                        Triple("Hapus", Icons.Default.Delete) { confirmDelete = true }
                    ).forEach { (label, icon, action) ->
                        TextButton(action, Modifier.weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, fontSize = 11.sp) }
                        }
                    }
                } else {
                    TextButton(onClick = {
                        val pending = clipboard.toList()
                        scope.launch {
                            withContext(Dispatchers.IO) { pending.forEach { repo.copy(it, folder) } }
                            onClipboardChange(emptyList())
                            onChanged()
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentPaste, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tempel " + clipboard.size + " file")
                    }
                }
            }
        }) { pad ->
        Column(Modifier.padding(pad)) {
            MediaTabs(tab, tabCounts) { tab = it; TabMemory.set(tabKey, it); sel.clear() }
            if (items.isEmpty() && !importing) EmptyState(tab, if (folder == null && !rootFiles) "Tandai file dengan ikon hati supaya muncul di sini." else "Tekan Tambah untuk menyimpan file ke sini.")
            LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(minSize = 104.dp), contentPadding = PaddingValues(start = 10.dp, top = 4.dp, end = 10.dp, bottom = 88.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.file.absolutePath }) { f ->
                    Thumb(repo, f,
                        selected = if (sel.isNotEmpty()) sel.contains(f) else null,
                        onToggle = { if (sel.contains(f)) sel.remove(f) else sel.add(f) },
                        onLongClick = { if (!sel.contains(f)) sel.add(f) }
                    ) {
                        if (sel.isNotEmpty()) {
                            if (sel.contains(f)) sel.remove(f) else sel.add(f)
                        } else onFile(f, items, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                    }
                }
            }
        }
    }
    if (importing && !importHidden) AlertDialog(onDismissRequest = { importHidden = true }, title = { Text("Mengimpor file") },
        text = { Column { LinearProgressIndicator(progress = { if (importTotal == 0) 0f else importDone.toFloat() / importTotal }, Modifier.fillMaxWidth()); Text("$importDone dari $importTotal file\n$importName", color = Muted) } },
        confirmButton = { TextButton({ importHidden = true }) { Text("Sembunyikan") } },
        dismissButton = { TextButton({ importCancelSignal.set(true); cancelImport = true }, enabled = !cancelImport) { Text(if (cancelImport) "Membatalkan…" else "Batal") } })
    if (importing && importHidden) TextButton(onClick = { importHidden = false },
        modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("Impor berjalan · $importDone dari $importTotal · Tampilkan") }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Konfirmasi Hapus") }, text = { Text("${sel.size} file akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton(onClick = { val selected = sel.toList(); sel.clear(); confirmDelete = false; scope.launch { withContext(Dispatchers.IO) { selected.forEach(repo::delete) }; onChanged() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Tidak") } })
    if (rename && folder != null) NameDialog("Ubah Nama", folder.name) { n -> rename = false; if (n != null) { if (repo.renameFolder(folder, n)) { onChanged(); onBack() } else android.widget.Toast.makeText(ctx, "Nama folder tidak valid atau sudah ada", android.widget.Toast.LENGTH_SHORT).show() } }
    if (moveDlg) AlertDialog({ moveDlg = false }, title = { Text("Pindahkan ke") }, confirmButton = {},
        text = { Column { repo.folders().filter { it != folder }.forEach { d -> TextButton({
            val selected = sel.toList()
            moveDlg = false
            scope.launch { withContext(Dispatchers.IO) { selected.forEach { repo.move(it, d) } }; sel.clear(); onChanged() }
        }) { Text(d.name) } } } })
    actionFile?.let { file -> AlertDialog(onDismissRequest = { actionFile = null }, title = { Text(file.name, maxLines = 1) }, text = { Text("Pilih tindakan untuk file ini.") }, confirmButton = { Row { TextButton({ actionFile = null; infoFile = file }) { Text("Informasi") }; TextButton({ actionFile = null; deleteFile = file }) { Text("Hapus") } } }, dismissButton = { TextButton({ actionFile = null }) { Text("Tutup") } }) }
    infoFile?.let { FileInfoDialog(it) { infoFile = null } }
    deleteFile?.let { file -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("Hapus file?") }, text = { Text("File akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFile = null; scope.launch { withContext(Dispatchers.IO) { repo.delete(file) }; onChanged() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFile = null }) { Text("Tidak") } }) }
    coverTarget?.let { file -> CbzCoverDialog(repo, file, onDismiss = { coverTarget = null }) { entry ->
        coverTarget = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repo.setComicCover(file, entry) }
            android.widget.Toast.makeText(ctx, if (ok) "Sampul CBZ diperbarui" else "Tidak dapat menyimpan sampul", android.widget.Toast.LENGTH_SHORT).show()
            if (ok) onChanged()
        }
    } }
    if (infoFolder && folder != null) FolderInfoDialog(folder) { infoFolder = false }
    if (deleteFolder && folder != null) AlertDialog(onDismissRequest = { deleteFolder = false }, title = { Text("Hapus folder?") }, text = { Text("Isi folder akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFolder = false; scope.launch { withContext(Dispatchers.IO) { repo.deleteFolder(folder) }; onChanged(); onBack() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFolder = false }) { Text("Tidak") } })
}

/* ---------- PENGATURAN ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(pm: PinManager, repo: VaultRepository, onChanged: () -> Unit, onBack: () -> Unit, onCheckUpdates: () -> Unit, checkingUpdates: Boolean) {
    var bio by remember { mutableStateOf(pm.biometric) }
    var auto by remember { mutableStateOf(pm.autoLockMs) }
    var pinDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val backup = rememberBackupUi(repo, onChanged)
    var moveOrig by remember { mutableStateOf(repo.moveOriginals) }
    Scaffold(containerColor = Bg, topBar = { TopAppBar(title = { Text("Pengaturan") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Keamanan", color = Muted, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { pinDlg = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Accent); Text("  Ubah PIN", Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Fingerprint, null, tint = Accent); Text("  Aktifkan Biometrik", Modifier.weight(1f)); Switch(bio, { bio = it; pm.biometric = it })
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp)) {
                Text("Kunci Otomatis"); Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Langsung" to 0L, "1 menit" to 60_000L, "5 menit" to 300_000L).forEach { (l, v) ->
                        FilterChip(auto == v, { auto = v; pm.autoLockMs = v }, { Text(l) }) }
                }
            }
            Text("File", color = Muted, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DriveFileMove, null, tint = Accent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Pindahkan file asli")
                    Text("Setelah diimpor, file asli dihapus dari galeri/penyimpanan sehingga hanya tersimpan (terenkripsi) di aplikasi ini.", color = Muted, fontSize = 12.sp)
                }
                Switch(moveOrig, { moveOrig = it; repo.moveOriginals = it })
            }
            Text("Semua file & thumbnail dienkripsi AES-256 di penyimpanan privat aplikasi.", color = Muted, fontSize = 13.sp)
            Text("Cadangan", color = Muted, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { backup.startBackup() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Backup, null, tint = Accent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text("Buat Backup"); Text("Simpan semua isi brankas ke satu file", color = Muted, fontSize = 12.sp) }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { backup.startRestore() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restore, null, tint = Accent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text("Pulihkan Backup"); Text("Buka file backup, termasuk setelah aplikasi dipasang ulang", color = Muted, fontSize = 12.sp) }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
            Text("Backup berisi folder, foto, video, komik, dan favorit dalam satu file .dhikabak yang dienkripsi dengan password pilihanmu. PIN tidak disertakan. Buat backup sebelum menghapus aplikasi: tanpa backup, file di brankas tidak bisa dipulihkan.", color = Muted, fontSize = 12.sp)
            Text("Tentang", color = Muted, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp)) { Text("Versi Aplikasi", Modifier.weight(1f)); Text(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-", color = Muted) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCheckUpdates, enabled = !checkingUpdates) {
                    if (checkingUpdates) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Memeriksa…")
                    } else {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Periksa Pembaruan")
                    }
                }
            }
        }
    }
    if (pinDlg) {
        var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }
        val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
        val hide = androidx.compose.ui.text.input.PasswordVisualTransformation()
        AlertDialog({ pinDlg = false }, title = { Text("Ubah PIN") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(old, { if (it.length <= 4) old = it }, label = { Text("PIN lama") }, singleLine = true, keyboardOptions = kb, visualTransformation = hide)
                OutlinedTextField(new, { if (it.length <= 4) new = it }, label = { Text("PIN baru (4 digit)") }, singleLine = true, keyboardOptions = kb, visualTransformation = hide) } },
            confirmButton = { TextButton({
                val ok = pm.changePin(old, new)
                android.widget.Toast.makeText(ctx, if (ok) "PIN diubah" else "PIN lama salah / PIN baru tidak valid", android.widget.Toast.LENGTH_SHORT).show()
                if (ok) pinDlg = false }) { Text("Simpan") } },
            dismissButton = { TextButton({ pinDlg = false }) { Text("Batal") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(repo: VaultRepository, onBack: () -> Unit, onChanged: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<VaultRepository.TrashedFile?>(null) }
    var confirmBulk by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val items = remember(version) { repo.trashedFiles() }
    Scaffold(containerColor = Bg, topBar = { TopAppBar(title = { Text("Baru Dihapus") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
        actions = { if (selected.isNotEmpty()) TextButton(onClick = { confirmBulk = true }) { Text("Hapus (${selected.size})") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) }) { pad ->
        if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Sampah kosong", color = Muted) }
        else LazyColumn(Modifier.padding(pad)) { items(items, key = { it.file.absolutePath }) { item ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, item.file.absolutePath) {
                value = withContext(Dispatchers.IO) { repo.trashThumb(item) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Card2), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Icon(Icons.Default.Image, null, tint = Muted)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(item.originalName, maxLines = 1)
                    Text("Dihapus: " + java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.deletedAt)), color = Muted, fontSize = 12.sp)
                }
                Checkbox(checked = selected.contains(item.file.absolutePath), onCheckedChange = { checked ->
                    if (checked) { if (!selected.contains(item.file.absolutePath)) selected.add(item.file.absolutePath) }
                    else selected.remove(item.file.absolutePath)
                })
                IconButton(onClick = { if (repo.restore(item)) { selected.remove(item.file.absolutePath); version++; onChanged() } }) { Icon(Icons.Default.Restore, "Pulihkan", tint = Accent) }
                IconButton(onClick = { confirm = item }) { Icon(Icons.Default.DeleteForever, "Hapus permanen") }
            }
        } }
    }
    confirm?.let { item -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Hapus permanen?") }, text = { Text("File tidak dapat dipulihkan setelah dihapus permanen.") }, confirmButton = { TextButton(onClick = { repo.permanentlyDelete(item); selected.remove(item.file.absolutePath); confirm = null; version++ }) { Text("Ya") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Tidak") } }) }
    if (confirmBulk) AlertDialog(onDismissRequest = { confirmBulk = false }, title = { Text("Hapus beberapa file?") }, text = { Text("${selected.size} file akan dihapus permanen dan tidak dapat dipulihkan.") },
        confirmButton = { TextButton(onClick = { val paths = selected.toSet(); items.filter { it.file.absolutePath in paths }.forEach(repo::permanentlyDelete); selected.clear(); confirmBulk = false; version++ }) { Text("Hapus permanen") } },
        dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Batal") } })
}
