package com.example.privatevault.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password backup salah, atau isi file backup sudah rusak/diubah. */
class WrongBackupPasswordException : IOException("Password salah atau file backup rusak")

/**
 * Format backup Dhika App (.dhikabak).
 *
 * Kunci Android Keystore yang melindungi brankas TIDAK ikut terbawa saat aplikasi dihapus/dipasang ulang,
 * karena itu backup dienkripsi ulang dengan kunci yang diturunkan dari password pengguna:
 *   - PBKDF2-HMAC-SHA256 (200.000 iterasi) + salt acak -> kunci AES-256
 *   - isi dipecah per 64 KB, tiap potongan AES-256-GCM dengan nonce unik (prefix acak + penghitung)
 *   - penanda "potongan terakhir" ikut diautentikasi, jadi file yang terpotong/diutak-atik terdeteksi
 * Dengan begitu backup bisa diproses secara streaming (aman untuk video berukuran GB).
 */
object BackupCrypto {
    private val MAGIC = "DHIKABK1".toByteArray(Charsets.US_ASCII)
    const val CHUNK = 64 * 1024
    private const val ITERATIONS = 200_000
    private const val TAG_BYTES = 16

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally { spec.clearPassword() }
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray {
        val iv = ByteArray(12)
        System.arraycopy(prefix, 0, iv, 0, 8)
        iv[8] = (counter ushr 24).toByte(); iv[9] = (counter ushr 16).toByte()
        iv[10] = (counter ushr 8).toByte(); iv[11] = counter.toByte()
        return iv
    }

    class Writer(private val out: OutputStream, password: CharArray) : OutputStream() {
        private val key: SecretKeySpec
        private val prefix = ByteArray(8)
        private var counter = 0
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var finished = false

        init {
            val random = SecureRandom()
            val salt = ByteArray(16).also { random.nextBytes(it) }
            random.nextBytes(prefix)
            out.write(MAGIC); out.write(salt); out.write(prefix)
            out.write(intBytes(ITERATIONS))
            key = deriveKey(password, salt, ITERATIONS)
        }

        override fun write(b: Int) {
            if (n == CHUNK) flushChunk(false)
            buf[n++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                if (n == CHUNK) flushChunk(false)
                val c = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; l -= c
            }
        }

        private fun flushChunk(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            val ct = cipher.doFinal(buf, 0, n)
            out.write(if (last) 1 else 0)
            out.write(intBytes(ct.size))
            out.write(ct)
            n = 0; counter++
        }

        /** Wajib dipanggil di akhir: menulis potongan terakhir sebagai penanda file lengkap. */
        fun finish() {
            if (finished) return
            finished = true
            flushChunk(true)
            out.flush()
        }

        override fun flush() { /* data baru ditulis per potongan penuh / saat finish() */ }
    }

    class Reader(private val input: InputStream, password: CharArray) : InputStream() {
        private val key: SecretKeySpec
        private val prefix = ByteArray(8)
        private var counter = 0
        private var buf = ByteArray(0)
        private var pos = 0
        private var eof = false

        init {
            val magic = readFully(input, MAGIC.size) ?: throw IOException("Bukan file backup Dhika App")
            if (!magic.contentEquals(MAGIC)) throw IOException("Bukan file backup Dhika App")
            val salt = readFully(input, 16) ?: throw IOException("File backup rusak")
            System.arraycopy(readFully(input, 8) ?: throw IOException("File backup rusak"), 0, prefix, 0, 8)
            val iterations = toInt(readFully(input, 4) ?: throw IOException("File backup rusak"))
            if (iterations !in 10_000..5_000_000) throw IOException("File backup rusak")
            key = deriveKey(password, salt, iterations)
        }

        override fun read(): Int {
            if (!fill()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val c = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, c)
            pos += c
            return c
        }

        private fun fill(): Boolean {
            while (pos >= buf.size) {
                if (eof) return false
                readChunk()
            }
            return true
        }

        private fun readChunk() {
            val header = readFully(input, 5) ?: throw IOException("Backup tidak lengkap (file terpotong)")
            val last = header[0].toInt() == 1
            val len = toInt(header, 1)
            if (len < TAG_BYTES || len > CHUNK + TAG_BYTES) throw IOException("File backup rusak")
            val ct = readFully(input, len) ?: throw IOException("Backup tidak lengkap (file terpotong)")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            buf = try { cipher.doFinal(ct) } catch (e: AEADBadTagException) { throw WrongBackupPasswordException() }
            pos = 0
            counter++
            if (last) eof = true
        }
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun toInt(b: ByteArray, off: Int = 0): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** Baca tepat [n] byte, atau null jika stream habis sebelum cukup. */
    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val data = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(data, got, n - got)
            if (r < 0) return null
            got += r
        }
        return data
    }
}
