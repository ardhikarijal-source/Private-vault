package com.example.privatevault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.core.content.edit
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipFile
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class MediaType { PHOTO, VIDEO, COMIC }

data class VaultFile(val file: File) {
    val name: String get() = file.name
    val type: MediaType? get() = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> MediaType.PHOTO
        "mp4", "m4v", "mov", "webm", "mkv", "3gp" -> MediaType.VIDEO
        "cbz" -> MediaType.COMIC
        else -> null
    }
}

/**
 * Semua file & thumbnail dienkripsi (AES-256-GCM via Jetpack Security, kunci di Android Keystore)
 * dan disimpan di filesDir/vault. Untuk dilihat, file didekripsi sementara ke cacheDir lalu dihapus.
 * Catatan: nama file (bukan isinya) tetap terbaca di dalam penyimpanan privat aplikasi.
 */
class VaultRepository(private val ctx: Context) {
    private val root = File(ctx.filesDir, "vault").apply { mkdirs(); File(this, ".nomedia").createNewFile() }
    private val cache = File(ctx.cacheDir, "view").apply { mkdirs() }
    private val trashDir = File(root, ".trash").apply { mkdirs() }
    private val trashPrefs = ctx.getSharedPreferences("vault_trash", Context.MODE_PRIVATE)
    private val prefs = ctx.getSharedPreferences("vault_meta", Context.MODE_PRIVATE)
    private val master by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val thumbMem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun enc(f: File) = EncryptedFile.Builder(ctx, f, master, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    private fun encryptFrom(plain: File, dest: File) { enc(dest).openFileOutput().use { o -> plain.inputStream().use { it.copyTo(o) } } }
    private fun decryptTo(src: File, dest: File) { enc(src).openFileInput().use { i -> dest.outputStream().use { i.copyTo(it) } } }
    private fun thumbFile(f: File) = File(File(f.parentFile, ".thumbs").apply { mkdirs() }, f.name + ".jpg")

    /* ---- folder ---- */
    fun rootFolder(): File = root
    fun rootFiles(): List<VaultFile> = listMedia(root)
    fun folders(): List<File> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    private fun safeFolderName(name: String): String? {
        val cleaned = name.trim().replace(Regex("[/\\\\]"), "_")
        return cleaned.replace('|', '_').takeIf { it.isNotBlank() && it != "." && it != ".." && !it.startsWith(".") }
    }
    fun createFolder(name: String): Boolean {
        val safe = safeFolderName(name) ?: return false
        return File(root, safe).mkdirs()
    }
    fun renameFolder(f: File, name: String): Boolean {
        val safe = safeFolderName(name) ?: return false
        if (f.parentFile != root || !f.isDirectory || safe == f.name) return false
        val target = File(root, safe)
        if (target.exists()) return false
        val oldPrefix = f.absolutePath + File.separator
        if (!f.renameTo(target)) return false
        // Favorit disimpan berdasarkan path, jadi harus ikut diperbarui ke nama folder baru.
        val favs = prefs.getStringSet("fav", emptySet())!!
        if (favs.any { it.startsWith(oldPrefix) }) {
            val updated = favs.map { if (it.startsWith(oldPrefix)) target.absolutePath + File.separator + it.removePrefix(oldPrefix) else it }.toSet()
            prefs.edit { putStringSet("fav", updated) }
        }
        thumbMem.evictAll()
        return true
    }
    fun deleteFolder(f: File): Boolean {
        if (f.parentFile != root || !f.isDirectory || f.name == ".trash") return false
        files(f).forEach(::delete)
        return f.deleteRecursively()
    }

    /* ---- file ---- */
    fun files(folder: File): List<VaultFile> = listMedia(folder)
    fun allFiles(): List<VaultFile> =
        (rootFiles() + folders().flatMap { files(it) })
            .map { it to it.file.lastModified() }.sortedByDescending { it.second }.map { it.first }

    /** Daftar file media terbaru di atas. lastModified() hanya dibaca sekali per file (bukan tiap perbandingan sort). */
    private fun listMedia(dir: File): List<VaultFile> {
        val list = dir.listFiles { f -> f.isFile } ?: return emptyList()
        return list.map { VaultFile(it) }.filter { it.type != null }
            .map { it to it.file.lastModified() }.sortedByDescending { it.second }.map { it.first }
    }

    /** Enkripsi file plain ke folder tujuan + buat thumbnail terenkripsi. */
    private fun store(plain: File, folder: File, name: String): File {
        val dest = unique(folder, name)
        runCatching { makeThumb(plain)?.let { bmp ->
            val t = File(cache, "t_${System.nanoTime()}.jpg")
            t.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            encryptFrom(t, thumbFile(dest)); t.delete() } }
        encryptFrom(plain, dest)
        return dest
    }

    data class ImportResult(val imported: Int, val skipped: Int)

    fun import(folder: File, uris: List<Uri>, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): ImportResult {
        var n = 0; var skipped = 0
        uris.forEachIndexed { index, uri ->
            onProgress(index, uris.size, "Menyiapkan file ${index + 1} dari ${uris.size}")
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) c.getString(column) else null
                    } else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
                ?: "file_${System.currentTimeMillis()}"
            if (VaultFile(File(name)).type == null) { skipped++; return@forEachIndexed }
            val tmp = File(cache, "imp_${System.nanoTime()}.${name.substringAfterLast('.')}")
            try {
                val input = ctx.contentResolver.openInputStream(uri)
                if (input == null) { skipped++; return@forEachIndexed }
                input.use { source -> tmp.outputStream().use { target -> source.copyTo(target) } }
                store(tmp, folder, name); n++
            } catch (_: Exception) {
                skipped++ // Lewati file yang gagal dibaca; lanjutkan impor berikutnya.
            } finally { tmp.delete() }
            onProgress(index + 1, uris.size, "Memproses $name")
        }
        return ImportResult(n, skipped)
    }
    fun move(f: VaultFile, to: File): Boolean {
        if (!to.isDirectory || to == f.file.parentFile) return false
        val plain = runCatching { open(f) }.getOrNull() ?: return false
        return try {
            val dest = runCatching { store(plain, to, f.name) }.getOrNull() ?: return false
            if (!f.file.delete()) { dest.delete(); return false }
            thumbFile(f.file).delete()
            val favs = prefs.getStringSet("fav", emptySet())!!.toMutableSet()
            if (favs.remove(f.file.absolutePath)) { favs.add(dest.absolutePath); prefs.edit { putStringSet("fav", favs) } }
            thumbMem.remove(f.file.absolutePath)
            true
        } finally { plain.delete() }
    }
    fun copy(f: VaultFile, destination: File = f.file.parentFile!!): Boolean {
        if (!destination.isDirectory || !destination.canonicalPath.startsWith(root.canonicalPath)) return false
        val plain = runCatching { open(f) }.getOrNull() ?: return false
        return try { runCatching { store(plain, destination, f.name) }.isSuccess }
        finally { plain.delete() }
    }
    private fun reencryptFile(source: File, destination: File): Boolean {
        val temp = File(cache, "move_${System.nanoTime()}.tmp")
        return try {
            decryptTo(source, temp)
            encryptFrom(temp, destination)
            true
        } catch (_: Exception) {
            destination.delete()
            false
        } finally { temp.delete() }
    }

    fun delete(f: VaultFile) {
        val dest = unique(trashDir, "${System.currentTimeMillis()}_${f.name}")
        val originalFolder = if (f.file.parentFile == root) "__ROOT__" else f.file.parentFile?.name.orEmpty()
        if (runCatching { f.file.renameTo(dest) }.getOrDefault(false)) {
            val thumb = thumbFile(f.file)
            val trashThumb = File(trashDir, dest.name + ".thumb")
            if (thumb.isFile && reencryptFile(thumb, trashThumb)) thumb.delete()
            val deletedAt = System.currentTimeMillis()
            val encodedFolder = Base64.encodeToString(originalFolder.toByteArray(), Base64.NO_WRAP)
            val encodedName = Base64.encodeToString(f.name.toByteArray(), Base64.NO_WRAP)
            trashPrefs.edit { putString(dest.name, "v2|${encodedFolder}|${encodedName}|${deletedAt}") }
        }
        setFav(f, false)
        thumbMem.remove(f.file.absolutePath)
    }

    data class TrashedFile(val file: File, val originalName: String, val deletedAt: Long)

    fun trashThumb(item: TrashedFile): Bitmap? {
        val thumbnail = File(trashDir, item.file.name + ".thumb")
        val fromSavedThumb = runCatching {
            if (!thumbnail.isFile) null else {
                decodeThumb(enc(thumbnail).openFileInput().use { it.readBytes() })
            }
        }.getOrNull()
        if (fromSavedThumb != null) return fromSavedThumb
        // Fallback untuk item lama atau thumbnail yang gagal dipindahkan: buat ulang dari file terenkripsi.
        val temp = File(cache, "trash_thumb_${System.nanoTime()}_${item.originalName}")
        return try {
            decryptTo(item.file, temp)
            makeThumb(temp)
        } catch (_: Exception) { null }
        finally { temp.delete() }
    }

    fun trashedFiles(): List<TrashedFile> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<TrashedFile>()
        trashDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".thumb") }?.forEach { file ->
            val meta = trashPrefs.getString(file.name, null)
            if (meta == null) {
                // Metadata hilang? Pertahankan file agar tidak terhapus otomatis.
                result += TrashedFile(file, file.name, file.lastModified())
            } else {
                val p = meta.split("|", limit = 4)
                val isV2 = p.firstOrNull() == "v2"
                val originalName = if (isV2) runCatching { String(Base64.decode(p[2], Base64.NO_WRAP)) }.getOrDefault(file.name) else p.getOrNull(1) ?: file.name
                val deletedAt = (if (isV2) p.getOrNull(3) else p.getOrNull(2))?.toLongOrNull() ?: file.lastModified()
                if (now - deletedAt >= 30L * 24 * 60 * 60 * 1000) {
                    file.delete(); File(trashDir, file.name + ".thumb").delete()
                    trashPrefs.edit { remove(file.name) }
                } else result += TrashedFile(file, originalName, deletedAt)
            }
        }
        return result.sortedByDescending { it.deletedAt }
    }

    fun restore(item: TrashedFile): Boolean {
        val meta = trashPrefs.getString(item.file.name, null) ?: return false
        val p = meta.split("|", limit = 4)
        val isV2 = p.firstOrNull() == "v2"
        val folderName = if (isV2) runCatching { String(Base64.decode(p[1], Base64.NO_WRAP)) }.getOrDefault("") else p.firstOrNull().orEmpty()
        val originalName = if (isV2) runCatching { String(Base64.decode(p[2], Base64.NO_WRAP)) }.getOrDefault(item.originalName) else item.originalName
        if (folderName.isBlank() || (folderName != "__ROOT__" && (folderName == "." || folderName == ".." || folderName.contains("/") || folderName.contains("\\")))) return false
        val folder = if (folderName == "__ROOT__") root else File(root, folderName)
        if (!folder.exists() && !folder.mkdirs()) return false
        if (folder != root && folder.parentFile != root) return false
        if (!folder.isDirectory) return false
        val dest = unique(folder, originalName)
        if (!item.file.renameTo(dest)) return false
        val oldThumb = File(trashDir, item.file.name + ".thumb")
        if (oldThumb.isFile && reencryptFile(oldThumb, thumbFile(dest))) oldThumb.delete()
        trashPrefs.edit { remove(item.file.name) }
        return true
    }

    fun permanentlyDelete(item: TrashedFile) {
        item.file.delete(); File(trashDir, item.file.name + ".thumb").delete()
        trashPrefs.edit { remove(item.file.name) }
    }

    /** Dekripsi sementara untuk dilihat/diputar. Hapus file hasilnya setelah selesai. */
    fun open(f: VaultFile): File {
        val out = File(cache, "${System.nanoTime()}_${f.name}"); decryptTo(f.file, out); return out
    }
    fun clearCache() { cache.listFiles()?.forEach { it.delete() }; thumbMem.evictAll() }

    fun thumb(f: VaultFile): Bitmap? {
        val cached = thumbMem.get(f.file.absolutePath)
        if (cached != null) return cached
        val tf = thumbFile(f.file)
        if (!tf.isFile) return null // tidak ada thumbnail: lewati tanpa inisialisasi enkripsi
        return runCatching { decodeThumb(enc(tf).openFileInput().use { it.readBytes() }) }.getOrNull()
            ?.also { thumbMem.put(f.file.absolutePath, it) }
    }

    private fun decodeThumb(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    private fun scaleDown(src: Bitmap, max: Int = 512): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= max) return src
        val r = max.toFloat() / longest
        val out = Bitmap.createScaledBitmap(src, (src.width * r).toInt().coerceAtLeast(1), (src.height * r).toInt().coerceAtLeast(1), true)
        if (out !== src) src.recycle()
        return out
    }

    /** Putar thumbnail sesuai orientasi EXIF supaya foto portrait tidak miring. */
    @Suppress("DEPRECATION")
    private fun applyExif(bmp: Bitmap, path: String): Bitmap {
        val orientation = runCatching {
            android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val m = android.graphics.Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return bmp
        }
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }

    private fun sampled(open: () -> InputStream?): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) }
        var sample = 1
        val longest = maxOf(b.outWidth, b.outHeight)
        while (longest / (sample * 2) >= 512) sample *= 2
        return open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    }
    private fun makeThumb(plain: File): Bitmap? = when (VaultFile(plain).type) {
        MediaType.PHOTO -> sampled { plain.inputStream() }?.let { applyExif(it, plain.path) }
        MediaType.VIDEO -> MediaMetadataRetriever().run {
            try {
                setDataSource(plain.path)
                (getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: getFrameAtTime(0))?.let { scaleDown(it) }
            } finally { release() }
        }
        MediaType.COMIC -> ZipFile(plain).use { z ->
            val e = z.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }.minByOrNull { it.name.lowercase() }
            e?.let { sampled { z.getInputStream(it) } } }
        null -> null
    }

    /* ---- favorit ---- */
    fun isFav(f: VaultFile) = prefs.getStringSet("fav", emptySet())!!.contains(f.file.absolutePath)
    fun setFav(f: VaultFile, on: Boolean) = prefs.edit {
        val s = prefs.getStringSet("fav", emptySet())!!.toMutableSet()
        if (on) s.add(f.file.absolutePath) else s.remove(f.file.absolutePath); putStringSet("fav", s)
    }
    fun favorites(): List<VaultFile> {
        val favs = prefs.getStringSet("fav", emptySet())!!
        return allFiles().filter { it.file.absolutePath in favs }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var i = 1
        while (f.exists()) { f = File(dir, "${name.substringBeforeLast('.')}_${i++}.${name.substringAfterLast('.')}") }
        return f
    }
}

/** PIN disimpan sebagai hash PBKDF2 + salt di EncryptedSharedPreferences. */
class PinManager(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "vault_secure", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    val hasPin get() = prefs.contains("hash")
    /** Sisa waktu (ms) PIN masih dikunci akibat terlalu banyak percobaan salah. */
    fun lockRemainingMs(): Long = (prefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    var biometric: Boolean
        get() = prefs.getBoolean("bio", true)
        set(v) = prefs.edit { putBoolean("bio", v) }
    /** Jeda sebelum terkunci setelah aplikasi ke background (ms). 0 = langsung. */
    var autoLockMs: Long
        get() = prefs.getLong("autolock", 60_000L)
        set(v) = prefs.edit { putLong("autolock", v) }

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit { putString("salt", Base64.encodeToString(salt, 0)); putString("hash", hash(pin, salt)) }
    }
    fun changePin(old: String, new: String): Boolean {
        if (new.length != 4 || !new.all { it.isDigit() } || !verify(old)) return false
        set(new); return true
    }
    fun verify(pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < prefs.getLong("pin_lock_until", 0L)) return false
        val salt = Base64.decode(prefs.getString("salt", "") ?: return false, 0)
        val valid = MessageDigest.isEqual(hash(pin, salt).toByteArray(), (prefs.getString("hash", "") ?: "").toByteArray())
        if (valid) {
            prefs.edit { remove("pin_failures"); remove("pin_lock_until"); remove("pin_rounds") }
        } else {
            val failures = prefs.getInt("pin_failures", 0) + 1
            prefs.edit {
                if (failures >= 5) {
                    // Jeda makin lama tiap putaran gagal: 30 dtk, 1 mnt, 2 mnt, ... maksimal 1 jam.
                    val rounds = prefs.getInt("pin_rounds", 0) + 1
                    val wait = (30_000L shl (rounds - 1).coerceAtMost(7)).coerceAtMost(3_600_000L)
                    putLong("pin_lock_until", now + wait)
                    putInt("pin_rounds", rounds)
                    putInt("pin_failures", 0)
                } else putInt("pin_failures", failures)
            }
        }
        return valid
    }
    private fun hash(pin: String, salt: ByteArray) = Base64.encodeToString(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded, 0)
}
