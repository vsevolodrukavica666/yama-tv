package dev.yama

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player

class MainActivity : Activity() {
    private lateinit var now: View
    private lateinit var ambient: ImageView
    private var ambientBmp: Bitmap? = null
    private val qualities = arrayOf("nq", "hq", "lossless")
    private val qualityNames = arrayOf("Экономно (AAC)", "Высокое (MP3 320)", "Lossless (FLAC)")

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateNow()
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = Playback.player
            val d = if (Playback.current?.isRadio == true) 0 else p.duration.takeIf { it > 0 } ?: 0
            findViewById<ProgressBar>(R.id.nowBar).progress = if (d > 0) (p.currentPosition * 1000 / d).toInt() else 0
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Api.token == null || Api.uid.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        now = findViewById(R.id.now)
        ambient = findViewById(R.id.ambient)

        val waveBtn = findViewById<View>(R.id.waveBtn)
        waveBtn.setOnClickListener { startWave(waveBtn, "user:onyourwave", "Моя волна") }
        waveBtn.setOnFocusChangeListener { _, has -> if (has) setAmbient(null) }
        waveBtn.zoomOnFocus()

        findViewById<View>(R.id.navHome).apply {
            isSelected = true
            setOnClickListener { findViewById<ScrollView>(R.id.scroll).smoothScrollTo(0, 0); waveBtn.requestFocus() }
        }
        findViewById<View>(R.id.navLikes).setOnClickListener { openList("likes") }
        findViewById<View>(R.id.navPlaylists).setOnClickListener { openList("playlists") }
        findViewById<View>(R.id.navSettings).setOnClickListener { settings() }
        findViewById<View>(R.id.searchBtn).apply { setOnClickListener { openList("search") }; zoomOnFocus() }
        findViewById<View>(R.id.clipsBtn).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ClipActivity::class.java)) }
            setOnFocusChangeListener { _, has -> if (has) setAmbient(null) }
            zoomOnFocus()
        }
        now.setOnClickListener { openPlayer() }
        now.zoomOnFocus(1.03f)
        findViewById<View>(R.id.nowCover).rounded()
        waveBtn.requestFocus()

        bg({ Api.likedIds().take(30).let { if (it.isEmpty()) emptyList() else Api.tracks(it) } }) { showLikes(it) }
        bg({ Api.waves() }, {}) { showWaves(it) }
        bg({ Api.radioGenres() }, {}) { showRadio(it) }
        bg({ Api.playlists() }, {}) { showPlaylists(it) }
    }

    override fun onStart() {
        super.onStart()
        if (!::now.isInitialized) return
        Playback.player.addListener(listener)
        updateNow()
        main.post(tick)
    }

    override fun onStop() {
        if (::now.isInitialized) {
            Playback.player.removeListener(listener)
            main.removeCallbacks(tick)
        }
        super.onStop()
    }

    private fun updateNow() {
        val t = Playback.current
        now.visibility = if (t != null) View.VISIBLE else View.GONE
        if (t == null) return
        findViewById<TextView>(R.id.nowTitle).text = t.title
        findViewById<TextView>(R.id.nowArtist).text = t.artists
        findViewById<ImageView>(R.id.nowState).setImageResource(if (Playback.player.isPlaying) R.drawable.ic_eq else R.drawable.ic_pause)
        val cover = findViewById<ImageView>(R.id.nowCover)
        val url = t.cover(200)
        if (cover.tag != url) Covers.into(cover, url)
    }

    /** The whole screen takes the colors of the focused cover; null brings back the default art. */
    private fun setAmbient(bmp: Bitmap?) {
        if (bmp === ambientBmp) return
        ambientBmp = bmp
        ambient.animate().alpha(0.2f).setDuration(120).withEndAction {
            if (bmp == null) ambient.setImageResource(R.drawable.art_bg) else ambient.setImageBitmap(blurred(bmp))
            // pale covers would wash the screen out to grey, so dim them; the art is dark already
            if (bmp == null) ambient.clearColorFilter() else ambient.setColorFilter(0xFF8C8C8C.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
            ambient.animate().alpha(1f).setDuration(350).start()
        }.start()
    }

    private fun card(row: LinearLayout, w: Int, h: Int, inside: String? = null, below: String? = null, click: (View) -> Unit): ImageView {
        val v = layoutInflater.inflate(R.layout.item_card, row, false)
        val dp = resources.displayMetrics.density
        val frame = v.findViewById<View>(R.id.frame)
        frame.layoutParams.width = (w * dp).toInt()
        frame.layoutParams.height = (h * dp).toInt()
        inside?.let { v.findViewById<TextView>(R.id.inside).apply { text = it; visibility = View.VISIBLE } }
        below?.let {
            v.findViewById<TextView>(R.id.below).apply { text = it; visibility = View.VISIBLE; layoutParams.width = (w * dp).toInt() }
        }
        val art = v.findViewById<ImageView>(R.id.img)
        frame.rounded()
        frame.setOnClickListener(click)
        frame.setOnFocusChangeListener { _, has -> if (has) setAmbient((art.drawable as? BitmapDrawable)?.bitmap) }
        frame.zoomOnFocus()
        row.addView(v)
        return art
    }

    // shortcut: the rail plays only the first 30 likes; "Все треки" opens the full list
    private fun showLikes(tracks: List<Track>) {
        val row = findViewById<LinearLayout>(R.id.likesRow)
        findViewById<TextView>(R.id.likesTitle).text = "Мне нравится · ${Api.liked.size}"
        tracks.forEachIndexed { i, t ->
            val art = card(row, 150, 150, below = t.title) {
                Playback.play(this, tracks, i, "Мне нравится")
                openPlayer()
            }
            Covers.into(art, t.cover(300), 0)
        }
        card(row, 150, 150, inside = "Все\nтреки") { openList("likes") }
    }

    private fun showWaves(groups: List<WaveGroup>) {
        val box = findViewById<LinearLayout>(R.id.waves)
        for (g in groups) {
            val rail = layoutInflater.inflate(R.layout.rail, box, false)
            rail.findViewById<TextView>(R.id.railTitle).text = g.title
            val row = rail.findViewById<LinearLayout>(R.id.railRow)
            for (s in g.waves) {
                val art = card(row, 170, 170, below = s.name) { startWave(it, s.seed, "Волна · ${s.name}") }
                // the covers are glyphs on transparent, so give each its own colour underneath
                if (s.color != 0) (art.parent as View).backgroundTintList = ColorStateList.valueOf(s.color and 0x00FFFFFF or 0x55000000)
                Covers.into(art, img(s.cover, 300), 0)
            }
            box.addView(rail)
        }
    }

    private fun showRadio(genres: List<Pair<String, String>>) {
        val box = findViewById<LinearLayout>(R.id.radio)
        val rail = layoutInflater.inflate(R.layout.rail, box, false)
        rail.findViewById<TextView>(R.id.railTitle).text = "Радио"
        val row = rail.findViewById<LinearLayout>(R.id.railRow)
        // the API lists genres in no useful order, so bring the common ones forward
        val top = listOf("Поп", "Рок", "Танцевальная", "Хип-хоп", "Электронная", "Русский рок", "Шансон", "Ретро", "Джаз", "Чиллаут")
        val sorted = genres.sortedBy { top.indexOf(it.second).let { i -> if (i < 0) top.size else i } }
        val colors = longArrayOf(0xFF7B3FE4, 0xFFD6336C, 0xFF1C7ED6, 0xFF2B9348, 0xFFE8590C, 0xFFC92A2A, 0xFF0B8A8A, 0xFF5F3DC4, 0xFFAE3EC9, 0xFF3B5BDB)
            .map { it.toInt() }
        val dp = resources.displayMetrics.density
        for ((i, genre) in (listOf(null to "Все станции") + sorted).withIndex()) {
            val (slug, name) = genre
            val art = card(row, 200, 120, inside = name) {
                startActivity(Intent(this, ListActivity::class.java).putExtra("mode", "radio").putExtra("genre", slug).putExtra("title", "Радио · $name"))
            }
            val frame = art.parent as View
            val ink = if (i == 0) 0xFF121212.toInt() else android.graphics.Color.WHITE
            frame.backgroundTintList = ColorStateList.valueOf(if (i == 0) getColor(R.color.focus) else colors[(i - 1) % colors.size])
            frame.findViewById<TextView>(R.id.inside).apply {
                (layoutParams as android.widget.FrameLayout.LayoutParams).gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                textSize = 24f
                setTextColor(ink)
                setShadowLayer(0f, 0f, 0f, 0)
            }
            // a big faint radio glyph peeking out of the corner
            art.setImageResource(R.drawable.ic_radio)
            art.imageTintList = ColorStateList.valueOf(ink)
            art.alpha = 0.22f
            art.scaleType = ImageView.ScaleType.FIT_END
            art.setPadding((110 * dp).toInt(), (20 * dp).toInt(), 0, 0)
            art.translationX = 18 * dp
            art.translationY = 14 * dp
        }
        box.addView(rail)
    }

    private fun showPlaylists(lists: List<Playlist>) {
        if (lists.isEmpty()) return
        findViewById<View>(R.id.playlistsTitle).visibility = View.VISIBLE
        val row = findViewById<LinearLayout>(R.id.playlistsRow)
        for (p in lists) {
            val art = card(row, 150, 150, below = p.title) {
                startActivity(
                    Intent(this, ListActivity::class.java)
                        .putExtra("mode", "playlist").putExtra("owner", p.owner).putExtra("kind", p.kind).putExtra("title", p.title)
                )
            }
            Covers.into(art, img(p.coverTpl, 300), 0)
        }
    }

    private fun startWave(v: View, seed: String, name: String) {
        v.isEnabled = false
        bg({ Api.waveStart(seed) }, { e -> v.isEnabled = true; showError(e) }) { b ->
            v.isEnabled = true
            Playback.playWave(this, b, name)
            openPlayer()
        }
    }

    private fun openList(mode: String) = startActivity(Intent(this, ListActivity::class.java).putExtra("mode", mode))
    private fun openPlayer() =
        startActivity(Intent(this, if (Playback.current?.isClip == true) ClipActivity::class.java else PlayerActivity::class.java))

    private fun settings() {
        AlertDialog.Builder(this)
            .setTitle("Качество звука")
            .setSingleChoiceItems(qualityNames, qualities.indexOf(Api.quality)) { d, i ->
                Api.quality = qualities[i]
                d.dismiss()
            }
            .setNeutralButton("Выйти из аккаунта") { _, _ ->
                Playback.player.clearMediaItems()
                Api.token = null
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .show()
    }
}
