package com.example.privatevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privatevault.data.NaturalSort
import com.example.privatevault.data.VaultFile

enum class SortField(val label: String, val icon: ImageVector, val ascLabel: String, val descLabel: String) {
    DATE("Tanggal", Icons.Default.Schedule, "Terlama dulu", "Terbaru dulu"),
    NAME("Nama", Icons.Default.SortByAlpha, "A → Z", "Z → A"),
    TYPE("Jenis file", Icons.Default.Category, "Foto → Video → Komik", "Komik → Video → Foto"),
    SIZE("Ukuran", Icons.Default.Storage, "Terkecil dulu", "Terbesar dulu")
}

data class SortSpec(val field: SortField = SortField.DATE, val ascending: Boolean = false) {
    fun encode(): String = field.name + ":" + (if (ascending) "asc" else "desc")
    fun summary(): String = field.label + (if (ascending) " ↑" else " ↓")

    companion object {
        fun decode(s: String?): SortSpec {
            if (s == null) return SortSpec()
            val parts = s.split(':')
            val field = SortField.values().firstOrNull { it.name == parts.getOrNull(0) } ?: return SortSpec()
            return SortSpec(field, parts.getOrNull(1) == "asc")
        }
    }
}

/** Urutkan sesuai spesifikasi; nama memakai urutan alami (img2 sebelum img10). */
fun sortFiles(list: List<VaultFile>, spec: SortSpec, typeRank: (VaultFile) -> Int): List<VaultFile> {
    val ascending: List<VaultFile> = when (spec.field) {
        SortField.DATE -> list.map { it to it.file.lastModified() }.sortedBy { it.second }.map { it.first }
        SortField.SIZE -> list.map { it to it.file.length() }.sortedBy { it.second }.map { it.first }
        SortField.NAME -> list.sortedWith(Comparator { a, b -> NaturalSort.compare(a.name, b.name) })
        SortField.TYPE -> list.sortedWith(
            compareBy<VaultFile> { typeRank(it) }
                .thenBy { it.file.extension.lowercase() }
                .then(Comparator { a, b -> NaturalSort.compare(a.name, b.name) })
        )
    }
    return if (spec.ascending) ascending else ascending.reversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(current: SortSpec, onChange: (SortSpec) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card2) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Urutkan", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(true to ("Naik" to Icons.Default.ArrowUpward), false to ("Turun" to Icons.Default.ArrowDownward)).forEach { (asc, info) ->
                    val active = current.ascending == asc
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (active) Accent else Color.Transparent)
                            .clickable { onChange(current.copy(ascending = asc)) }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(info.second, null, tint = if (active) Color.White else Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(info.first, color = if (active) Color.White else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SortField.values().forEach { field ->
                val active = current.field == field
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (active) Color(0x222F7BFF) else Color.Transparent)
                        .clickable { onChange(current.copy(field = field)) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(field.icon, null, tint = if (active) Accent else Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(field.label, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        Text(if (current.ascending) field.ascLabel else field.descLabel, color = Muted, fontSize = 12.sp)
                    }
                    if (active) Icon(Icons.Default.CheckCircle, null, tint = Accent)
                }
            }
            Spacer(Modifier.height(10.dp))
            ConfirmButton("Selesai") { onDismiss() }
        }
    }
}
