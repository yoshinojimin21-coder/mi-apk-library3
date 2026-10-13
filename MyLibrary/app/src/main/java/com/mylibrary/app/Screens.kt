package com.mylibrary.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable
fun AppRoot(store: Store) {
    val ctx = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    var darkPref by remember { mutableIntStateOf(store.darkPref) }
    val dark = if (darkPref == -1) systemDark else darkPref == 1
    val aqua = if (dark) DarkAqua else LightAqua
    var mode by remember { mutableStateOf(store.mode) }

    val books = remember { mutableStateListOf<Book>().apply { addAll(store.loadBooks()) } }
    val tracks = remember { mutableStateListOf<Track>().apply { addAll(store.loadTracks()) } }
    var openBook by remember { mutableStateOf(store.openBookId?.let { id -> books.firstOrNull { it.id == id } }) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    val toggleTheme: () -> Unit = { darkPref = if (dark) 0 else 1; store.darkPref = darkPref }

    // Iconos de la barra de estado legibles según el tema elegido
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let {
            val c = WindowCompat.getInsetsController(it, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    // Conexión con el servicio de reproducción
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try { controller = future.get() } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(future); controller = null }
    }

    // Restaurar la cola y la posición de la última canción (sin reproducir sola)
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        if (c.mediaItemCount == 0 && tracks.isNotEmpty()) {
            val items = tracks.map { it.toItem(store) }
            val idx = tracks.indexOfFirst { it.id == store.playId }.let { if (it < 0) 0 else it }
            val pos = if (tracks.getOrNull(idx)?.id == store.playId) store.playPos else 0L
            c.setMediaItems(items, idx, pos)
            c.prepare()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotif: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    CompositionLocalProvider(LocalAqua provides aqua) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            val ob = openBook
            val closeBook = { openBook = null; store.openBookId = null }
            BackHandler(enabled = ob != null) { closeBook() }

            if (ob != null) {
                ReaderScreen(ob, store, dark, toggleTheme) { closeBook() }
            } else {
                Column(Modifier.fillMaxSize().pinstripes(aqua)) {
                    // Barra superior metálica estilo Aqua
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(aqua.barTop, aqua.barBottom)))
                    ) {
                        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp)) {
                            GlossButton(
                                onClick = { mode = if (mode == "books") "music" else "books"; store.mode = mode },
                                modifier = Modifier.align(Alignment.CenterStart)
                            ) {
                                Icon(
                                    if (mode == "books") Icons.Filled.MusicNote else Icons.AutoMirrored.Filled.MenuBook,
                                    null, tint = Color.White, modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (mode == "books") "Música" else "Libros",
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    style = TextStyle(shadow = Shadow(Color(0x66000000), Offset(0f, 1f), 1f))
                                )
                            }
                            Text(
                                "My library",
                                modifier = Modifier.align(Alignment.Center),
                                color = aqua.text, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                style = TextStyle(
                                    shadow = Shadow(if (dark) Color.Black else Color.White, Offset(0f, 1.5f), 0f)
                                )
                            )
                            GlossButton(
                                onClick = toggleTheme, hPad = 0.dp,
                                modifier = Modifier.align(Alignment.CenterEnd).width(38.dp)
                            ) {
                                Icon(
                                    if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    null, tint = Color.White, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(aqua.border))
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (mode == "books") {
                            BooksScreen(store, books) { b -> store.openBookId = b.id; openBook = b }
                        } else {
                            MusicScreen(store, tracks, controller, askNotif)
                        }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
private fun AquaRow(index: Int, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val a = LocalAqua.current
    Row(
        modifier.fillMaxWidth()
            .background(if (index % 2 == 0) a.rowA else a.rowB)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun AddBar(label: String, busy: Boolean, onClick: () -> Unit) {
    val a = LocalAqua.current
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(a.barTop, a.barBottom)))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        GlossButton(onClick = { if (!busy) onClick() }) {
            Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (busy) "Importando…" else label, color = Color.White, fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color(0x66000000), Offset(0f, 1f), 1f))
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    val a = LocalAqua.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = a.sub, textAlign = TextAlign.Center, fontSize = 15.sp)
    }
}

/* ───────────────────────────── LIBROS ───────────────────────────── */

@Composable
fun BooksScreen(store: Store, books: SnapshotStateList<Book>, onOpen: (Book) -> Unit) {
    val a = LocalAqua.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            var failed = 0
            for (u in uris) {
                val b = withContext(Dispatchers.IO) { Importer.importBook(ctx, store, u) }
                if (b == null) failed++ else books.add(b)
            }
            store.saveBooks(books.toList())
            busy = false
            if (failed > 0) msg = "$failed archivo(s) no se pudieron importar (no son EPUB válidos)."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (books.isEmpty()) {
                EmptyState("Tu biblioteca está vacía.\nPulsa «Añadir libros» para importar archivos .epub.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
                        val pos = store.getPosition(b.id)
                        AquaRow(i, Modifier.clickable { onOpen(b) }) {
                            Box(
                                Modifier.size(width = 42.dp, height = 58.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.hsv((abs(b.id.hashCode()) % 360).toFloat(), 0.45f, 0.95f),
                                                Color.hsv((abs(b.id.hashCode()) % 360).toFloat(), 0.75f, 0.65f)
                                            )
                                        )
                                    )
                                    .border(1.dp, a.border, RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    b.title.firstOrNull()?.uppercase() ?: "?",
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, color = a.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (b.author.isNotBlank()) Text(b.author, color = a.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (pos.first > 0 || pos.second > 0f) "Continuar · capítulo ${pos.first + 1}" else "Sin empezar",
                                    color = a.sub, fontSize = 12.sp
                                )
                            }
                            IconButton({ toDelete = b }) { Icon(Icons.Filled.Delete, "Eliminar", tint = a.sub) }
                        }
                    }
                }
            }
        }
        AddBar("Añadir libros", busy) {
            picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        }
    }

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminar libro") },
            text = { Text("¿Quitar «${b.title}» de la biblioteca? Se borrará su copia dentro de la app.") },
            confirmButton = {
                TextButton({
                    File(store.booksDir, b.file).delete()
                    store.forgetBook(b.id)
                    if (store.openBookId == b.id) store.openBookId = null
                    books.remove(b)
                    store.saveBooks(books.toList())
                    toDelete = null
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancelar") } }
        )
    }
    msg?.let {
        AlertDialog(onDismissRequest = { msg = null }, confirmButton = { TextButton({ msg = null }) { Text("OK") } }, text = { Text(it) })
    }
}

/* ───────────────────────────── MÚSICA ───────────────────────────── */

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun playTrack(c: MediaController, store: Store, tracks: List<Track>, t: Track) {
    val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
    if (ids != tracks.map { it.id }) c.setMediaItems(tracks.map { it.toItem(store) })
    val idx = tracks.indexOfFirst { it.id == t.id }
    if (idx < 0) return
    c.seekTo(idx, 0L)
    c.prepare()
    c.play()
}

@Composable
fun MusicScreen(store: Store, tracks: SnapshotStateList<Track>, controller: MediaController?, askNotif: () -> Unit) {
    val a = LocalAqua.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                pos = it.currentPosition
                dur = it.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L
                playing = it.isPlaying
                curId = it.currentMediaItem?.mediaId
            }
            delay(400)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            var failed = 0
            for (u in uris) {
                val t = withContext(Dispatchers.IO) { Importer.importTrack(ctx, store, u) }
                if (t == null) failed++ else {
                    tracks.add(t)
                    controller?.addMediaItem(t.toItem(store))
                }
            }
            store.saveTracks(tracks.toList())
            busy = false
            if (failed > 0) msg = "$failed archivo(s) no se pudieron importar (no son MP3 válidos)."
        }
    }

    val cur = tracks.firstOrNull { it.id == curId }

    Column(Modifier.fillMaxSize()) {
        // Pantalla LCD estilo iPod
        Column(
            Modifier.fillMaxWidth().padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(a.lcdTop, a.lcdBottom)))
                .border(1.dp, a.border, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Text("♪  REPRODUCIENDO", color = a.lcdText.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                cur?.title ?: if (tracks.isEmpty()) "Sin música" else "Elige una canción",
                color = a.lcdText, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(cur?.artist ?: " ", color = a.lcdText.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Slider(
                value = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { controller?.seekTo((it * dur).toLong()) }
                    drag = null
                },
                enabled = controller != null && dur > 0,
                colors = SliderDefaults.colors(
                    thumbColor = a.btnBottom, activeTrackColor = a.btnTop,
                    inactiveTrackColor = a.lcdText.copy(alpha = 0.2f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = drag?.let { (it * dur).toLong() } ?: pos
                Text(fmt(shown), color = a.lcdText, fontSize = 12.sp)
                Text("-" + fmt(dur - shown), color = a.lcdText, fontSize = 12.sp)
            }
        }

        // Controles
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlossButton({ controller?.seekToPreviousMediaItem() }, hPad = 0.dp, height = 44.dp, modifier = Modifier.width(60.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Anterior", tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            GlossButton({
                val c = controller ?: return@GlossButton
                if (c.isPlaying) c.pause()
                else {
                    askNotif()
                    if (c.mediaItemCount == 0 && tracks.isNotEmpty()) playTrack(c, store, tracks, tracks.first())
                    else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
                }
            }, hPad = 0.dp, height = 58.dp, modifier = Modifier.width(78.dp)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Reproducir/Pausa", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(14.dp))
            GlossButton({ controller?.seekToNextMediaItem() }, hPad = 0.dp, height = 44.dp, modifier = Modifier.width(60.dp)) {
                Icon(Icons.Filled.SkipNext, "Siguiente", tint = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(a.border))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tracks.isEmpty()) {
                EmptyState("No hay canciones todavía.\nPulsa «Añadir MP3» para importar música. Funciona sin conexión.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                        val isCur = t.id == curId
                        AquaRow(i, Modifier.clickable {
                            val c = controller ?: return@clickable
                            askNotif()
                            if (isCur) { if (c.isPlaying) c.pause() else c.play() }
                            else playTrack(c, store, tracks, t)
                        }) {
                            Text(
                                if (isCur && playing) "▶" else "♪",
                                color = if (isCur) a.btnBottom else a.sub, fontSize = 16.sp,
                                modifier = Modifier.width(26.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title, color = if (isCur) a.btnBottom else a.text,
                                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(t.artist, color = a.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ toDelete = t }) { Icon(Icons.Filled.Delete, "Eliminar", tint = a.sub) }
                        }
                    }
                }
            }
        }
        AddBar("Añadir MP3", busy) { picker.launch(arrayOf("audio/*")) }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminar canción") },
            text = { Text("¿Quitar «${t.title}» de la biblioteca? Se borrará su copia dentro de la app.") },
            confirmButton = {
                TextButton({
                    controller?.let { c ->
                        for (i in 0 until c.mediaItemCount) {
                            if (c.getMediaItemAt(i).mediaId == t.id) { c.removeMediaItem(i); break }
                        }
                    }
                    File(store.musicDir, t.file).delete()
                    tracks.remove(t)
                    store.saveTracks(tracks.toList())
                    toDelete = null
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancelar") } }
        )
    }
    msg?.let {
        AlertDialog(onDismissRequest = { msg = null }, confirmButton = { TextButton({ msg = null }) { Text("OK") } }, text = { Text(it) })
    }
}
