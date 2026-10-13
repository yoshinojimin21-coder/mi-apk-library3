package com.mylibrary.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Importa archivos copiándolos al almacenamiento privado, validando su contenido (no solo la extensión). */
object Importer {
    private const val MAX_BYTES = 500L * 1024 * 1024

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun copy(ctx: Context, uri: Uri, dest: File): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return false
                        o.write(buf, 0, n)
                    }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    fun importBook(ctx: Context, store: Store, uri: Uri): Book? {
        val id = UUID.randomUUID().toString()
        val dest = File(store.booksDir, "$id.epub")
        if (!copy(ctx, uri, dest)) { dest.delete(); return null }
        val info = Epub.parse(dest)
        if (info == null || info.spine.isEmpty()) { dest.delete(); return null }
        val name = displayName(ctx, uri)?.substringBeforeLast('.') ?: "Libro"
        return Book(id, info.title.ifBlank { name }, info.author, dest.name)
    }

    private fun looksLikeMp3(f: File): Boolean = try {
        f.inputStream().use { s ->
            val h = ByteArray(3)
            if (s.read(h) < 3) false
            else {
                val id3 = h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()
                val sync = (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0
                id3 || sync
            }
        }
    } catch (e: Exception) { false }

    fun importTrack(ctx: Context, store: Store, uri: Uri): Track? {
        val id = UUID.randomUUID().toString()
        val dest = File(store.musicDir, "$id.mp3")
        if (!copy(ctx, uri, dest) || !looksLikeMp3(dest)) { dest.delete(); return null }
        var title: String? = null
        var artist: String? = null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(dest.path)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
        val fallback = displayName(ctx, uri)?.substringBeforeLast('.') ?: "Canción"
        return Track(id, title?.takeIf { it.isNotBlank() } ?: fallback, artist?.takeIf { it.isNotBlank() } ?: "Artista desconocido", dest.name)
    }
}
