package dev.yama

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Track list (likes / playlist / artist / album), the list of playlists, or search, picked by the "mode" extra. */
class ListActivity : Activity() {
    private val rows = ArrayList<Any>()
    private lateinit var list: ListView
    private lateinit var name: String

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.item_row, parent, false).also { it.findViewById<View>(R.id.cover).rounded() }
            val r = rows[p]
            val (a, b, c) = when (r) {
                is Track -> Triple(r.title, r.artists, fmt(r.durationMs))
                is Playlist -> Triple(r.title, plural(r.count, "трек", "трека", "треков"), "")
                is Artist -> Triple(r.name, "Исполнитель", "")
                is Album -> Triple(r.title, listOfNotNull("Альбом", r.artists, r.year.takeIf { it > 0 }).joinToString(" · "), "")
                else -> Triple("", "", "")
            }
            v.findViewById<TextView>(R.id.line1).text = a
            v.findViewById<TextView>(R.id.line2).apply { text = b; visibility = if (b.isEmpty()) View.GONE else View.VISIBLE }
            v.findViewById<TextView>(R.id.right).text = c
            val playing = r is Track && r.id == Playback.current?.id
            v.findViewById<View>(R.id.playing).visibility = if (playing) View.VISIBLE else View.GONE
            val url = when (r) {
                is Track -> r.cover(200)
                is Playlist -> img(r.coverTpl, 200)
                is Artist -> img(r.coverTpl, 200)
                is Album -> img(r.coverTpl, 200)
                else -> null
            }
            Covers.into(v.findViewById<ImageView>(R.id.cover), url, if (r is Playlist) R.drawable.ic_playlist else R.drawable.ic_note)
            return v
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            when (val r = rows[pos]) {
                is Track -> play(tracks(), tracks().indexOf(r))
                is Artist -> open("artist", r.name).putExtra("id", r.id).let(::startActivity)
                is Album -> open("album", r.title).putExtra("id", r.id).let(::startActivity)
                is Playlist -> open("playlist", r.title).putExtra("owner", r.owner).putExtra("kind", r.kind).let(::startActivity)
            }
        }

        val mode = intent.getStringExtra("mode")
        name = when (mode) {
            "likes" -> "Мне нравится"
            "playlists" -> "Плейлисты"
            "search" -> "Поиск"
            else -> intent.getStringExtra("title").orEmpty()
        }
        findViewById<TextView>(R.id.title).text = name
        if (mode != "playlists") {
            for (id in intArrayOf(R.id.playAll, R.id.shuffle)) findViewById<View>(id).zoomOnFocus()
            findViewById<View>(R.id.playAll).setOnClickListener { if (rows.isNotEmpty()) play(tracks(), 0) }
            findViewById<View>(R.id.shuffle).setOnClickListener { if (rows.isNotEmpty()) play(tracks().shuffled(), 0) }
        }
        val id = intent.getStringExtra("id").orEmpty()
        if (mode == "artist") findViewById<View>(R.id.wave).apply {
            zoomOnFocus()
            setOnClickListener {
                bg({ Api.waveStart("artist:$id") }) { b ->
                    Playback.playWave(this@ListActivity, b, "Волна · $name")
                    startActivity(Intent(this@ListActivity, PlayerActivity::class.java))
                }
            }
        }
        when (mode) {
            "search" -> searchBox()
            "likes" -> bg({ Api.likedIds() }) { ids -> loadChunks(ids.chunked(200), 0) }
            "playlists" -> bg({ Api.playlists() }) { show(it) }
            "artist" -> bg({ Api.artistTracks(id) }) { show(it) }
            "album" -> bg({ Api.albumTracks(id) }) { show(it) }
            else -> bg({ Api.playlistTracks(intent.getStringExtra("owner")!!, intent.getStringExtra("kind")!!) }) { show(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        Playback.player.addListener(listener)
        adapter.notifyDataSetChanged()
    }

    override fun onStop() {
        Playback.player.removeListener(listener)
        super.onStop()
    }

    private fun searchBox() {
        findViewById<View>(R.id.title).visibility = View.GONE
        findViewById<View>(R.id.progress).visibility = View.GONE
        val q = findViewById<EditText>(R.id.query)
        q.visibility = View.VISIBLE
        q.requestFocus()
        // search as you type, once the typing pauses
        val run = Runnable {
            val text = q.text.toString().trim()
            if (text.isEmpty()) return@Runnable
            bg({ Api.search(text) }) { found ->
                if (q.text.toString().trim() != text) return@bg
                rows.clear()
                show(found)
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { main.removeCallbacks(run); main.postDelayed(run, 500) }
        })
        q.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH && rows.isNotEmpty()) list.requestFocus()
            false
        }
    }

    private fun open(mode: String, title: String) =
        Intent(this, ListActivity::class.java).putExtra("mode", mode).putExtra("title", title)

    private fun tracks() = rows.filterIsInstance<Track>()

    private fun play(tracks: List<Track>, index: Int) {
        Playback.play(this, tracks, index, name)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Big likes lists come in chunks so the first tracks show up right away. */
    private fun loadChunks(chunks: List<List<String>>, i: Int) {
        if (i == chunks.size) return show(emptyList())
        bg({ Api.tracks(chunks[i]) }) { show(it); loadChunks(chunks, i + 1) }
    }

    private fun show(items: List<Any>) {
        val first = rows.isEmpty() && intent.getStringExtra("mode") != "search" // don't pull focus out of the search field
        rows.addAll(items)
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.progress).visibility = View.GONE
        val n = rows.size
        val mode = intent.getStringExtra("mode")
        findViewById<TextView>(R.id.sub).text = when {
            mode == "search" -> ""
            rows.firstOrNull() is Playlist -> plural(n, "плейлист", "плейлиста", "плейлистов")
            else -> plural(n, "трек", "трека", "треков")
        }
        if (mode != "search" && rows.firstOrNull() is Track) for (id in intArrayOf(R.id.playAll, R.id.shuffle)) findViewById<View>(id).visibility = View.VISIBLE
        if (mode == "artist") findViewById<View>(R.id.wave).visibility = View.VISIBLE
        if (first && rows.isNotEmpty()) list.requestFocus()
    }
}
