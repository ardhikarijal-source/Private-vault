package com.example.privatevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
 

/**
 * Slider scroll cepat di sisi kanan. Berupa overlay di atas gambar (tidak menggeser grid): meluncur masuk dari
 * kanan ke kiri hanya saat daftar digulir atau slider diseret, lalu meluncur keluar saat berhenti.
 * Hanya tampil untuk daftar ≥ 15 item.
 */
@Composable
fun FastScrollbar(
    state: LazyGridState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    label: (Int) -> String = { "${it + 1} / $totalItems" }
) {
    if (totalItems < 15) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(false) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastTarget by remember { mutableIntStateOf(-1) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) awake = true else { delay(1000); awake = false }
    }
    val visible = awake || dragging
    val trackWidth by animateDpAsState(if (dragging) 48.dp else 36.dp, tween(150), label = "trackWidth")
    val thumbWidth by animateDpAsState(if (dragging) 14.dp else 8.dp, tween(150), label = "thumbWidth")
    // Posisi geser: 0 = terlihat penuh, trackWidth+12dp = tersembunyi di luar tepi kanan layar.
    val slideX by animateDpAsState(if (visible) 0.dp else trackWidth + 12.dp, tween(240), label = "slideX")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "alpha")
    val minThumbPx = with(density) { 72.dp.toPx() }

    val visibleCount = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val maxFirst = (totalItems - visibleCount).coerceAtLeast(1)
    val naturalFraction = (state.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
    val fraction = if (dragging) dragFraction else naturalFraction
    val thumbPx = minThumbPx.coerceAtMost(heightPx.coerceAtLeast(minThumbPx))
    val thumbTop = ((heightPx - thumbPx) * fraction).coerceAtLeast(0f)
    val currentIndex = (fraction * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)

    // Sudah benar-benar hilang: lepas dari komposisi agar tidak menangkap sentuhan sama sekali.
    if (!visible && alpha == 0f) return

    Box(
        modifier.fillMaxHeight().width(trackWidth)
            .offset { IntOffset(slideX.roundToPx(), 0) }
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(totalItems) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val h = size.height.toFloat()
                    fun jump(y: Float) {
                        val usable = (h - thumbPx).coerceAtLeast(1f)
                        val f = ((y - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                        dragFraction = f
                        val target = (f * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                        if (target != lastTarget) {
                            lastTarget = target
                            scope.launch { state.scrollToItem(target) }
                        }
                    }
                    dragging = true
                    jump(down.position.y)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change -> if (change.pressed) { jump(change.position.y); change.consume() } }
                    } while (event.changes.any { it.pressed })
                    dragging = false
                    lastTarget = -1
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize().alpha(alpha)) {
            val tw = thumbWidth.toPx()
            val cx = size.width - 14.dp.toPx()
            drawRoundRect(Color(0x55000000), Offset(cx - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
            drawRoundRect(Color(0x66000000), Offset(cx - tw / 2f - 2.dp.toPx(), thumbTop - 2.dp.toPx()), Size(tw + 4.dp.toPx(), thumbPx + 4.dp.toPx()), CornerRadius((tw + 4.dp.toPx()) / 2f))
            drawRoundRect(if (dragging) Accent else Color(0xE68FB4FF), Offset(cx - tw / 2f, thumbTop), Size(tw, thumbPx), CornerRadius(tw / 2f))
        }
        if (dragging) Text(
            label(currentIndex), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-(trackWidth.roundToPx() + 8.dp.roundToPx()), (thumbTop + thumbPx / 2f - 18.dp.toPx()).roundToInt()) }
                .wrapContentWidth(Alignment.End, unbounded = true)
                .background(Color(0xEE1A2644), RoundedCornerShape(12.dp))
                .border(1.dp, Accent, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
