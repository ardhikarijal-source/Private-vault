package com.example.privatevault.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privatevault.data.VaultRepository
import com.example.privatevault.data.VaultFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pekerjaan panjang (backup/pulihkan) dijalankan di scope tingkat aplikasi, bukan di layar,
 * sehingga tidak ikut batal saat layar berganti atau aplikasi terkunci otomatis.
 */
object Ops {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    var title by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var detail by mutableStateOf("")
        private set
    var result by mutableStateOf<String?>(null)
    var progressVisible by mutableStateOf(true)

    val running: Boolean get() = title != null
    fun hideProgress() { progressVisible = false }
    fun showProgress() { progressVisible = true }

    fun start(title: String, onDone: () -> Unit = {}, block: suspend (report: (Float, String) -> Unit) -> String) {
        if (running) return
        this.title = title; progress = 0f; detail = ""; result = null; progressVisible = true
        job = scope.launch {
            val message = try {
                block { p, d -> progress = p.coerceIn(0f, 1f); detail = d }
            } catch (e: CancellationException) {
                "Dibatalkan."
            } catch (e: Exception) {
                "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(NonCancellable) {
                Ops.title = null
                result = message
                onDone()
            }
        }
    }

    fun cancel() { job?.cancel() }
}

/**
 * Meminta folder tujuan, lalu mengekspor salinan file terpilih tanpa mengubah isi brankas.
 */
@Composable
fun rememberExportUi(repo: VaultRepository, onChanged: () -> Unit = {}): (List<VaultFile>) -> Unit {
    val onChangedNow by rememberUpdatedState(onChanged)
    var pending by remember { mutableStateOf<List<VaultFile>?>(null) }
    pending?.let { files ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Pindahkan ke Downloads?") },
            text = { Text("${files.size} file akan dipindahkan ke Downloads/Dhika App. File yang berhasil dipindahkan akan dihapus dari brankas. Jika gagal, file tetap tersimpan di brankas.") },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pending = null }, modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Batal") }
                    Button(onClick = {
                        val chosen = pending.orEmpty()
                        pending = null
                        if (chosen.isNotEmpty() && !Ops.running) {
                            Ops.start("Memindahkan file ke Downloads/Dhika App", onDone = { onChangedNow() }) { report ->
                                val job = currentCoroutineContext()
                                val result = withContext(Dispatchers.IO) {
                                    repo.exportToDownloads(chosen, shouldContinue = { job[Job]?.isActive == true }) { done, total, name ->
                                        report(if (total == 0) 1f else done.toFloat() / total, "$done dari $total · $name")
                                    }
                                }
                                "Selesai: ${result.exported} file dipindahkan ke Downloads/Dhika App" +
                                    (if (result.failed > 0) ". ${result.failed} gagal dan tetap tersimpan di brankas." else ".") +
                                    (if (result.cancelled) " Proses dibatalkan; file yang belum diproses tetap di brankas." else "")
                            }
                        }
                    }, modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Pindahkan") }
                }
            },
            dismissButton = { OutlinedButton(onClick = { pending = null }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Batal") } }
        )
    }
    return remember {
        { files: List<VaultFile> -> if (files.isNotEmpty() && !Ops.running) pending = files.toList() }
    }
}

/** Dialog progres + hasil untuk pekerjaan di [Ops]. Dipasang sekali di level atas aplikasi. */
@Composable
fun OpsHost() {
    val title = Ops.title
    val result = Ops.result
    val view = LocalView.current
    DisposableEffect(title != null) {
        view.keepScreenOn = title != null // jaga layar tetap menyala selama backup/pemulihan
        onDispose { view.keepScreenOn = false }
    }
    if (title != null && Ops.progressVisible) {
        AlertDialog(onDismissRequest = {}, title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ops.hideProgress() }) { Icon(Icons.Default.Close, "Sembunyikan") }
            }
        },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { Ops.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    Text(Ops.detail, color = Muted, fontSize = 13.sp, maxLines = 2)
                    Text("Jangan tutup aplikasi sampai selesai.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { Ops.cancel() }, modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Batalkan") }
                    Button(onClick = { Ops.hideProgress() }, modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Sembunyikan") }
                }
            },
            dismissButton = {})
    } else if (title != null) {
        ProgressChip("Proses berjalan · Tampilkan progres", Ops.progress) { Ops.showProgress() }
    } else if (result != null) {
        AlertDialog(onDismissRequest = { Ops.result = null }, title = { Text("Selesai") }, text = { Text(result) },
            confirmButton = {
                Button({ Ops.result = null }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("OK") }
            })
    }
}

/** Dialog password. Untuk membuat backup ([confirm] = true) password harus diketik dua kali. */
@Composable
private fun PasswordDialog(title: String, message: String, confirm: Boolean, done: (String?) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val hide = androidx.compose.ui.text.input.PasswordVisualTransformation()
    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
    AlertDialog(onDismissRequest = { done(null) }, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = Muted, fontSize = 13.sp)
                OutlinedTextField(pw, { pw = it; error = null }, label = { Text("Password backup") }, singleLine = true, visualTransformation = hide, keyboardOptions = kb)
                if (confirm) OutlinedTextField(pw2, { pw2 = it; error = null }, label = { Text("Ulangi password") }, singleLine = true, visualTransformation = hide, keyboardOptions = kb)
                error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B73), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton({
                when {
                    confirm && pw.length < 6 -> error = "Password minimal 6 karakter"
                    confirm && pw != pw2 -> error = "Kedua password tidak sama"
                    pw.isEmpty() -> error = "Masukkan password"
                    else -> done(pw)
                }
            }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text(if (confirm) "Lanjut" else "Pulihkan") }
        },
        dismissButton = { OutlinedButton({ done(null) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("Batal") } })
}

class BackupUi(val startBackup: () -> Unit, val startRestore: () -> Unit)

/** Menyiapkan alur "Buat Backup" dan "Pulihkan Backup" (picker + password + proses). Dipakai di Pengaturan dan Beranda. */
@Composable
fun rememberBackupUi(repo: VaultRepository, onChanged: () -> Unit): BackupUi {
    val app = LocalContext.current.applicationContext
    var backupDlg by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPw by remember { mutableStateOf<String?>(null) }
    val onChangedNow by rememberUpdatedState(onChanged)

    fun runBackup(treeUri: Uri, pw: String) {
        Ops.start("Memperbarui backup") { report ->
            val job = currentCoroutineContext()
            val folder = DocumentFile.fromTreeUri(app, treeUri) ?: throw IOException("Folder backup tidak dapat diakses")
            if (!folder.isDirectory || !folder.canWrite()) throw IOException("Folder backup tidak memiliki izin tulis")
            val finalName = "DhikaApp-backup.dhikabak"
            val tempName = "DhikaApp-backup-${System.currentTimeMillis()}.tmp"
            val temp = folder.createFile("application/octet-stream", tempName)
                ?: throw IOException("Tidak bisa membuat file backup sementara")
            var oldBackup: DocumentFile? = null
            try {
                val out = app.contentResolver.openOutputStream(temp.uri, "w") ?: throw IOException("Tidak bisa menulis backup")
                val count = out.use { raw -> repo.backupTo(BufferedOutputStream(raw, 64 * 1024), pw.toCharArray(), report) { job.ensureActive() } }
                // Backup baru harus selesai dahulu sebelum backup lama diganti.
                oldBackup = folder.findFile(finalName)
                if (oldBackup != null && !oldBackup!!.renameTo("$finalName.previous")) {
                    throw IOException("Backup lama tidak dapat diamankan untuk diganti")
                }
                if (!temp.renameTo(finalName)) {
                    oldBackup?.renameTo(finalName)
                    throw IOException("Tidak dapat menetapkan nama backup baru")
                }
                // Bersihkan backup lama dengan pola nama aplikasi, termasuk format bertanggal dari versi sebelumnya.
                folder.listFiles()
                    .filter { it.name?.startsWith("DhikaApp-backup") == true &&
                        (it.name?.endsWith(".dhikabak") == true || it.name == "$finalName.previous") &&
                        it.name != finalName }
                    .forEach { it.delete() }
                "Backup diperbarui: $count file. Backup lama di folder ini digantikan."
            } catch (e: Exception) {
                if (temp.exists()) temp.delete()
                throw e
            }
        }
    }

    fun runRestore(uri: Uri, pw: String) {
        Ops.start("Memulihkan backup", onDone = { onChangedNow() }) { report ->
            val job = currentCoroutineContext()
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membaca file backup")
            input.use { raw ->
                val r = repo.restoreFrom(BufferedInputStream(raw, 64 * 1024), pw.toCharArray(), report) { job.ensureActive() }
                "Pemulihan selesai.\n${r.restored} file dipulihkan" + if (r.skipped > 0) ", ${r.skipped} dilewati (sudah ada atau tidak valid)." else "."
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pw = pendingPw; pendingPw = null
        if (uri != null && pw != null) runBackup(uri, pw)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreUri = uri }

    if (backupDlg) PasswordDialog("Buat / Perbarui Backup",
        "Pilih folder penyimpanan. File DhikaApp-backup.dhikabak di folder tersebut akan diganti dengan backup terbaru setelah backup baru selesai dibuat. Password dibutuhkan untuk memulihkan data.", true) { pw ->
        backupDlg = false
        if (pw != null) {
            pendingPw = pw
            ExternalFlow.active = true
            folderPicker.launch(null)
        }
    }
    restoreUri?.let { uri ->
        PasswordDialog("Pulihkan Backup", "Masukkan password yang dipakai saat backup dibuat.", false) { pw ->
            restoreUri = null
            if (pw != null) runRestore(uri, pw)
        }
    }
    return remember {
        BackupUi(
            startBackup = { if (!Ops.running) backupDlg = true },
            startRestore = { if (!Ops.running) { ExternalFlow.active = true; open.launch(arrayOf("*/*")) } })
    }
}

/**
 * Picker file yang meminta izin TULIS, supaya file asli bisa dihapus setelah diamankan ke brankas
 * (memindahkan, bukan menyalin).
 */
class PickDocsForMove : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null || resultCode != Activity.RESULT_OK) return emptyList()
        val out = mutableListOf<Uri>()
        intent.data?.let { out.add(it) }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) } }
        return out.distinct()
    }
}

/** Ubah URI dokumen "Galeri" (media provider) menjadi URI MediaStore agar bisa dihapus lewat permintaan sistem. */
private fun mediaStoreUri(uri: Uri): Uri? {
    if (uri.authority != "com.android.providers.media.documents") return null
    val parts = runCatching { DocumentsContract.getDocumentId(uri).split(":", limit = 2) }.getOrNull() ?: return null
    val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val base = when (parts[0]) {
        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> return null
    }
    return ContentUris.withAppendedId(base, id)
}

/**
 * Cadangan untuk file asli yang tidak bisa dihapus langsung: di Android 11+ minta izin penghapusan lewat dialog sistem
 * (satu kali untuk semua file). Jika tetap tidak bisa, beri tahu pengguna agar menghapus manual.
 */
@Composable
fun rememberOriginalsRemover(): (List<Uri>) -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    return remember {
        { uris: List<Uri> ->
            fun manual() = android.widget.Toast.makeText(ctx,
                "${uris.size} file asli belum terhapus (lokasinya tidak mengizinkan). Hapus manual lewat Galeri/File.", android.widget.Toast.LENGTH_LONG).show()
            val media = if (android.os.Build.VERSION.SDK_INT >= 30) uris.mapNotNull { mediaStoreUri(it) } else emptyList()
            if (media.isEmpty()) manual() else {
                val started = runCatching {
                    val sender = MediaStore.createDeleteRequest(ctx.contentResolver, media).intentSender
                    ExternalFlow.active = true
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }.isSuccess
                if (!started) manual()
            }
            Unit
        }
    }
}
