package com.mylibrary.app

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/** Reproduce MP3 en segundo plano (con notificación y controles en pantalla de bloqueo). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() { save(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(applicationContext)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saver)
                if (isPlaying) handler.post(saver) else save()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { save() }
        })
        session = MediaSession.Builder(this, player).setCallback(Callback()).build()
    }

    private fun save() {
        val id = player.currentMediaItem?.mediaId ?: return
        store.savePlayback(id, player.currentPosition)
    }

    /** Solo se aceptan archivos que estén dentro de la carpeta privada de música. */
    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val root = store.musicDir.canonicalPath + File.separator
            val ok = mediaItems.mapNotNull { item ->
                val uri: Uri? = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                val path = uri?.takeIf { it.scheme == "file" }?.path
                if (path != null && File(path).canonicalPath.startsWith(root)) {
                    item.buildUpon().setUri(uri).build()
                } else null
            }.toMutableList()
            return Futures.immediateFuture(ok)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        save()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

fun Track.toItem(store: Store): MediaItem {
    val uri = Uri.fromFile(File(store.musicDir, file))
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
        .build()
}
