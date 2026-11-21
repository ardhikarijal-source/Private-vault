@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.privatevault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.*
import androidx.compose.animation.animateContentSize
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
@Composable
fun Thumb(repo: VaultRepository, f: VaultFile, modifier: Modifier = Modifier, selected: Boolean? = null, onToggle: () -> Unit = {}, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(null, f.file.absolutePath) { value = withContext(Dispatchers.IO) { repo.thumb(f) } }
    val image = remember(bmp) { bmp?.asImageBitmap() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Card2)
        .combinedClickable(onClick = onClick, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick() }),
        contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (bmp == null && f.type == MediaType.COMIC) Icon(Icons.Default.MenuBook, null, tint = Accent, modifier = Modifier.size(36.dp))
        if (f.type == MediaType.VIDEO) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))
        if (selected == true) Box(Modifier.fillMaxSize().background(Color(0x552F7BFF)))
        if (selected != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
            .background(if (selected) Accent else Color(0x66000000)).border(2.dp, Color.White, CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Beri tahu pengguna hasil impor (berapa file masuk, berapa dilewati). */
private fun toastImport(ctx: android.content.Context, r: VaultRepository.ImportResult) {
    val msg = if (r.skipped == 0) "${r.imported} file diimpor"
    else "${r.imported} file diimpor, ${r.skipped} dilewati (format tidak didukung atau gagal dibaca)"
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
}

private class HomeData(val media: List<VaultFile>, val folders: List<File>, val counts: Map<File, Int>)

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

/* ---------- HOME ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: VaultRepository, version: Int, onFolder: (File) -> Unit, onFile: (VaultFile, List<VaultFile>) -> Unit, onFav: () -> Unit, onSettings: () -> Unit, onTrash: () -> Unit, onRootFiles: () -> Unit, onChanged: () -> Unit, showBottomNav: Boolean = true) {
    // Baca daftar file di thread IO (bukan di main thread); data lama tetap tampil selama memuat ulang.
    val home by produceState<HomeData?>(null, version) {
        value = withContext(Dispatchers.IO) {
            val fs = repo.folders(); val everything = repo.allFiles()
            HomeData(everything, fs, fs.associateWith { d -> everything.count { it.file.parentFile == d } })
        }
    }
    val all = home?.media ?: emptyList()
    val folders = home?.folders ?: emptyList()
    val typeCounts = remember(all) { all.groupingBy { it.type }.eachCount() }
    var q by remember { mutableStateOf("") }
    val shown = remember(all, q) { all.filter { it.name.contains(q, true) }.take(if (q.isBlank()) 16 else 60).chunked(4) }
    var chooseDest by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var target by remember { mutableStateOf<File?>(null) }
    val selectedFiles = remember { mutableStateListOf<VaultFile>() }
    androidx.activity.compose.BackHandler(enabled = selectedFiles.isNotEmpty()) { selectedFiles.clear() }
    var actionFile by remember { mutableStateOf<VaultFile?>(null) }
    var infoFile by remember { mutableStateOf<VaultFile?>(null) }
    var deleteFile by remember { mutableStateOf<VaultFile?>(null) }
    var actionFolder by remember { mutableStateOf<File?>(null) }
    var infoFolder by remember { mutableStateOf<File?>(null) }
    var deleteFolder by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var importing by remember { mutableStateOf(false) }
    var importDone by remember { mutableIntStateOf(0) }; var importTotal by remember { mutableIntStateOf(0) }; var importName by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val f = target ?: repo.folders().firstOrNull() ?: repo.rootFolder()
        if (uris.isNotEmpty()) scope.launch { importing = true; try { val r = withContext(Dispatchers.IO) { repo.import(f, uris) { done, total, name -> mainHandler.post { importDone = done; importTotal = total; importName = name } } }; toastImport(ctx, r); onChanged() } finally { importing = false } }
    }
    fun launchPicker(dest: File) { target = dest; ExternalFlow.active = true; picker.launch(arrayOf("*/*")) }
    Scaffold(containerColor = Bg,
        topBar = { if (selectedFiles.isNotEmpty()) TopAppBar(title = { Text("${selectedFiles.size} dipilih") },
            navigationIcon = { IconButton(onClick = { selectedFiles.clear() }) { Icon(Icons.Default.Close, "Batal pilih") } },
            actions = {
                IconButton(onClick = { selectedFiles.forEach { repo.setFav(it, true) }; selectedFiles.clear(); onChanged() }) { Icon(Icons.Default.Favorite, "Favorit") }
                IconButton(onClick = { val chosen = selectedFiles.toList(); selectedFiles.clear(); scope.launch { withContext(Dispatchers.IO) { chosen.forEach(repo::delete) }; onChanged() } }) { Icon(Icons.Default.Delete, "Pindahkan ke Sampah") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) },
        floatingActionButton = { FloatingActionButton(onClick = {
            when (folders.size) {
                0 -> { android.widget.Toast.makeText(ctx, "Buat folder dulu untuk menyimpan file", android.widget.Toast.LENGTH_SHORT).show(); newDialog = true }
                1 -> launchPicker(folders[0])
                else -> chooseDest = true
            }
        }, containerColor = Accent, shape = CircleShape) { Icon(Icons.Default.Add, "Tambah file") } },
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
        LazyColumn(state = listState, modifier = Modifier.padding(pad).background(Brush.verticalGradient(listOf(Color(0xFF0B1426), Bg, Color(0xFF080E19)))).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.padding(top = 18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RUANG PRIBADI", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Dhika App", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Semua file aman, dalam satu tempat", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onTrash, modifier = Modifier.glassSurface(RoundedCornerShape(50.dp)).size(46.dp)) { Icon(Icons.Default.DeleteOutline, "Sampah") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).padding(vertical = 16.dp)) {
                    listOf("Foto" to MediaType.PHOTO, "Video" to MediaType.VIDEO, "Komik" to MediaType.COMIC).forEach { (n, t) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("${typeCounts[t] ?: 0}", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(n, color = Muted) }
                    }
                }
            }
            item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Cari file…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
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
                    items(folders) { f ->
                        Column(Modifier.width(104.dp).glassSurface(RoundedCornerShape(17.dp)).combinedClickable(onClick = { onFolder(f) }, onLongClick = { actionFolder = f }).padding(12.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(34.dp)); Text(f.name, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text("${home?.counts?.get(f) ?: 0} item", fontSize = 11.sp, color = Muted)
                        }
                    }
                    item { Column(Modifier.width(104.dp).glassSurface(RoundedCornerShape(17.dp)).clickable { newDialog = true }.padding(12.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Add, null, modifier = Modifier.size(34.dp)); Text("Folder Baru", fontSize = 12.sp) } }
                }
            }
            item {
                Column {
                    Text("Media Terbaru", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Lanjutkan membuka foto, video, dan komik", color = Muted, fontSize = 11.sp)
                }
            }
            if (home != null && shown.isEmpty()) item {
                Text(if (q.isBlank()) "Brankas masih kosong.\nTekan + untuk menambah foto, video, atau komik." else "Tidak ada file yang cocok dengan “$q”.",
                    color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            }
            items(shown) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { file -> Thumb(repo, file, Modifier.weight(1f), selected = if (selectedFiles.isNotEmpty()) selectedFiles.contains(file) else null, onToggle = { if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file) }, onLongClick = { if (!selectedFiles.contains(file)) selectedFiles.add(file) }) { if (selectedFiles.isNotEmpty()) { if (selectedFiles.contains(file)) selectedFiles.remove(file) else selectedFiles.add(file) } else onFile(file, all) } }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (importing) AlertDialog(onDismissRequest = {}, title = { Text("Mengimpor file") }, text = { Column { LinearProgressIndicator(progress = { if (importTotal == 0) 0f else importDone.toFloat() / importTotal }, Modifier.fillMaxWidth()); Text("$importDone dari $importTotal file\n$importName", color = Muted) } }, confirmButton = {})
    if (chooseDest) AlertDialog(onDismissRequest = { chooseDest = false }, title = { Text("Simpan ke folder") }, confirmButton = {},
        dismissButton = { TextButton({ chooseDest = false }) { Text("Batal") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { folders.forEach { d ->
            TextButton({ chooseDest = false; launchPicker(d) }, Modifier.fillMaxWidth()) { Text(d.name, maxLines = 1, modifier = Modifier.fillMaxWidth()) } } } })
    if (newDialog) NameDialog("Folder Baru", "") { n -> newDialog = false; if (n != null) { if (repo.createFolder(n)) onChanged() else android.widget.Toast.makeText(ctx, "Nama folder tidak valid atau sudah ada", android.widget.Toast.LENGTH_SHORT).show() } }
    actionFile?.let { file -> AlertDialog(onDismissRequest = { actionFile = null }, title = { Text(file.name, maxLines = 1) }, text = { Text("Pilih tindakan untuk file ini.") }, confirmButton = { Row { TextButton({ actionFile = null; infoFile = file }) { Text("Informasi") }; TextButton({ actionFile = null; deleteFile = file }) { Text("Hapus") } } }, dismissButton = { TextButton({ actionFile = null }) { Text("Tutup") } }) }
    infoFile?.let { FileInfoDialog(it) { infoFile = null } }
    deleteFile?.let { file -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("Hapus file?") }, text = { Text("File akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFile = null; scope.launch { withContext(Dispatchers.IO) { repo.delete(file) }; onChanged() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFile = null }) { Text("Tidak") } }) }
    actionFolder?.let { folder -> AlertDialog(onDismissRequest = { actionFolder = null }, title = { Text(folder.name) }, text = { Text("Pilih tindakan untuk folder ini.") }, confirmButton = { Row { TextButton({ actionFolder = null; infoFolder = folder }) { Text("Informasi") }; TextButton({ actionFolder = null; deleteFolder = folder }) { Text("Hapus") } } }, dismissButton = { TextButton({ actionFolder = null }) { Text("Tutup") } }) }
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
    var tab by remember { mutableStateOf(MediaType.PHOTO) }
    val sel = remember { mutableStateListOf<VaultFile>() }
    androidx.activity.compose.BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var moveDlg by remember { mutableStateOf(false) }
    val items = remember(version, tab, rootFiles, folder) { (if (rootFiles) repo.rootFiles().filter { it.type == MediaType.PHOTO } else if (folder == null) repo.favorites() else repo.files(folder)).filter { it.type == tab } }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialScrollIndex, initialFirstVisibleItemScrollOffset = initialScrollOffset)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var importing by remember { mutableStateOf(false) }
    var importDone by remember { mutableIntStateOf(0) }; var importTotal by remember { mutableIntStateOf(0) }; var importName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<VaultFile?>(null) }
    var infoFile by remember { mutableStateOf<VaultFile?>(null) }
    var deleteFile by remember { mutableStateOf<VaultFile?>(null) }
    var actionFolder by remember { mutableStateOf(false) }
    var infoFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> val destination = folder ?: if (rootFiles) repo.rootFolder() else null; if (uris.isNotEmpty()) destination?.let { f -> scope.launch { importing = true; try { val r = withContext(Dispatchers.IO) { repo.import(f, uris) { done, total, name -> mainHandler.post { importDone = done; importTotal = total; importName = name } } }; toastImport(ctx, r); onChanged() } finally { importing = false } } } }
    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(title = { Text(if (sel.isNotEmpty()) "${sel.size} dipilih" else if (rootFiles) "Gambar di Luar Folder" else folder?.name ?: "Favorit") },
            navigationIcon = { if (sel.isNotEmpty()) IconButton({ sel.clear() }) { Icon(Icons.Default.Close, "Batal pilih") } else if (!rootFiles && folder != null) IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Kembali") } },
            actions = {
                if (sel.isNotEmpty()) IconButton(onClick = { sel.clear(); sel.addAll(items) }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                if ((folder == null || rootFiles) && sel.isNotEmpty()) {
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
        floatingActionButton = { if (folder != null || rootFiles) FloatingActionButton({ ExternalFlow.active = true; picker.launch(arrayOf("*/*")) }, containerColor = Accent) { Icon(Icons.Default.Add, "Tambah file") } },
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
            if (!rootFiles) TabRow(tab.ordinal, containerColor = Bg) { listOf("Foto", "Video", "Komik").forEachIndexed { i, n -> Tab(tab.ordinal == i, { tab = MediaType.values()[i]; sel.clear() }) { Text(n, Modifier.padding(12.dp)) } } }
            if (items.isEmpty() && !importing) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Belum ada file di sini.\nTekan + untuk menambah.", color = Muted) }
            LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    if (importing) AlertDialog(onDismissRequest = {}, title = { Text("Mengimpor file") }, text = { Column { LinearProgressIndicator(progress = { if (importTotal == 0) 0f else importDone.toFloat() / importTotal }, Modifier.fillMaxWidth()); Text("$importDone dari $importTotal file\n$importName", color = Muted) } }, confirmButton = {})
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
    if (infoFolder && folder != null) FolderInfoDialog(folder) { infoFolder = false }
    if (deleteFolder && folder != null) AlertDialog(onDismissRequest = { deleteFolder = false }, title = { Text("Hapus folder?") }, text = { Text("Isi folder akan dipindahkan ke Sampah selama 30 hari.") }, confirmButton = { TextButton({ deleteFolder = false; scope.launch { withContext(Dispatchers.IO) { repo.deleteFolder(folder) }; onChanged(); onBack() } }) { Text("Ya, hapus") } }, dismissButton = { TextButton({ deleteFolder = false }) { Text("Tidak") } })
}

/* ---------- PENGATURAN ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(pm: PinManager, onBack: () -> Unit) {
    var bio by remember { mutableStateOf(pm.biometric) }
    var auto by remember { mutableStateOf(pm.autoLockMs) }
    var pinDlg by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
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
            Text("Semua file & thumbnail dienkripsi AES-256 di penyimpanan privat aplikasi.", color = Muted, fontSize = 13.sp)
            Text("Tentang", color = Muted, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp)) { Text("Versi Aplikasi", Modifier.weight(1f)); Text(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-", color = Muted) }
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
