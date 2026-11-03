package dev.yama

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Yandex image URI with a %% size placeholder, e.g. avatars.yandex.net/get-music-content/.../%% */
fun img(template: String?, size: Int) =
    template?.let { (if (it.startsWith("http")) it else "https://$it").replace("%%", "${size}x$size") }

class Track(val id: String, val albumId: String?, val title: String, val artists: String, val coverTpl: String?, val durationMs: Long) {
    /** "id:albumId", the form rotor expects */
    val qid get() = if (albumId != null) "$id:$albumId" else id
    fun cover(size: Int = 600) = img(coverTpl, size)
}

class Playlist(val owner: String, val kind: String, val title: String, val count: Int, val coverTpl: String?)

class Artist(val id: String, val name: String, val coverTpl: String?)

class Album(val id: String, val title: String, val artists: String, val year: Int, val coverTpl: String?)

/** A rotor seed to start a wave from, e.g. micro-genre:witch-house; [header] says what kind, e.g. "Моя волна по жанру". */
class Station(val seed: String, val name: String, val header: String, val cover: String?, val color: Int = 0)

class WaveGroup(val title: String, val waves: List<Station>)

class ApiError(val code: Int, msg: String) : IOException(msg)

/** Unofficial Yandex Music API; endpoints and keys mirror MarshalX/yandex-music-api. */
object Api {
    private const val BASE = "https://api.music.yandex.net"
    private const val OAUTH = "https://oauth.yandex.ru"
    private const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
    private const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
    private const val ANDROID_CLIENT = "YandexMusicAndroid/24023621"
    // get-file-info sign key only works together with this client header
    private const val DESKTOP_CLIENT = "YandexMusicDesktopAppWindows/5.95.0"
    private const val SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"

    lateinit var prefs: SharedPreferences
    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
    var uid: String
        get() = prefs.getString("uid", "")!!
        set(v) = prefs.edit().putString("uid", v).apply()
    /** nq | hq | lossless */
    var quality: String
        get() = prefs.getString("quality", "hq")!!
        set(v) = prefs.edit().putString("quality", v).apply()

    val liked: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun form(vararg kv: Pair<String, String>) =
        kv.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun http(url: String, body: String? = null, json: Boolean = false, client: String = ANDROID_CLIENT, auth: Boolean = true): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("X-Yandex-Music-Client", client)
        if (auth) token?.let { c.setRequestProperty("Authorization", "OAuth $it") }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (!ok) throw ApiError(c.responseCode, "HTTP ${c.responseCode}: ${text.take(300)}")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun get(path: String) = http(BASE + path)
    private fun post(path: String, vararg kv: Pair<String, String>) = http(BASE + path, form(*kv))
    private fun postJson(path: String, body: JSONObject) = http(BASE + path, body.toString(), json = true)

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }

    // --- login (OAuth device code: user confirms on the phone) ---

    fun deviceCode(deviceId: String) = http(
        "$OAUTH/device/code",
        form("client_id" to CLIENT_ID, "device_id" to deviceId, "device_name" to "YaMa TV"), auth = false
    )

    /** Access token, or null while the user hasn't confirmed yet. */
    fun pollToken(deviceCode: String): String? = try {
        http(
            "$OAUTH/token",
            form("grant_type" to "device_code", "code" to deviceCode, "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET),
            auth = false
        ).getString("access_token")
    } catch (e: ApiError) {
        if (e.code == 400 && "authorization_pending" in e.message.orEmpty()) null else throw e
    }

    fun loadUid() {
        uid = get("/account/status").getJSONObject("result").getJSONObject("account").get("uid").toString()
    }

    // --- library ---

    fun track(j: JSONObject): Track {
        val version = j.str("version")
        return Track(
            id = j.get("id").toString(),
            albumId = j.optJSONArray("albums")?.optJSONObject(0)?.opt("id")?.toString(),
            title = j.optString("title") + if (version != null) " ($version)" else "",
            artists = names(j),
            coverTpl = j.str("coverUri"),
            durationMs = j.optLong("durationMs"),
        )
    }

    private fun names(j: JSONObject) = j.optJSONArray("artists")?.objects()?.joinToString(", ") { it.optString("name") }.orEmpty()

    private fun playable(items: List<JSONObject>) = items.filter { it.optBoolean("available", true) }.map(::track)

    fun tracks(ids: List<String>): List<Track> =
        playable(post("/tracks", "track-ids" to ids.joinToString(",")).getJSONArray("result").objects())

    fun likedIds(): List<String> =
        get("/users/$uid/likes/tracks").getJSONObject("result").getJSONObject("library").getJSONArray("tracks")
            .objects().map { it.get("id").toString() }
            .also { liked.clear(); liked.addAll(it) }

    fun playlists(): List<Playlist> {
        val own = get("/users/$uid/playlists/list").getJSONArray("result").objects()
        // liked playlists of other people are a bonus, don't fail the screen on them
        val others = runCatching {
            get("/users/$uid/likes/playlists").getJSONArray("result").objects().mapNotNull { it.optJSONObject("playlist") }
        }.getOrDefault(emptyList())
        return (own + others).map(::playlist)
    }

    private fun playlist(j: JSONObject): Playlist {
        val cover = j.optJSONObject("cover")
        val tpl = cover?.str("uri") ?: cover?.optJSONArray("itemsUri")?.optString(0) ?: j.str("ogImage")
        return Playlist(j.get("uid").toString(), j.get("kind").toString(), j.optString("title"), j.optInt("trackCount"), tpl)
    }

    fun artistTracks(id: String): List<Track> =
        playable(get("/artists/$id/tracks?page-size=100").getJSONObject("result").getJSONArray("tracks").objects())

    fun albumTracks(id: String): List<Track> =
        playable(get("/albums/$id/with-tracks").getJSONObject("result").getJSONArray("volumes").let { v ->
            (0 until v.length()).flatMap { v.getJSONArray(it).objects() }
        })

    fun playlistTracks(owner: String, kind: String): List<Track> {
        val items = get("/users/$owner/playlists/$kind").getJSONObject("result").optJSONArray("tracks")?.objects().orEmpty()
        val full = items.mapNotNull { it.optJSONObject("track") }
        return if (full.size == items.size) playable(full)
        else items.map { it.get("id").toString() }.chunked(200).flatMap(::tracks)
    }

    fun setLiked(id: String, like: Boolean) {
        post("/users/$uid/likes/tracks/" + if (like) "add-multiple" else "remove", "track-ids" to id)
    }

    fun dislike(id: String) {
        post("/users/$uid/dislikes/tracks/add-multiple", "track-ids" to id)
    }

    // --- My Wave (rotor session) ---

    class Batch(val session: String, val batchId: String, val tracks: List<Track>)

    private fun batch(session: String?, r: JSONObject) = Batch(
        session ?: r.getString("radioSessionId"),
        r.optString("batchId"),
        r.getJSONArray("sequence").objects().map { track(it.getJSONObject("track")) },
    )

    /** [seed]: user:onyourwave, track:<id>, genre:<tag>, ... */
    fun waveStart(seed: String = "user:onyourwave") = batch(
        null,
        postJson("/rotor/session/new", JSONObject().put("seeds", JSONArray().put(seed)).put("includeTracksInResponse", true))
            .getJSONObject("result")
    )

    /** The personal wave picks the official app shows on its home screen, grouped (top, genre, mood, ...). */
    fun waves(): List<WaveGroup> = get("/landing-blocks/waves").getJSONArray("waves").objects()
        .sortedBy { it.optString("id") in listOf("language", "diversity") } // genres and moods before languages
        .map { g ->
        WaveGroup(
            if (g.optString("id") == "mix") "Волны для тебя" else "Волна " + g.optString("title"),
            g.getJSONArray("items").objects().map {
                val cover = it.optJSONObject("agent")?.optJSONObject("cover")
                val color = runCatching { android.graphics.Color.parseColor(cover?.optString("color")) }.getOrDefault(0)
                Station(it.getString("stationId"), it.optString("title"), it.optString("header"), cover?.str("uri"), color)
            },
        )
    }

    /** Mixed rows: a few artists, the tracks, then albums and playlists. */
    fun search(text: String): List<Any> {
        val r = get("/search?" + form("text" to text, "type" to "all", "page" to "0", "nocorrect" to "false")).getJSONObject("result")
        fun results(k: String) = r.optJSONObject(k)?.optJSONArray("results")?.objects().orEmpty()
        val artists = results("artists").take(3).map { Artist(it.get("id").toString(), it.optString("name"), it.optJSONObject("cover")?.str("uri")) }
        val albums = results("albums").take(6).map {
            Album(it.get("id").toString(), it.optString("title"), names(it), it.optInt("year"), it.str("coverUri"))
        }
        return artists + playable(results("tracks")) + albums + results("playlists").take(6).map(::playlist)
    }

    fun waveMore(session: String, queue: List<Track>) = batch(
        session,
        postJson("/rotor/session/$session/tracks", JSONObject().put("queue", JSONArray(queue.map { it.qid })))
            .getJSONObject("result")
    )

    fun waveFeedback(w: Batch, type: String, t: Track?, playedSec: Double? = null) {
        val ev = JSONObject().put("type", type).put("timestamp", iso())
        t?.let { ev.put("trackId", it.qid) }
        playedSec?.let { ev.put("totalPlayedSeconds", it) }
        postJson(
            "/rotor/session/${w.session}/feedback",
            JSONObject().put("event", ev).put("batchId", w.batchId).put("from", "radio-mobile-user-onyourwave")
        )
    }

    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(System.currentTimeMillis())

    // --- streaming ---

    /** Direct file URL; valid for about a minute, so ask right before playing. */
    fun streamUrl(trackId: String): String {
        val q = quality
        val codecs = when (q) {
            "lossless" -> listOf("flac", "aac", "mp3")
            "hq" -> listOf("mp3", "aac")
            else -> listOf("aac", "he-aac", "mp3")
        }
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SIGN_KEY.toByteArray(), "HmacSHA256")) }
        val sign = Base64.encodeToString(mac.doFinal("$ts$trackId$q${codecs.joinToString("")}raw".toByteArray()), Base64.NO_WRAP).dropLast(1)
        val url = "$BASE/get-file-info?" + form(
            "ts" to ts, "trackId" to trackId, "quality" to q, "codecs" to codecs.joinToString(","), "transports" to "raw", "sign" to sign
        )
        val info = http(url, client = DESKTOP_CLIENT).getJSONObject("result").getJSONObject("downloadInfo")
        if (info.optString("transport") == "encraw") throw IOException("encrypted stream not supported yet")
        return info.getString("url")
    }
}
