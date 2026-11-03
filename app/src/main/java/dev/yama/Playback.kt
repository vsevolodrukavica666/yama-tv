package dev.yama

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** One app-wide player; activities use it directly, the service only exposes it to the system. */
object Playback {
    lateinit var player: ExoPlayer
    private var wave: Api.Batch? = null
    private var waveLoading = false
    private var errors = 0

    val isWave get() = wave != null
    /** What is playing, shown above the title: "Моя волна", a playlist name, ... */
    var source = ""
        private set
    val current: Track? get() = player.currentMediaItem.track

    /** Loudness (RMS, 0..1) of the audio being played; the home screen orb pulses with it. */
    @Volatile var level = 0f
        private set

    private val meter = object : TeeAudioProcessor.AudioBufferSink {
        private var pcm16 = false
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            pcm16 = encoding == C.ENCODING_PCM_16BIT
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!pcm16) return
            val s = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            if (!s.hasRemaining()) return
            var sum = 0.0
            val n = s.remaining()
            while (s.hasRemaining()) { val v = s.get() / 32768.0; sum += v * v }
            level = sqrt(sum / n).toFloat()
        }
    }

    private val MediaItem?.track get() = this?.localConfiguration?.tag as Track?

    fun init(ctx: Context) {
        val files = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx)) { spec ->
            // shortcut: links expire in ~1 min, so every open (seeks too) asks the API again; cache it if that gets slow
            if (spec.uri.scheme == "yama") spec.withUri(Uri.parse(Api.streamUrl(spec.uri.lastPathSegment!!))) else spec
        }
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(meter))).build()
        }
        player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(files))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
    }

    private fun item(t: Track) = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri("yama://track/${t.id}")
        .setTag(t)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artists).setArtworkUri(t.cover(400)?.let(Uri::parse)).build())
        .build()

    fun play(ctx: Context, tracks: List<Track>, index: Int, from: String) {
        wave = null
        source = from
        start(ctx, tracks, index)
    }

    fun playWave(ctx: Context, b: Api.Batch, from: String = "Моя волна") {
        wave = b
        source = from
        background { Api.waveFeedback(b, "radioStarted", null) }
        start(ctx, b.tracks, 0)
    }

    private fun start(ctx: Context, tracks: List<Track>, index: Int) {
        errors = 0
        player.setMediaItems(tracks.map(::item), index, 0)
        player.prepare()
        player.play()
        ctx.startService(Intent(ctx, PlaybackService::class.java))
    }

    fun like(t: Track, on: Boolean) {
        if (on) Api.liked.add(t.id) else Api.liked.remove(t.id)
        val w = wave
        background {
            Api.setLiked(t.id, on)
            if (w != null) Api.waveFeedback(w, if (on) "like" else "unlike", t)
        }
    }

    fun dislike() {
        val t = current ?: return
        val w = wave
        val played = player.currentPosition / 1000.0
        background {
            Api.dislike(t.id)
            if (w != null) Api.waveFeedback(w, "dislike", t, played)
        }
        player.seekToNextMediaItem()
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val w = wave ?: return
            if (reason == Player.DISCONTINUITY_REASON_REMOVE) return // queue replaced, old track belongs to another session
            val prev = old.mediaItem.track ?: return
            if (old.mediaItemIndex == new.mediaItemIndex) return
            val type = if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) "trackFinished" else "skip"
            val played = old.positionMs / 1000.0
            background { Api.waveFeedback(w, type, prev, played) }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val w = wave ?: return
            val t = item.track ?: return
            background { Api.waveFeedback(w, "trackStarted", t) }
            if (waveLoading || player.currentMediaItemIndex < player.mediaItemCount - 2) return
            waveLoading = true
            val queue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track }.takeLast(50)
            io.execute {
                val b = runCatching { Api.waveMore(w.session, queue) }.onFailure { Log.w("YaMa", "wave", it) }.getOrNull()
                main.post {
                    waveLoading = false
                    if (b != null && wave?.session == w.session) {
                        wave = b
                        player.addMediaItems(b.tracks.map(::item))
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w("YaMa", "playback", error)
            // skip broken/unavailable tracks, but don't spin forever on a dead network
            if (++errors < 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errors = 0
        }
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, Playback.player).setSessionActivity(open).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Playback.player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
