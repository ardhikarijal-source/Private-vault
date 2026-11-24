package com.example.privatevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Danger = Color(0xFFD64550)
private val DangerText = Color(0xFFFF6B73)

@Composable
fun ConfirmButton(text: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
                 else ButtonDefaults.buttonColors()
    ) { Text(text) }
}

@Composable
fun CancelButton(text: String = "Batal", enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Text(text) }
}

@Composable
fun ActionRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (destructive) DangerText else Accent)
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (destructive) DangerText else Color.White)
    }
}

@Composable
fun ProgressChip(text: String, progress: Float?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = Card2, tonalElevation = 6.dp, shadowElevation = 8.dp) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (progress != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                else CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                Text(text, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
