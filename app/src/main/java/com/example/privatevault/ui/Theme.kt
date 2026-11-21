package com.example.privatevault.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0A111F); val Card = Color(0xFF131C33); val Card2 = Color(0xFF1A2644)
val Line = Color(0xFF243153); val Accent = Color(0xFF2F7BFF); val Muted = Color(0xFF8D9BBD)

/** Tandai bahwa aplikasi sengaja keluar sebentar (mis. membuka pemilih file) agar tidak ikut terkunci saat kembali. */
object ExternalFlow { @Volatile var active = false }

@Composable
fun VaultTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Color.White,
        secondaryContainer = Color(0xFF1F3A6E), onSecondaryContainer = Color(0xFFEAF0FF),
        background = Bg, surface = Card,
        onBackground = Color(0xFFEAF0FF), onSurface = Color(0xFFEAF0FF),
        surfaceVariant = Card2, onSurfaceVariant = Muted,
        outline = Line, outlineVariant = Line,
        surfaceContainer = Card, surfaceContainerHigh = Card2, surfaceContainerHighest = Card2),
    content = content)
