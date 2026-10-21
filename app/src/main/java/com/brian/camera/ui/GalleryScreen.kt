package com.brian.camera.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.brian.camera.MainActivity
import com.brian.camera.camera.Saver
import com.brian.camera.ml.Vision
import com.brian.camera.process.Filters
import com.brian.camera.process.Img
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Media(val uri: Uri, val video: Boolean, val name: String, val date: Long, val mime: String)

private val thumbCache = LruCache<Uri, Bitmap>(200)

/** Photos and videos in DCIM/Camera, newest first. */
fun loadMedia(act: MainActivity): List<Media> {
    val out = mutableListOf<Media>()
    fun query(coll: Uri, video: Boolean) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE)
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("${Saver.FOLDER}/%")
        else "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/DCIM/Camera/%")
        runCatching {
            act.contentResolver.query(coll, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Media(ContentUris.withAppendedId(coll, id), video, c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "")
                }
            }
        }
    }
    query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return out.sortedByDescending { it.date }
}

private fun thumb(act: MainActivity, m: Media): Bitmap? {
    thumbCache.get(m.uri)?.let { return it }
    val b = runCatching {
        if (Build.VERSION.SDK_INT >= 29) act.contentResolver.loadThumbnail(m.uri, Size(320, 320), null)
        else if (m.video) null
        else decodeUri(act, m.uri)
    }.getOrNull()
    if (b != null) thumbCache.put(m.uri, b)
    return b
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(act: MainActivity, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<Media>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableIntStateOf(-1) }
    LaunchedEffect(reload) { items = withContext(Dispatchers.IO) { loadMedia(act) } }

    if (open >= 0 && open < items.size) {
        BackHandler { open = -1 }
        Viewer(act, items, open, onClose = { open = -1 }, onChanged = { reload++ })
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Gallery", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ${items.size}", color = Color.Gray, fontSize = 14.sp)
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos yet", color = Color.Gray) }
            return@Column
        }
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().navigationBarsPadding()) {
            items(items.size) { i ->
                val m = items[i]
                val bmp by produceState<Bitmap?>(thumbCache.get(m.uri), m.uri) { value = withContext(Dispatchers.IO) { thumb(act, m) } }
                Box(Modifier.padding(1.dp).aspectRatio(1f).background(Color(0xFF1A1A1A)).clickable { open = i }) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (m.video) Icon(Icons.Filled.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                    if (m.name.startsWith("ST_")) Text("ST", color = Amber, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(act: MainActivity, items: List<Media>, start: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    val scope = rememberCoroutineScope()
    var lensText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { onChanged(); onClose() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val m = items[page]
            if (m.video) {
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(m.uri)
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = false }
                    }
                }, modifier = Modifier.fillMaxSize(), update = { v -> if (pager.currentPage != page) v.pause() })
            } else {
                val bmp by produceState<Bitmap?>(null, m.uri) { value = withContext(Dispatchers.IO) { runCatching { decodeUri(act, m.uri) }.getOrNull() } }
                var scale by remember { mutableFloatStateOf(1f) }
                var ox by remember { mutableFloatStateOf(0f) }
                var oy by remember { mutableFloatStateOf(0f) }
                bmp?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                                }
                            }
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        val cur = items.getOrNull(pager.currentPage)
        Row(Modifier.statusBarsPadding().fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(cur?.name ?: "", color = Color.White, fontSize = 13.sp, maxLines = 1)
        }

        if (cur != null) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding()
                .horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Chip("Share", false) { scope.launch { share(act, cur) } }
            Chip("Delete", false) {
                runCatching { act.contentResolver.delete(cur.uri, null, null) }
                    .onSuccess { onChanged(); onClose() }
                    .onFailure {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val pi = MediaStore.createDeleteRequest(act.contentResolver, listOf(cur.uri))
                            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    }
            }
            if (!cur.video) {
                Chip("Text", false) {
                    scope.launch {
                        val t = withContext(Dispatchers.Default) { runCatching { Vision.read(decodeUri(act, cur.uri)).joinToString("\n\n") { it.text } }.getOrDefault("") }
                        lensText = (t.ifBlank { "No text found" }) to "Text"
                    }
                }
                Chip("Identify", false) {
                    scope.launch {
                        val t = withContext(Dispatchers.Default) {
                            runCatching { Vision.labels(decodeUri(act, cur.uri)).joinToString("\n") { "${it.text}  ${(it.confidence * 100).toInt()}%" } }.getOrDefault("")
                        }
                        lensText = (t.ifBlank { "Nothing recognised" }) to "Identify"
                    }
                }
                Chip("QR", false) {
                    scope.launch {
                        val t = withContext(Dispatchers.Default) { runCatching { Vision.codes(decodeUri(act, cur.uri)).joinToString("\n") { it.text } }.getOrDefault("") }
                        lensText = (t.ifBlank { "No code found" }) to "QR"
                    }
                }
                Filters.names.drop(1).take(4).forEach { f ->
                    Chip(f, false) {
                        scope.launch(Dispatchers.Default) {
                            val src = runCatching { decodeUri(act, cur.uri) }.getOrNull() ?: return@launch
                            val m = Filters.matrix(f) ?: return@launch
                            Saver.saveBitmap(act, Img.applyMatrix(src, m), "EDIT", false, null)
                            withContext(Dispatchers.Main) { android.widget.Toast.makeText(act, "Saved a $f copy", android.widget.Toast.LENGTH_SHORT).show(); onChanged() }
                        }
                    }
                }
                Chip("Info", false) { info = exifSummary(act, cur) }
            }
        }

        lensText?.let { (t, tool) -> LensResultSheet(act, t, tool) { lensText = null } }
        info?.let { txt ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { info = null }, contentAlignment = Alignment.Center) {
                Text(txt, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

private fun exifSummary(act: MainActivity, m: Media): String = runCatching {
    act.contentResolver.openInputStream(m.uri)!!.use { s ->
        val ex = ExifInterface(s)
        buildString {
            appendLine(m.name)
            ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { appendLine("Taken: $it") }
            appendLine("Size: ${ex.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: "?"} × ${ex.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: "?"}")
            ex.getAttribute(ExifInterface.TAG_MODEL)?.let { appendLine("Camera: $it") }
            ex.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { appendLine("ISO: $it") }
            ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { appendLine("Shutter: 1/${Math.round(1 / it)}") }
            ex.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { appendLine("Aperture: f/$it") }
            ex.latLong?.let { appendLine("Location: %.5f, %.5f".format(it[0], it[1])) } ?: appendLine("Location: none")
        }
    }
}.getOrDefault(m.name)

/** Shares a photo or video; photos lose their GPS tags first when that setting is on. */
private suspend fun share(act: MainActivity, m: Media) {
    val uri = withContext(Dispatchers.IO) {
        if (m.video || !act.prefs.stripLocationOnShare || !m.mime.contains("jpeg")) return@withContext m.uri
        runCatching {
            val dir = File(act.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, m.name)
            act.contentResolver.openInputStream(m.uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            val ex = ExifInterface(f)
            listOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD
            ).forEach { ex.setAttribute(it, null) }
            ex.saveAttributes()
            FileProvider.getUriForFile(act, "com.brian.camera.files", f)
        }.getOrDefault(m.uri)
    }
    val i = Intent(Intent.ACTION_SEND).setType(if (m.video) "video/*" else "image/*")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    act.startActivity(Intent.createChooser(i, "Share"))
}
