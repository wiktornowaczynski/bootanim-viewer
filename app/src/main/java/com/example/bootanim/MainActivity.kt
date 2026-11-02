package com.example.bootanim

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.zip.ZipInputStream

class Part(val name: String, val loop: Int, val pause: Int, val frames: List<ByteArray>)

class Pal(val dark: Boolean, val bg: Int, val muted: Int, val btn: Int, val onBtn: Int, val ripple: Int)

class AnimView(a: Activity) : View(a) {
    var bmp: Bitmap? = null
    var w = 1; var h = 1
    var hint = ""
    private val dens = a.resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt(); textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, a.resources.displayMetrics)
    }
    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 28 * dens)
            }
        }
        clipToOutline = true
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bmp
        if (b == null) {
            val lines = hint.split("\n")
            val lh = hintPaint.textSize * 1.4f
            var y = height / 2f - (lines.size - 1) * lh / 2f + hintPaint.textSize / 3f
            for (l in lines) { c.drawText(l, width / 2f, y, hintPaint); y += lh }
            return
        }
        val s = minOf(width / w.toFloat(), height / h.toFloat())
        val fw = b.width * s; val fh = b.height * s
        val l = (width - fw) / 2; val t = (height - fh) / 2
        c.drawBitmap(b, null, RectF(l, t, l + fw, t + fh), paint)
    }
}

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var view: AnimView
    private lateinit var info: TextView
    private lateinit var playBtn: Button
    private val buttons = ArrayList<Button>()
    private var parts = listOf<Part>()
    private var fps = 30
    private var p = 0; private var f = 0; private var l = 0; private var wait = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = Runnable { tick() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun palette(): Pal {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int) = getColor(id)
            return if (dark) Pal(true, c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_300),
                c(android.R.color.system_accent1_700), c(android.R.color.system_accent1_100), c(android.R.color.system_accent1_300))
            else Pal(false, c(android.R.color.system_neutral1_10), c(android.R.color.system_neutral2_600),
                c(android.R.color.system_accent1_100), c(android.R.color.system_accent1_900), c(android.R.color.system_accent1_300))
        }
        return if (dark) Pal(true, 0xFF1C1B1F.toInt(), 0xFFCAC4D0.toInt(), 0xFF4A4458.toInt(), 0xFFE8DEF8.toInt(), 0xFFD0BCFF.toInt())
        else Pal(false, 0xFFFFFBFE.toInt(), 0xFF49454F.toInt(), 0xFFE8DEF8.toInt(), 0xFF1D192B.toInt(), 0xFF6750A4.toInt())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(100).toFloat(); setColor(color)
    }

    private fun applyColors() {
        val pal = palette()
        root.setBackgroundColor(pal.bg)
        info.setTextColor(pal.muted)
        val rip = ColorStateList.valueOf((pal.ripple and 0x00FFFFFF) or 0x40000000)
        for (b in buttons) {
            b.setTextColor(pal.onBtn)
            b.background = RippleDrawable(rip, pill(pal.btn), pill(Color.BLACK))
        }
        val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (pal.dark) 0 else m, m)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom); i
            }
        }
        view = AnimView(this).apply { hint = "Tap Open and choose\na bootanimation.zip" }
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        })
        info = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 13f
            setPadding(dp(24), dp(8), dp(24), dp(12))
            text = "No animation loaded"
        }
        root.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        fun btn(label: String, a: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f; stateListAnimator = null
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(18), 0, dp(18), 0); setOnClickListener { a() }
        }.also {
            buttons.add(it)
            bar.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        btn("Open") { pick() }
        playBtn = btn("Pause") { toggle() }
        btn("Restart") { start() }
        btn("Next") { skip() }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        applyColors()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        applyColors()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        data?.data?.let { load(it) }
    }

    private fun load(uri: Uri) {
        stop(); info.text = "Loading…"
        Thread {
            try {
                val files = HashMap<String, ByteArray>()
                contentResolver.openInputStream(uri)!!.use { ins ->
                    ZipInputStream(ins).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name
                            if (!e.isDirectory && !n.startsWith("__MACOSX") &&
                                !n.substringAfterLast('/').startsWith("._")) files[n] = z.readBytes()
                            e = z.nextEntry
                        }
                    }
                }
                val dk = files.keys.firstOrNull { it.substringAfterLast('/').equals("desc.txt", true) }
                    ?: throw Exception("No desc.txt found")
                val lines = String(files[dk]!!).lines().map { it.trim() }.filter { it.isNotEmpty() }
                val head = lines[0].split(Regex("\\s+")).map { it.toInt() }
                val groups = files.keys.filter { Regex("(?i).*\\.(png|jpe?g)$").matches(it) }
                    .groupBy { it.substringBeforeLast('/', "").substringAfterLast('/') }
                val list = ArrayList<Part>()
                for (ln in lines.drop(1)) {
                    val t = ln.split(Regex("\\s+"))
                    if (t.size < 4 || (t[0] != "p" && t[0] != "c")) continue
                    val fr = (groups[t[3]] ?: emptyList())
                        .sortedWith(compareBy({ it.length }, { it })).map { files[it]!! }
                    list.add(Part(t[3], t[1].toInt(), t[2].toInt(), fr))
                }
                if (list.none { it.frames.isNotEmpty() }) throw Exception("No image frames found")
                runOnUiThread {
                    view.w = head[0]; view.h = head[1]; fps = head[2].coerceAtLeast(1)
                    parts = list; start()
                }
            } catch (e: Exception) {
                runOnUiThread { info.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun start() {
        if (parts.isEmpty()) return
        handler.removeCallbacks(ticker)
        p = 0; f = 0; l = 0; wait = 0; playing = true
        playBtn.text = "Pause"; tick()
    }

    private fun stop() { playing = false; handler.removeCallbacks(ticker) }

    private fun toggle() {
        if (parts.isEmpty()) return
        if (playing) { stop(); playBtn.text = "Play" }
        else { playing = true; playBtn.text = "Pause"; tick() }
    }

    private fun skip() {
        if (parts.isEmpty()) return
        advance(); wait = 0
        if (!playing) { playing = true; playBtn.text = "Pause" }
        handler.removeCallbacks(ticker); tick()
    }

    private fun advance() { p = (p + 1) % parts.size; f = 0; l = 0 }

    private fun tick() {
        if (!playing) return
        val part = parts[p]
        if (wait > 0) wait--
        else {
            if (part.frames.isNotEmpty()) {
                view.bmp = BitmapFactory.decodeByteArray(part.frames[f], 0, part.frames[f].size)
                view.invalidate()
                info.text = "Part ${p + 1}/${parts.size} (${part.name}) · frame ${f + 1}/${part.frames.size} · " +
                    "loop ${l + 1}" + if (part.loop > 0) "/${part.loop}" else " (∞)"
            }
            f++
            if (f >= part.frames.size) {
                f = 0; l++
                if (part.loop > 0 && l >= part.loop) { wait = part.pause; advance() }
            }
        }
        handler.postDelayed(ticker, 1000L / fps)
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
