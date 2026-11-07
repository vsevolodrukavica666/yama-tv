package dev.yama

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

class PlayerActivity : Activity() {
    private val p get() = Playback.player
    private lateinit var cover: ImageView
    private lateinit var backdrop: ImageView
    private lateinit var source: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var bar: ProgressBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var hint: TextView
    private lateinit var play: ImageButton
    private lateinit var like: ImageButton
    private lateinit var dislike: ImageButton
    private var coverUrl: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "Не играет: " + (error.cause?.message ?: error.errorCodeName), Toast.LENGTH_LONG).show()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            progress()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cover = findViewById(R.id.cover)
        backdrop = findViewById(R.id.backdrop)
        source = findViewById(R.id.source)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        bar = findViewById(R.id.bar)
        pos = findViewById(R.id.pos)
        dur = findViewById(R.id.dur)
        hint = findViewById(R.id.hint)
        play = findViewById(R.id.play)
        like = findViewById(R.id.like)
        dislike = findViewById(R.id.dislike)
        cover.rounded()

        play.setOnClickListener {
            if (p.playWhenReady) p.pause() else {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
        }
        findViewById<View>(R.id.prev).setOnClickListener { p.seekToPrevious() }
        findViewById<View>(R.id.next).setOnClickListener { p.seekToNextMediaItem() }
        like.setOnClickListener {
            val t = Playback.current ?: return@setOnClickListener
            Playback.like(t, t.id !in Api.liked)
            update()
        }
        dislike.setOnClickListener { Playback.dislike() }
        val trackWave = findViewById<View>(R.id.trackWave)
        trackWave.setOnClickListener {
            val t = Playback.current ?: return@setOnClickListener
            trackWave.isEnabled = false
            bg({ Api.waveStart("track:${t.id}") }, { e -> trackWave.isEnabled = true; showError(e) }) { b ->
                trackWave.isEnabled = true
                Playback.playWave(this, b, "Волна по треку «${t.title}»")
            }
        }
        for (id in intArrayOf(R.id.dislike, R.id.prev, R.id.play, R.id.next, R.id.like, R.id.trackWave)) {
            findViewById<View>(id).run {
                setOnFocusChangeListener { v, has -> if (has) hint.text = v.contentDescription }
                zoomOnFocus(1.12f)
            }
        }
        play.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        p.addListener(listener)
        update()
        main.post(tick)
    }

    override fun onStop() {
        p.removeListener(listener)
        main.removeCallbacks(tick)
        super.onStop()
    }

    private fun update() {
        val t = Playback.current
        source.text = Playback.source
        title.text = t?.title ?: "Ничего не играет"
        artist.text = t?.artists.orEmpty()
        play.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        val liked = t != null && t.id in Api.liked
        like.setImageResource(if (liked) R.drawable.ic_like else R.drawable.ic_like_off)
        like.imageTintList = ColorStateList.valueOf(getColor(if (liked) R.color.focus else android.R.color.white))
        dislike.visibility = if (Playback.isWave) View.VISIBLE else View.GONE
        // radio and clips are not catalog tracks: nothing to like or build a wave from; live radio can't skip either
        val own = if (t?.isRadio == true || t?.isClip == true) View.GONE else View.VISIBLE
        for (id in intArrayOf(R.id.like, R.id.trackWave)) findViewById<View>(id).visibility = own
        for (id in intArrayOf(R.id.prev, R.id.next)) findViewById<View>(id).visibility = if (t?.isRadio == true) View.GONE else View.VISIBLE
        val url = t?.cover(600)
        if (url != coverUrl) {
            coverUrl = url
            cover.setImageResource(R.drawable.ic_note)
            backdrop.setImageDrawable(null)
            Covers.load(url) { bmp ->
                if (coverUrl != url) return@load
                cover.setImageBitmap(bmp)
                backdrop.setImageBitmap(blurred(bmp))
            }
        }
        progress()
    }

    private fun progress() {
        val live = Playback.current?.isRadio == true
        // a live stream's "duration" is just its buffer window, so show no progress at all
        val d = if (live) 0 else p.duration.takeIf { it > 0 } ?: Playback.current?.durationMs ?: 0
        val at = p.currentPosition
        bar.progress = if (d > 0) (at * 1000 / d).toInt() else 0
        pos.text = if (live) "" else fmt(at)
        dur.text = if (live) "Эфир" else fmt(d)
    }
}
