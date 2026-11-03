package dev.yama

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.prefs = getSharedPreferences("yama", MODE_PRIVATE)
        Covers.init(this)
        Playback.init(this)
    }
}

val io = Executors.newFixedThreadPool(3)!!
val main = Handler(Looper.getMainLooper())

/** Run [work] off the main thread, then [done] on it; errors go to [onError] or a toast. */
fun <T> Activity.bg(work: () -> T, onError: ((Throwable) -> Unit)? = null, done: (T) -> Unit) {
    io.execute {
        val r = runCatching(work)
        main.post {
            if (isDestroyed) return@post
            r.onSuccess(done).onFailure { e ->
                Log.w("YaMa", "request failed", e)
                if (onError != null) onError(e) else showError(e)
            }
        }
    }
}

/** Fire-and-forget network call. */
fun background(work: () -> Unit) = io.execute {
    runCatching(work).onFailure { Log.w("YaMa", "background call failed", it) }
}

fun Activity.showError(e: Throwable) {
    if (e is ApiError && e.code == 401) {
        Api.token = null
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        return
    }
    Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
}

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Small image loader with a memory cache; good enough for covers in lists. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Covers {
    private lateinit var loader: androidx.media3.datasource.DataSourceBitmapLoader
    // sized in bytes: a 600px player cover alone is ~1.4 MB, and the TV has 2 GB
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(32 shl 20) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    fun init(ctx: android.content.Context) {
        loader = androidx.media3.datasource.DataSourceBitmapLoader(ctx)
    }

    /** Calls [done] on the main thread; skipped if [key] says the target was reused meanwhile. */
    fun load(url: String?, done: (android.graphics.Bitmap) -> Unit) {
        if (url == null) return
        cache.get(url)?.let { return done(it) }
        val f = loader.loadBitmap(android.net.Uri.parse(url))
        f.addListener({ runCatching { f.get() }.getOrNull()?.let { cache.put(url, it); done(it) } }, { main.post(it) })
    }

    fun into(iv: android.widget.ImageView, url: String?, placeholder: Int = R.drawable.ic_note) {
        iv.tag = url
        iv.setImageResource(placeholder)
        load(url) { if (iv.tag == url) iv.setImageBitmap(it) }
    }
}

/** TV-style focus: the focused view grows a bit. */
fun android.view.View.zoomOnFocus(scale: Float = 1.06f) {
    val prev = onFocusChangeListener
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(150).start()
        prev?.onFocusChange(v, has)
    }
}

/** Rounded corners for images and tiles: clip to the background's shape. */
fun android.view.View.rounded() {
    clipToOutline = true
}

/** Cheap blur for backdrops (RenderEffect needs API 31): shrink to a few pixels, then smooth back up in two steps. */
fun blurred(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val tiny = android.graphics.Bitmap.createScaledBitmap(bmp, 10, 10, true)
    return android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createScaledBitmap(tiny, 40, 40, true), 160, 160, true)
}

/** "1 трек", "3 трека", "5 треков" */
fun plural(n: Int, one: String, few: String, many: String) =
    "$n " + when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }
