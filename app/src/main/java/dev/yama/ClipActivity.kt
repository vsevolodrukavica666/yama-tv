package dev.yama

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/** Full-screen music videos: an endless clip wave played by the shared player onto a surface. */
class ClipActivity : Activity() {
    private val p get() = Playback.player
    private var loading = false
    private lateinit var video: SurfaceView
    private lateinit var info: View
    private val hide = Runnable { info.animate().alpha(0f).setDuration(600).start() }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = showInfo()
        override fun onPlaybackStateChanged(state: Int) = spinner()
        override fun onVideoSizeChanged(size: VideoSize) = fit(size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clip)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = findViewById(R.id.video)
        info = findViewById(R.id.info)
        if (Playback.current?.isClip == true) return showInfo()
        bg({ Api.clipsStart().let { it to playable(it.clips) } }, { e -> showError(e); finish() }) { (b, items) ->
            Playback.playClips(this, b, items)
        }
    }

    override fun onStart() {
        super.onStart()
        p.setVideoSurfaceView(video)
        p.addListener(listener)
        // back from the mini player the clip is already playing: no state change will come to hide the spinner
        spinner()
        fit(p.videoSize)
        showInfo()
    }

    override fun onStop() {
        p.removeListener(listener)
        p.clearVideoSurfaceView(video)
        main.removeCallbacks(hide)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> p.seekToNextMediaItem()
            KeyEvent.KEYCODE_DPAD_LEFT -> p.seekToPrevious()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (p.isPlaying) p.pause() else p.play()
            else -> return super.onKeyDown(keyCode, event)
        }
        showInfo()
        return true
    }

    private fun spinner() {
        val busy = p.playbackState == Player.STATE_BUFFERING || Playback.current?.isClip != true
        findViewById<View>(R.id.progress).visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** Keeps the clip's proportions, black bars around it, instead of stretching it over the screen. */
    private fun fit(size: VideoSize) {
        val box = video.parent as View
        if (size.width == 0 || size.height == 0) return
        if (box.width == 0) { box.post { fit(size) }; return }
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        video.layoutParams = (video.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER
            if (box.width.toFloat() / box.height > ratio) { width = (box.height * ratio).toInt(); height = box.height }
            else { width = box.width; height = (box.width / ratio).toInt() }
        }
    }

    private fun showInfo() {
        val t = Playback.current ?: return
        findViewById<TextView>(R.id.clipTitle).text = t.title
        findViewById<TextView>(R.id.clipArtist).text = t.artists
        info.animate().cancel()
        info.alpha = 1f
        main.removeCallbacks(hide)
        if (p.playWhenReady) main.postDelayed(hide, 4000)
        if (p.currentMediaItemIndex >= p.mediaItemCount - 2) more()
    }

    private fun more() {
        val s = Playback.clipSession ?: return
        if (loading) return
        loading = true
        val seen = Playback.clipsSeen.toList()
        bg({ Api.clipsMore(s, seen).let { it.clips to playable(it.clips) } }, { loading = false }) { (clips, items) ->
            loading = false
            if (Playback.current?.isClip != true || Playback.clipSession != s) return@bg
            Playback.clipsSeen += clips
            p.addMediaItems(items)
        }
    }

    /** Runs in the background: one stream lookup per clip, clips without a free stream are dropped. */
    private fun playable(clips: List<Clip>) = clips.mapNotNull { c -> Api.clipUrl(c)?.let { (url, mime) -> Playback.clipItem(c, url, mime) } }
}
