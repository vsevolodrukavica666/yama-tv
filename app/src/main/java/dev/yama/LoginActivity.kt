package dev.yama

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

class LoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        start()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(this)
        super.onDestroy()
    }

    private val deviceId by lazy {
        Api.prefs.getString("device", null)
            ?: UUID.randomUUID().toString().replace("-", "").take(16).also { Api.prefs.edit().putString("device", it).apply() }
    }

    private fun start() {
        bg({ Api.deviceCode(deviceId) }, { e -> showError(e); later(5000) { start() } }) { j ->
            val url = j.getString("verification_url")
            findViewById<TextView>(R.id.url).text = url
            findViewById<TextView>(R.id.code).text = j.getString("user_code")
            findViewById<ImageView>(R.id.qr).setImageBitmap(qr(url))
            poll(j.getString("device_code"), j.optInt("interval", 5) * 1000L)
        }
    }

    private fun poll(code: String, every: Long): Unit = later(every) {
        // any error here (code expired, denied) just restarts with a fresh code
        bg({ Api.pollToken(code) }, { e -> showError(e); start() }) { token ->
            if (token == null) return@bg poll(code, every)
            Api.token = token
            // without the uid every request fails, so a half-done login starts over
            bg({ Api.loadUid() }, { e -> Api.token = null; showError(e); start() }) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun later(ms: Long, f: () -> Unit) {
        main.postAtTime(f, this, android.os.SystemClock.uptimeMillis() + ms)
    }

    private fun qr(text: String): Bitmap {
        val n = 512
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n)
        val px = IntArray(n * n) { if (m[it % n, it / n]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.RGB_565)
    }
}
