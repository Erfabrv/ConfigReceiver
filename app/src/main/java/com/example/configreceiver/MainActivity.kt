package com.example.configreceiver

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder

class MainActivity : Activity() {

    private val port = 8080
    private val pin = (1000..9999).random().toString()
    private val configs = mutableListOf<String>()
    private val labels = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var server: ConfigServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadConfigs()

        val ip = localIp()
        val url = "http://$ip:$port/?pin=$pin"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F1A24"))
            setPadding(48, 40, 48, 40)
        }

        // سمت چپ: QR و آدرس
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        left.addView(text("با دوربین گوشی این کد رو اسکن کن", 20f))
        left.addView(ImageView(this).apply {
            setImageBitmap(qr(url, 360))
            setPadding(0, 20, 0, 20)
        }, LinearLayout.LayoutParams(400, 400))
        left.addView(text("یا در مرورگر گوشی باز کن:\n$url", 16f))
        left.addView(text("کد: $pin", 30f).apply { setPadding(0, 16, 0, 0) })
        root.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        // سمت راست: لیست کانفیگ‌ها
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(text("کانفیگ‌های دریافت‌شده (برای گزینه‌ها OK بزن)", 20f))
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> showActions(pos) }
        }
        right.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.3f))

        setContentView(root)
        refresh()

        server = ConfigServer(port, pin) { body -> runOnUiThread { receive(body) } }
            .also { it.start() }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun receive(body: String) {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var added = 0
        for (line in lines) {
            if (line !in configs) { configs.add(0, line); added++ }
        }
        saveConfigs()
        refresh()
        toast("$added کانفیگ جدید دریافت شد")
    }

    private fun showActions(pos: Int) {
        val cfg = configs[pos]
        AlertDialog.Builder(this)
            .setTitle(labels[pos])
            .setItems(arrayOf("کپی و باز کردن v2rayNG", "ارسال به برنامه‌ی دیگه", "حذف")) { _, which ->
                when (which) {
                    0 -> copyAndOpen(cfg)
                    1 -> share(cfg)
                    2 -> { configs.removeAt(pos); saveConfigs(); refresh() }
                }
            }
            .show()
    }

    private fun copyAndOpen(cfg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("config", cfg))
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(V2RAYNG) ?: pm.getLaunchIntentForPackage(V2RAYNG)
        if (intent == null) {
            toast("کانفیگ کپی شد، ولی v2rayNG نصب نیست.")
        } else {
            toast("کپی شد. توی v2rayNG از منوی + گزینه‌ی Import from clipboard رو بزن.")
            startActivity(intent)
        }
    }

    private fun share(cfg: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cfg)
        }
        try {
            startActivity(Intent.createChooser(send, "ارسال کانفیگ به..."))
        } catch (e: Exception) {
            toast("برنامه‌ای برای دریافت پیدا نشد.")
        }
    }

    private fun label(cfg: String): String {
        val scheme = cfg.substringBefore("://", "?")
        if (cfg.startsWith("vmess://")) {
            runCatching {
                val json = String(Base64.decode(cfg.removePrefix("vmess://"), Base64.DEFAULT))
                return "[vmess] " + JSONObject(json).optString("ps", "بی‌نام")
            }
        }
        val name = cfg.substringAfter('#', "")
        val shown = if (name.isNotEmpty())
            runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        else cfg.take(45)
        return "[$scheme] $shown"
    }

    private fun refresh() {
        labels.clear()
        labels.addAll(configs.map { label(it) })
        adapter.notifyDataSetChanged()
    }

    private fun saveConfigs() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("list", configs.joinToString("\n")).apply()
    }

    private fun loadConfigs() {
        val s = getSharedPreferences("cfg", MODE_PRIVATE).getString("list", "") ?: ""
        configs.clear()
        configs.addAll(s.lines().filter { it.isNotBlank() })
    }

    private fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "0.0.0.0"

    private fun qr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor("#E8EEF3"))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val V2RAYNG = "com.v2ray.ang"
    }
}
