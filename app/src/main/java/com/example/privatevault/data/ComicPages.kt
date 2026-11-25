package com.example.privatevault.data

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Pengurutan alami: angka dibandingkan sebagai angka, jadi 2 < 10. */
object NaturalSort {
    val ORDER: Comparator<String> = Comparator { a, b -> compare(a, b) }

    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** Aturan halaman CBZ: hanya gambar, buang entri sampah zip, urut alami. */
object ComicPages {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val COVER_NAMES = setOf("cover", "folder", "front", "frontcover", "sampul")

    private fun isJunk(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        return parts.any { it.startsWith(".") || it.equals("__MACOSX", ignoreCase = true) } ||
            parts.last().equals("Thumbs.db", ignoreCase = true)
    }

    fun isPage(entry: ZipEntry): Boolean =
        !entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in IMAGE_EXT && !isJunk(entry.name)

    fun pages(zip: ZipFile): List<String> =
        zip.entries().asSequence().filter { isPage(it) }.map { it.name }.sortedWith(NaturalSort.ORDER).toList()

    fun coverCandidates(pages: List<String>): List<String> {
        val named = pages.filter { p -> p.substringAfterLast('/').substringBeforeLast('.').lowercase() in COVER_NAMES }
        return (named + pages.take(4)).distinct()
    }
}
