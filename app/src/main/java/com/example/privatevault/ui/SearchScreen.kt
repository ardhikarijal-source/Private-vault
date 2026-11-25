package com.example.privatevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privatevault.data.MediaType
import com.example.privatevault.data.VaultFile
import com.example.privatevault.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seluruh keadaan pencarian disimpan di MainActivity agar tetap ada saat berpindah ke viewer. */
@Stable
class SearchState {
    var query by mutableStateOf("")
    var filter by mutableStateOf<MediaType?>(null)
    var scrollIndex = 0
    var scrollOffset = 0
    var lastViewedPath: String? = null
    var autoFocus = true
    var cachedAll: List<VaultFile> = emptyList()

    fun reset() {
        query = ""; filter = null; scrollIndex = 0; scrollOffset = 0
        lastViewedPath = null; autoFocus = true; cachedAll = emptyList()
    }
}

/** Beberapa kata dicari bersamaan (AND); cocok dengan nama file ATAU nama folder. */
private fun searchMatches(all: List<VaultFile>, query: String, filter: MediaType?): List<VaultFile> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return all.filter { f ->
        (filter == null || f.type == filter) && words.all { w ->
            f.name.lowercase().contains(w) || (f.file.parentFile?.name?.lowercase()?.contains(w) == true)
        }
    }
}

/** Tandai bagian nama yang cocok dengan kata kunci. */
private fun highlight(name: String, query: String): AnnotatedString = buildAnnotatedString {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val lower = name.lowercase()
    val marks = BooleanArray(name.length)
    for (w in words) {
        var i = lower.indexOf(w)
        while (i >= 0) {
            for (k in i until minOf(name.length, i + w.length)) marks[k] = true
            i = lower.indexOf(w, i + w.length)
        }
    }
    var k = 0
    while (k < name.length) {
        var e = k
        while (e < name.length && marks[e] == marks[k]) e++
        if (marks[k]) withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) { append(name.substring(k, e)) }
        else append(name.substring(k, e))
        k = e
    }
}

@Composable
private fun SearchHint(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Card2), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Search, null, tint = Accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    repo: VaultRepository,
    version: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onFile: (VaultFile, List<VaultFile>) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val exportFiles = rememberExportUi(repo, onChanged)

    val all by produceState(state.cachedAll, version) {
        value = withContext(Dispatchers.IO) { repo.allFiles() }
        state.cachedAll = value
    }
    val query = state.query.trim()
    val matchesAll = remember(all, query) { searchMatches(all, query, null) }
    val results = remember(matchesAll, state.filter) {
        val f = state.filter
        if (f == null) matchesAll else matchesAll.filter { it.type == f }
    }
    val counts = remember(matchesAll) { matchesAll.groupingBy { it.type }.eachCount() }

    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.scrollIndex, initialFirstVisibleItemScrollOffset = state.scrollOffset)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> state.scrollIndex = i; state.scrollOffset = o }
    }
    var lastKey by remember { mutableStateOf(state.query to state.filter) }
    LaunchedEffect(state.query, state.filter) {
        val key = state.query to state.filter
        if (key != lastKey) { lastKey = key; gridState.scrollToItem(0) }
    }
    LaunchedEffect(Unit) {
        val path = state.lastViewedPath ?: return@LaunchedEffect
        val index = results.indexOfFirst { it.file.absolutePath == path }
        if (index >= 0) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index !in visible) gridState.scrollToItem(index)
        }
        state.lastViewedPath = null
    }
    LaunchedEffect(Unit) {
        if (state.autoFocus) { state.autoFocus = false; runCatching { focusRequester.requestFocus() } }
    }

    val sel = remember { mutableStateListOf<VaultFile>() }
    var selMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }
    LaunchedEffect(results) { sel.removeAll { f -> results.none { it.file == f.file } } }

    Scaffold(
        containerColor = Bg,
        topBar = {
            if (sel.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${sel.size} dipilih") },
                    navigationIcon = { IconButton({ sel.clear() }) { Icon(Icons.Default.Close, "Batal pilih") } },
                    actions = {
                        IconButton({ sel.clear(); sel.addAll(results) }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                        IconButton({ sel.forEach { repo.setFav(it, true) }; sel.clear(); onChanged() }) { Icon(Icons.Default.Favorite, "Favorit") }
                        Box {
                            IconButton({ selMenu = true }) { Icon(Icons.Default.MoreVert, "Tindakan lain") }
                            DropdownMenu(selMenu, { selMenu = false }) {
                                DropdownMenuItem(text = { Text("Keluarkan file") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                    onClick = { selMenu = false; val chosen = sel.toList(); sel.clear(); exportFiles(chosen) })
                                DropdownMenuItem(text = { Text("Pindahkan ke Sampah", color = Color(0xFFFF6B73)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6B73)) },
                                    onClick = { selMenu = false; confirmDelete = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            } else {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.query, onValueChange = { state.query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            placeholder = { Text("Cari nama file atau folder…") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) IconButton({ state.query = ""; runCatching { focusRequester.requestFocus() } }) {
                                    Icon(Icons.Default.Close, "Hapus pencarian")
                                }
                            },
                            singleLine = true, shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line, focusedContainerColor = Card, unfocusedContainerColor = Card)
                        )
                    },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Kembali") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<MediaType?, String>>(null to "Semua", MediaType.PHOTO to "Foto", MediaType.VIDEO to "Video", MediaType.COMIC to "Komik").forEach { (type, label) ->
                    val n = if (type == null) matchesAll.size else (counts[type] ?: 0)
                    FilterChip(selected = state.filter == type, onClick = { state.filter = type }, label = { Text("$label $n") })
                }
            }
            when {
                query.isEmpty() -> SearchHint("Ketik untuk mencari", "Hasil muncul langsung saat mengetik. Beberapa kata dicari bersamaan, mis. “liburan 2024”.")
                results.isEmpty() -> SearchHint("Tidak ada hasil", "Tidak ada file yang cocok dengan “$query”. Coba kata lain atau ubah filter jenis.")
                else -> {
                    Text("${results.size} hasil", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(
                            state = gridState, columns = GridCells.Adaptive(minSize = 104.dp), modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 10.dp, top = 4.dp, end = 34.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(results, key = { it.file.absolutePath }) { f ->
                                Column {
                                    Thumb(repo, f,
                                        selected = if (sel.isNotEmpty()) sel.contains(f) else null,
                                        onToggle = { if (sel.contains(f)) sel.remove(f) else sel.add(f) },
                                        onLongClick = { if (!sel.contains(f)) sel.add(f) }
                                    ) {
                                        if (sel.isNotEmpty()) {
                                            if (sel.contains(f)) sel.remove(f) else sel.add(f)
                                        } else {
                                            focusManager.clearFocus()
                                            state.scrollIndex = gridState.firstVisibleItemIndex
                                            state.scrollOffset = gridState.firstVisibleItemScrollOffset
                                            state.lastViewedPath = f.file.absolutePath
                                            onFile(f, results)
                                        }
                                    }
                                    Text(highlight(f.name, query), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp))
                                }
                            }
                        }
                        FastScrollbar(gridState, results.size, Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Pindahkan ${sel.size} file ke Sampah?") },
        text = { Text("File bisa dipulihkan dari menu Sampah.") },
        confirmButton = {
            ConfirmButton("Pindahkan", destructive = true) {
                confirmDelete = false
                val chosen = sel.toList()
                sel.clear()
                scope.launch { withContext(Dispatchers.IO) { chosen.forEach { repo.delete(it) } }; onChanged() }
            }
        },
        dismissButton = { CancelButton("Batal") { confirmDelete = false } }
    )
}
