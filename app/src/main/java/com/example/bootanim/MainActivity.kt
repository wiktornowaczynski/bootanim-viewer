package com.example.bootanim

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.zip.ZipInputStream

class Part(val name: String, val loop: Int, val pause: Int, val frames: List<ByteArray>)

class AnimView(a: Activity) : View(a) {
    var bmp: Bitmap? = null
    var w = 1; var h = 1
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bmp ?: return
        val s = minOf(width / w.toFloat(), height / h.toFloat())
        val fw = b.width * s; val fh = b.height * s
        val l = (width - fw) / 2; val t = (height - fh) / 2
        c.drawBitmap(b, null, RectF(l, t, l + fw, t + fh), paint)
    }
}

class MainActivity : Activity() {
    private lateinit var view: AnimView
    private lateinit var info: TextView
    private lateinit var playBtn: Button
    private var parts = listOf<Part>()
    private var fps = 30
    private var p = 0; private var f = 0; private var l = 0; private var wait = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = Runnable { tick() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1E.toInt())
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom); i
            }
        }
        view = AnimView(this)
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        info = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); setPadding(24, 16, 24, 8)
            text = "Tap Open and choose a bootanimation.zip"
        }
        root.addView(info)
        val bar = LinearLayout(this)
        fun btn(label: String, a: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { a() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        btn("Open") { pick() }
        playBtn = btn("Pause") { toggle() }
        btn("Restart") { start() }
        btn("Next") { skip() }
        root.addView(bar)
        setContentView(root)
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
