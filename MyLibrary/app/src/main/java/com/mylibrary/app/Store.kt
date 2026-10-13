package com.mylibrary.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Book(val id: String, val title: String, val author: String, val file: String)
data class Track(val id: String, val title: String, val artist: String, val file: String)

/** Estado persistente. Todo vive en el almacenamiento privado de la app (sandbox de Android). */
class Store(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("my_library_state", Context.MODE_PRIVATE)
    val booksDir: File = File(ctx.filesDir, "books").apply { mkdirs() }
    val musicDir: File = File(ctx.filesDir, "music").apply { mkdirs() }

    /** "books" o "music": el modo en el que se quedó la app. */
    var mode: String
        get() = prefs.getString("mode", "books") ?: "books"
        set(v) { prefs.edit().putString("mode", v).apply() }

    /** -1 = seguir al sistema, 0 = claro, 1 = oscuro. */
    var darkPref: Int
        get() = prefs.getInt("dark", -1)
        set(v) { prefs.edit().putInt("dark", v).apply() }

    var fontPct: Int
        get() = prefs.getInt("font_pct", 110)
        set(v) { prefs.edit().putInt("font_pct", v).apply() }

    /** Libro abierto al cerrar la app (null si estaba en la biblioteca). */
    var openBookId: String?
        get() = prefs.getString("open_book", null)
        set(v) { prefs.edit().putString("open_book", v).apply() }

    fun getPosition(bookId: String): Pair<Int, Float> =
        prefs.getInt("ch_$bookId", 0) to prefs.getFloat("fr_$bookId", 0f)

    fun savePosition(bookId: String, chapter: Int, fraction: Float) {
        prefs.edit().putInt("ch_$bookId", chapter).putFloat("fr_$bookId", fraction).apply()
    }

    fun forgetBook(bookId: String) {
        prefs.edit().remove("ch_$bookId").remove("fr_$bookId").apply()
    }

    var playId: String?
        get() = prefs.getString("play_id", null)
        set(v) { prefs.edit().putString("play_id", v).apply() }

    var playPos: Long
        get() = prefs.getLong("play_pos", 0L)
        set(v) { prefs.edit().putLong("play_pos", v).apply() }

    fun savePlayback(id: String, pos: Long) {
        prefs.edit().putString("play_id", id).putLong("play_pos", pos).apply()
    }

    private fun safeName(n: String) = n.isNotEmpty() && !n.contains('/') && !n.contains('\\') && !n.contains("..")

    fun loadBooks(): List<Book> = try {
        val arr = JSONArray(prefs.getString("books", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Book(o.getString("id"), o.getString("title"), o.optString("author"), o.getString("file"))
        }.filter { safeName(it.file) && File(booksDir, it.file).exists() }
    } catch (e: Exception) { emptyList() }

    fun saveBooks(list: List<Book>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).put("file", it.file))
        }
        prefs.edit().putString("books", arr.toString()).apply()
    }

    fun loadTracks(): List<Track> = try {
        val arr = JSONArray(prefs.getString("tracks", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Track(o.getString("id"), o.getString("title"), o.optString("artist"), o.getString("file"))
        }.filter { safeName(it.file) && File(musicDir, it.file).exists() }
    } catch (e: Exception) { emptyList() }

    fun saveTracks(list: List<Track>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("file", it.file))
        }
        prefs.edit().putString("tracks", arr.toString()).apply()
    }
}
