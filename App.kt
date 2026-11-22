package com.wans.tpl

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

// ===== Data template =====
class Tpl(val name: String, val text: String, val mode: Int = 0, val loop: Boolean = false)

// Pola angka: {000} (cara lama) atau angka langsung di dalam kalimat, contoh pesan000.
// Tanpa kurung, yang dipakai adalah deretan angka paling belakang (maks 9 digit).
val NUM_PATTERN = Regex("\\{(\\d{1,9})\\}|(\\d{1,9})(?!.*\\d)")

object Store {
    fun load(c: Context): MutableList<Tpl> {
        val sp = c.getSharedPreferences("tpl", 0)
        val out = mutableListOf<Tpl>()
        val j = sp.getString("data", null)
        if (j != null) {
            try {
                val a = JSONArray(j)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val body = o.getString("c")
                    val md = if (o.has("m")) o.getInt("m")
                    else if (body.indexOf('\n') < 0 && Regex("\\{\\d{1,9}\\}").containsMatchIn(body)) 1 else 0
                    out.add(Tpl(o.getString("n"), body, md, o.optBoolean("l", false)))
                }
            } catch (e: Exception) {
            }
            return out
        }
        // migrasi dari versi lama (tanpa nama)
        val old = sp.getString("items", "") ?: ""
        if (old.isNotEmpty()) {
            old.split("\u0001").forEachIndexed { i, s -> out.add(Tpl("Template " + (i + 1), s)) }
        }
        return out
    }

    // Cadangan: format JSON sama dengan penyimpanan internal
    fun exportJson(c: Context): String {
        val a = JSONArray()
        load(c).forEach {
            val o = JSONObject()
            o.put("n", it.name)
            o.put("c", it.text)
            o.put("m", it.mode)
            o.put("l", it.loop)
            a.put(o)
        }
        return a.toString(2)
    }

    // Mengembalikan jumlah template baru yang ditambahkan, atau -1 kalau bukan file cadangan.
    // Template dengan nama dan isi yang sama dilewati.
    fun importJson(c: Context, txt: String): Int {
        if (!txt.startsWith("[")) return -1
        try {
            val a = JSONArray(txt)
            val all = load(c)
            var added = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val n = o.getString("n")
                val body = o.getString("c")
                if (all.any { it.name == n && it.text == body }) continue
                all.add(Tpl(n, body, o.optInt("m", 0), o.optBoolean("l", false)))
                added++
            }
            save(c, all)
            return added
        } catch (e: Exception) {
            return -1
        }
    }

    fun save(c: Context, l: List<Tpl>) {
        val a = JSONArray()
        l.forEach {
            val o = JSONObject()
            o.put("n", it.name)
            o.put("c", it.text)
            o.put("m", it.mode)
            o.put("l", it.loop)
            a.put(o)
        }
        c.getSharedPreferences("tpl", 0).edit().putString("data", a.toString()).apply()
    }

    // posisi bubble terakhir (-1 = belum pernah disimpan)
    fun bubbleX(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("bx", -1)

    fun bubbleY(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("by", -1)

    fun setBubblePos(c: Context, x: Int, y: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("bx", x).putInt("by", y).apply()
    }

    // Jeda per ketikan dalam milidetik (0 = Turbo). Kalau belum pernah diatur, ikut nilai slider lama.
    fun delayMs(c: Context): Int {
        val sp = c.getSharedPreferences("tpl", 0)
        return if (sp.contains("ms")) sp.getInt("ms", 700) else sp.getInt("speed", 35) * 20
    }

    fun setDelayMs(c: Context, v: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("ms", v.coerceIn(0, 5000)).apply()
    }

    fun speed(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("speed", 35)

    fun setSpeed(c: Context, v: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("speed", v).apply()
    }

    // 0 = lewat clipboard, 1 = isi teks langsung (tanpa clipboard, tanpa popup sistem)
    fun pasteMode(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("paste", 0)

    fun setPasteMode(c: Context, v: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("paste", v).apply()
    }

    // 0 = tombol Enter keyboard, 1 = pindah ke kolom berikutnya, 2 = ketuk tombol Kirim/Send
    fun enterMode(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("enter", 0)

    fun setEnterMode(c: Context, v: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("enter", v).apply()
    }
}

// ScrollView dengan tinggi maksimal
class MaxScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

// Tombol bulat dengan ikon yang digambar sendiri (tidak tergantung font/emoji HP)
class IconBtn(c: Context, private val kind: String, private val bg: Int, private val fg: Int) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    init {
        setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        fill.color = bg
        canvas.drawCircle(cx, cy, w / 2f, fill)
        fill.color = fg
        line.color = fg
        line.strokeWidth = w * 0.085f

        when (kind) {
            "play" -> {
                line.strokeWidth = w * 0.07f
                path.reset()
                path.moveTo(w * 0.40f, h * 0.30f)
                path.lineTo(w * 0.40f, h * 0.70f)
                path.lineTo(w * 0.72f, h * 0.50f)
                path.close()
                canvas.drawPath(path, fill)
                line.style = Paint.Style.STROKE
                canvas.drawPath(path, line)
            }
            "pause" -> {
                val r = w * 0.04f
                rect.set(w * 0.33f, h * 0.29f, w * 0.45f, h * 0.71f)
                canvas.drawRoundRect(rect, r, r, fill)
                rect.set(w * 0.55f, h * 0.29f, w * 0.67f, h * 0.71f)
                canvas.drawRoundRect(rect, r, r, fill)
            }
            "plus" -> {
                canvas.drawLine(w * 0.30f, cy, w * 0.70f, cy, line)
                canvas.drawLine(cx, h * 0.30f, cx, h * 0.70f, line)
            }
            "close" -> {
                canvas.drawLine(w * 0.32f, h * 0.32f, w * 0.68f, h * 0.68f, line)
                canvas.drawLine(w * 0.68f, h * 0.32f, w * 0.32f, h * 0.68f, line)
            }
            "edit" -> {
                canvas.save()
                canvas.rotate(45f, cx, cy)
                val r = w * 0.03f
                rect.set(cx - w * 0.07f, cy - w * 0.27f, cx + w * 0.07f, cy + w * 0.07f)
                canvas.drawRoundRect(rect, r, r, fill)
                path.reset()
                path.moveTo(cx - w * 0.07f, cy + w * 0.11f)
                path.lineTo(cx + w * 0.07f, cy + w * 0.11f)
                path.lineTo(cx, cy + w * 0.25f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.restore()
            }
            "reset" -> {
                val rad = w * 0.20f
                rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
                canvas.drawArc(rect, -60f, 270f, false, line)
                // kepala panah di ujung busur (sudut 210 derajat, searah jarum jam)
                val a = Math.toRadians(210.0)
                val px = cx + rad * Math.cos(a).toFloat()
                val py = cy + rad * Math.sin(a).toFloat()
                val tx = -Math.sin(a).toFloat()
                val ty = Math.cos(a).toFloat()
                val nx = -ty
                val ny = tx
                val hh = w * 0.13f
                path.reset()
                path.moveTo(px + tx * hh, py + ty * hh)
                path.lineTo(px + nx * hh * 0.85f, py + ny * hh * 0.85f)
                path.lineTo(px - nx * hh * 0.85f, py - ny * hh * 0.85f)
                path.close()
                canvas.drawPath(path, fill)
            }
            "gear" -> {
                line.strokeWidth = w * 0.09f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * w * 0.20f, cy + sn * w * 0.20f, cx + c * w * 0.28f, cy + sn * w * 0.28f, line)
                }
                canvas.drawCircle(cx, cy, w * 0.21f, fill)
                fill.color = bg
                canvas.drawCircle(cx, cy, w * 0.09f, fill)
            }
            "import" -> {
                canvas.drawLine(cx, h * 0.28f, cx, h * 0.56f, line)
                path.reset()
                path.moveTo(w * 0.38f, h * 0.46f)
                path.lineTo(cx, h * 0.59f)
                path.lineTo(w * 0.62f, h * 0.46f)
                line.style = Paint.Style.STROKE
                canvas.drawPath(path, line)
                path.reset()
                path.moveTo(w * 0.31f, h * 0.64f)
                path.lineTo(w * 0.31f, h * 0.71f)
                path.lineTo(w * 0.69f, h * 0.71f)
                path.lineTo(w * 0.69f, h * 0.64f)
                canvas.drawPath(path, line)
            }
        }
    }
}

// Bubble bulat dengan outline progres (biru). Penuh melingkar = selesai.
class RingBubble(c: Context) : TextView(c) {
    var progress = 0f
    var showTrack = false
    var ringColor = Color.parseColor("#007AFF")
    var trackColor = Color.parseColor("#33808080")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var pulse = 0f

    // Cincin menebal sebentar dua kali = tanda selesai
    fun pulseRing() {
        ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f).apply {
            duration = 700
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showTrack) return
        val d = resources.displayMetrics.density
        val sw = (3.5f + 2.5f * pulse) * d
        val m = 3f * d + 1f * d
        r.set(m, m, width - m, height - m)
        p.strokeWidth = sw
        p.color = trackColor
        canvas.drawOval(r, p)
        val f = progress.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = ringColor
            if (f >= 1f) canvas.drawOval(r, p)
            else canvas.drawArc(r, -90f, 360f * f, false, p)
        }
    }
}

// ===== Halaman apk (aktivasi + pemilih file) =====
class MainActivity : Activity() {
    private var pickMode = false
    private var exportMode = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pickMode = intent.getBooleanExtra("pick", false)
        exportMode = intent.getBooleanExtra("export", false)
        if (pickMode) {
            openPicker()
            return
        }
        if (exportMode) {
            openExport()
            return
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d + 0.5f).toInt()
        val blue = Color.parseColor(if (night) "#0A84FF" else "#007AFF")

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 23 && !night) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (night) intArrayOf(Color.parseColor("#000000"), Color.parseColor("#1C1C1E"))
                else intArrayOf(Color.parseColor("#F2F2F7"), Color.parseColor("#E5E5EA"))
            )
        }
        val title = TextView(this).apply {
            text = "WansForce"
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (night) Color.WHITE else Color.parseColor("#1C1C1E"))
        }
        val btnAcc = TextView(this).apply {
            text = "Buka Pengaturan Aksesibilitas"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blue, Color.parseColor(if (night) "#0060DF" else "#0A6CFF"))
            ).apply { cornerRadius = dp(32).toFloat() }
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.8f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                }
                false
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(36) })
        root.addView(btnAcc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!pickMode && !exportMode) FloatService.instance?.showBubble()
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json"))
        startActivityForResult(i, 1)
    }

    private fun openExport() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "WansForce-cadangan.json")
        startActivityForResult(i, 2)
    }

    private fun exportUri(uri: Uri) {
        try {
            val txt = Store.exportJson(this)
            contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
            val n = Store.load(this).size
            Toast.makeText(this, "Cadangan disimpan (" + n + " template)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan cadangan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fileName(uri: Uri): String {
        var n = "Template"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) n = c.getString(i) ?: n
            }
        } catch (e: Exception) {
        }
        return n.substringBeforeLast(".")
    }

    private fun importUri(uri: Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val clean = txt.replace("\r", "").replace("\uFEFF", "").trim()
            if (clean.isEmpty()) {
                Toast.makeText(this, "File kosong", Toast.LENGTH_SHORT).show()
                return
            }
            val restored = Store.importJson(this, clean)
            if (restored >= 0) {
                Toast.makeText(this, "Cadangan dipulihkan (" + restored + " template baru)", Toast.LENGTH_LONG).show()
                return
            }
            val name = fileName(uri)
            val all = Store.load(this)
            all.add(Tpl(name, clean))
            Store.save(this, all)
            val n = clean.split("\n").count { it.isNotBlank() }
            Toast.makeText(this, "Diimpor: " + name + " (" + n + " baris)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membaca file", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importUri(uri)
        }
        if (req == 2 && res == RESULT_OK) {
            val uri = data?.data
            if (uri != null) exportUri(uri)
        }
        if (pickMode || exportMode) finish()
    }
}

// ===== Tile panel cepat: munculkan / sembunyikan bubble =====
class BubbleTile : TileService() {
    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        val s = FloatService.instance
        if (s == null) {
            // layanan aksesibilitas belum aktif: buka apk
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        s.toggleBubble()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val s = FloatService.instance
        t.state = if (s != null && !s.isBubbleHidden()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}

// ===== Bubble melayang + pengetik otomatis =====
class FloatService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var bp: WindowManager.LayoutParams
    private var bubble: RingBubble? = null
    private var panel: View? = null
    private var editor: View? = null
    private val h = Handler(Looper.getMainLooper())

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    // status mengetik
    private var running = false
    private var lines: List<String> = emptyList()
    private var idx = 0
    private var curKey = ""
    private var needEnter = false
    private var closeTarget: View? = null
    private var hidden = false

    companion object {
        var instance: FloatService? = null
    }

    private var loop = false
    private var afterEnter = false
    private var warned = false
    private var waitingFocus = false
    private val pos = HashMap<String, Int>()
    private val proceed = Runnable {
        waitingFocus = false
        step()
    }

    // info mode angka: nilai awal & lebar digit (-1 = bukan mode angka)
    private var numStart = -1L
    private var numW = 0
    private var typingPkg = ""
    private var lastStepAt = 0L
    private var panelMode = ""
    private var panelInfo: TextView? = null
    private var turboNode: AccessibilityNodeInfo? = null
    private var locked = false
    private var lastBubbleDown = 0L

    // Layar mati / terkunci: semua tampilan melayang disembunyikan dan pengetikan dijeda
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> lockOverlay(true)
                Intent.ACTION_USER_PRESENT -> lockOverlay(false)
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!km.isKeyguardLocked) lockOverlay(false)
                }
            }
        }
    }

    private fun lockOverlay(on: Boolean) {
        locked = on
        if (on) {
            closePanel()
            closeEditor()
            hideCloseTarget()
            pause()
            bubble?.visibility = View.GONE
        } else if (!hidden) {
            bubble?.visibility = View.VISIBLE
        }
    }
    private var turboCount = 0
    private var lastUi = 0L

    // Penjaga: kalau pengetikan macet (misal pindah aplikasi / screenshot) status otomatis jadi pause
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val gap = SystemClock.uptimeMillis() - lastStepAt
            val limit = maxOf(3000L, Store.delayMs(this@FloatService) * 4L)
            if (gap > limit) {
                pause()
                toast("Pengetikan terhenti. Ketuk bubble lalu play untuk lanjut")
            } else {
                h.postDelayed(this, 1000)
            }
        }
    }

    private fun numMeta(t: Tpl): LongArray? {
        if (t.mode != 1 || t.text.contains("\n")) return null
        val m = NUM_PATTERN.find(t.text.trim()) ?: return null
        val d = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return longArrayOf(d.toLong(), d.length.toLong())
    }

    private fun shownNow(): String =
        if (numStart >= 0) {
            if (idx <= 0) "\u2013" else (numStart + idx - 1).toString().padStart(numW, '0')
        } else idx.toString()

    private fun shownMax(): String =
        if (numStart >= 0) "9".repeat(numW) else lines.size.toString()

    private fun infoText(): String {
        val hasInfo = curKey != "" && lines.isNotEmpty()
        if (!hasInfo) return "Template"
        val nowS = shownNow()
        val maxS = shownMax()
        val head1 = when {
            running -> "Mengetik " + nowS + " dari " + maxS
            idx >= lines.size -> if (numStart >= 0) "Selesai sampai " + maxS else "Selesai, " + lines.size + " ketikan"
            idx <= 0 && numStart >= 0 -> "Siap mulai dari " + numStart.toString().padStart(numW, '0')
            else -> "Berhenti di " + nowS + " dari " + maxS
        }
        return if (idx in 1..lines.size) head1 + "\nTerakhir: " + lines[idx - 1] else head1
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e != null && waitingFocus && e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            waitingFocus = false
            h.removeCallbacks(proceed)
            h.postDelayed(proceed, 30)
        }
    }

    // ----- helper tampilan -----
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun dark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun cPrimary(): Int = if (dark()) Color.WHITE else Color.parseColor("#1C1C1E")
    private fun cGray(): Int = Color.parseColor("#8E8E93")
    private fun cBtnGray(): Int = if (dark()) Color.parseColor("#48484A") else Color.parseColor("#C7C7CC")
    private fun cBlue(): Int = if (dark()) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF")
    private fun cGreen(): Int = if (dark()) Color.parseColor("#30D158") else Color.parseColor("#34C759")
    private fun cRed(): Int = Color.parseColor("#FF3B30")

    private fun glass(r: Float) = GradientDrawable().apply {
        setColor(if (dark()) Color.parseColor("#E61C1C1E") else Color.parseColor("#EBF2F2F7"))
        cornerRadius = r
        setStroke(dp(1), if (dark()) Color.parseColor("#33FFFFFF") else Color.parseColor("#80FFFFFF"))
    }

    private fun card(r: Float) = GradientDrawable().apply {
        setColor(if (dark()) Color.parseColor("#33FFFFFF") else Color.parseColor("#B3FFFFFF"))
        cornerRadius = r
    }

    private fun bubbleBg(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (active) setColor(if (dark()) Color.parseColor("#F01F2F4A") else Color.parseColor("#F5E6F0FF"))
        else setColor(if (dark()) Color.parseColor("#EE2C2C2E") else Color.parseColor("#F5FFFFFF"))
        setStroke(dp(1), if (dark()) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000"))
    }

    private fun txt(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun mlp(w: Int, hh: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, hh).apply { setMargins(l, t, r, b) }

    private fun circleBtn(label: String, color: Int, size: Int, onClick: () -> Unit): View {
        val kind = when (label) {
            "+" -> "plus"
            "TXT" -> "import"
            "\u2699" -> "gear"
            "\u270E" -> "edit"
            "\u21BA" -> "reset"
            "\u23F8" -> "pause"
            "\u25B6" -> "play"
            else -> ""
        }
        val lp = mlp(dp(size), dp(size), dp(6), 0, 0, 0)
        if (kind != "") {
            val fg = if (color == cBtnGray()) cPrimary() else Color.WHITE
            return IconBtn(this, kind, color, fg).apply {
                layoutParams = lp
                setOnClickListener { onClick() }
            }
        }
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = if (label.length > 1) 10f else 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun setSel(v: TextView, sel: Boolean) {
        v.setTextColor(if (sel) Color.WHITE else cPrimary())
        (v.background as? GradientDrawable)?.setColor(if (sel) cBlue() else cBtnGray())
    }

    private fun pill(label: String, bgc: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(fg)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply {
            setColor(bgc)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String, multi: Boolean) = EditText(this).apply {
        hint = hintText
        setHintTextColor(cGray())
        setTextColor(cPrimary())
        textSize = 16f
        background = card(dp(12).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multi) {
            minLines = 5
            maxLines = 8
            gravity = Gravity.TOP
        } else {
            setSingleLine()
        }
    }

    private fun lp(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun blur(p: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 31) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            p.blurBehindRadius = 40
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun keyOf(t: Tpl): String = t.name + "|" + t.text.hashCode().toString() + "|" + t.mode.toString()

    private fun countLines(s: String): Int = s.split("\n").count { it.isNotBlank() }

    private fun msLabel(ms: Int): String =
        if (ms == 0) "Turbo (tanpa jeda, secepat mungkin)" else ms.toString() + " ms per ketikan"

    private fun speedLabel(v: Int): String =
        if (v == 0) "Turbo (tanpa jeda, secepat mungkin)" else (v * 20).toString() + " ms per baris"

    // ----- bubble -----
    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bp = lp().apply {
            width = dp(64)
            height = dp(64)
            x = if (Store.bubbleX(this@FloatService) >= 0) Store.bubbleX(this@FloatService) else dp(8)
            y = if (Store.bubbleY(this@FloatService) >= 0) Store.bubbleY(this@FloatService) else dp(160)
        }

        val b = RingBubble(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(4), 0, dp(4), 0)
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = bp.x
                        sy = bp.y
                        tx = ev.rawX
                        ty = ev.rawY
                        moved = false
                        lastBubbleDown = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - tx).toInt()
                        val dy = (ev.rawY - ty).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                        if (moved) {
                            bp.x = sx + dx
                            bp.y = sy + dy
                            wm.updateViewLayout(v, bp)
                            closePanel()
                            showCloseTarget()
                            closeTarget?.scaleX = if (overTarget(ev.rawX, ev.rawY)) 1.3f else 1f
                            closeTarget?.scaleY = if (overTarget(ev.rawX, ev.rawY)) 1.3f else 1f
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val over = moved && overTarget(ev.rawX, ev.rawY)
                        hideCloseTarget()
                        if (over) {
                            hideBubble()
                        } else if (!moved) {
                            toggle()
                        } else {
                            snapBubble(v)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> hideCloseTarget()
                }
                return true
            }
        })
        wm.addView(b, bp)
        bubble = b
        refreshBubble()

        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(lockReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(lockReceiver, f)
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) lockOverlay(true)
    }

    // ----- menempel ke tepi layar + simpan posisi -----
    private fun snapBubble(v: View) {
        val dm = resources.displayMetrics
        val w = dp(64)
        val tx = if (bp.x + w / 2 < dm.widthPixels / 2) dp(8) else dm.widthPixels - w - dp(8)
        bp.y = bp.y.coerceIn(dp(24), dm.heightPixels - w - dp(48))
        val from = bp.x
        ValueAnimator.ofInt(from, tx).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bp.x = it.animatedValue as Int
                try {
                    wm.updateViewLayout(v, bp)
                } catch (e: Exception) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    Store.setBubblePos(this@FloatService, bp.x, bp.y)
                }
            })
            start()
        }
    }

    // ----- tanda selesai: cincin berdenyut + getar halus -----
    private fun celebrate() {
        val b = bubble ?: return
        b.pulseRing()
        val type = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        b.performHapticFeedback(type)
    }

    // ----- tutup bubble: tarik ke bawah tengah layar -----
    private fun overTarget(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        return y > dm.heightPixels - dp(170) && Math.abs(x - dm.widthPixels / 2f) < dp(90)
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val t = IconBtn(this, "close", cRed(), Color.WHITE).apply { alpha = 0.9f }
        val p = WindowManager.LayoutParams(
            dp(64),
            dp(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = dp(60)
        try {
            wm.addView(t, p)
            closeTarget = t
        } catch (e: Exception) {
        }
    }

    private fun hideCloseTarget() {
        closeTarget?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        closeTarget = null
    }

    private fun hideBubble() {
        pause()
        closePanel()
        closeEditor()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        hidden = true
        toast("Bubble ditutup. Munculkan lagi lewat tile WansForce di panel cepat atau buka apk.")
    }

    fun isBubbleHidden(): Boolean = hidden

    fun toggleBubble() {
        if (hidden) showBubble() else hideBubble()
    }

    fun showBubble() {
        if (!hidden) return
        val b = bubble ?: return
        bp.x = if (Store.bubbleX(this) >= 0) Store.bubbleX(this) else dp(8)
        bp.y = if (Store.bubbleY(this) >= 0) Store.bubbleY(this) else dp(160)
        b.visibility = if (locked) View.GONE else View.VISIBLE
        try {
            wm.addView(b, bp)
        } catch (e: Exception) {
        }
        hidden = false
        refreshBubble()
    }

    private fun setBubbleCount(l: RingBubble) {
        val maxS = shownMax()
        val w = maxS.length
        l.textSize = when {
            w <= 3 -> 15f
            w <= 5 -> 12f
            w <= 7 -> 10f
            else -> 8.5f
        }
        val top = shownNow()
        val bottom = (if (loop) "\u21BB " else "") + "/" + maxS
        val sp = SpannableString(top + "\n" + bottom)
        sp.setSpan(RelativeSizeSpan(0.72f), top.length + 1, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        l.text = sp
    }

    private fun refreshBubble() {
        val l = bubble ?: return
        l.ringColor = cBlue()
        l.trackColor = if (dark()) Color.parseColor("#44FFFFFF") else Color.parseColor("#26000000")
        if (curKey != "" && lines.isNotEmpty()) {
            // outline mengikuti progres: idx / total. Selesai (mis. 999/999) = lingkaran biru penuh.
            l.progress = idx.toFloat() / lines.size.toFloat()
            l.showTrack = true
            setBubbleCount(l)
            l.setTextColor(if (running) cBlue() else cPrimary())
            l.background = bubbleBg(running)
        } else {
            l.progress = 0f
            l.showTrack = false
            l.textSize = 20f
            l.text = "T"
            l.setTextColor(cBlue())
            l.background = bubbleBg(false)
        }
        l.invalidate()
        panelInfo?.text = infoText()
    }

    private fun toggle() {
        if (editor != null) {
            closeEditor()
            return
        }
        if (panel != null) closePanel() else openPanel("list")
    }

    // ----- panel daftar template / pengaturan -----
    private fun openPanel(mode: String) {
        closePanel()
        panelMode = mode
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (mode == "set") {
            box.addView(txt("Kecepatan mengetik", 20f, cPrimary(), true))
            val lbl = txt(msLabel(Store.delayMs(this)), 14f, cBlue(), true).apply {
                setPadding(0, dp(10), 0, dp(4))
            }
            box.addView(lbl)
            val sb = SeekBar(this).apply {
                max = 100
                progress = minOf(100, Store.delayMs(this@FloatService) / 20)
                progressTintList = ColorStateList.valueOf(cBlue())
                thumbTintList = ColorStateList.valueOf(cBlue())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        if (!u) return
                        lbl.text = msLabel(p * 20)
                        Store.setDelayMs(this@FloatService, p * 20)
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(sb, mlp(MATCH, WRAP, 0, dp(4), 0, 0))
            box.addView(txt("Kiri = Turbo, kanan = lambat. Kalau ada pesan terlewat, geser sedikit ke kanan.", 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(4), 0, 0))
            val manual = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            manual.addView(pill("Isi milidetik manual", cBtnGray(), cPrimary()) {
                closePanel()
                showDelayEditor()
            })
            box.addView(manual, mlp(MATCH, WRAP, 0, dp(10), 0, 0))
            box.addView(txt("Cara Enter", 16f, cPrimary(), true), mlp(WRAP, WRAP, 0, dp(14), 0, 0))
            val em = Store.enterMode(this)
            val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Enter", "Kolom", "Kirim").forEachIndexed { i, nm ->
                modes.addView(pill(nm, if (em == i) cBlue() else cBtnGray(), if (em == i) Color.WHITE else cPrimary()) {
                    Store.setEnterMode(this, i)
                    openPanel("set")
                })
            }
            box.addView(modes, mlp(MATCH, WRAP, 0, dp(6), 0, 0))
            box.addView(
                txt("Enter = tombol enter keyboard. Kolom = pindah ke kolom berikutnya. Kirim = ketuk tombol Kirim/Send di layar.", 12f, cGray(), false),
                mlp(WRAP, WRAP, 0, dp(4), 0, 0)
            )
            box.addView(txt("Cara tempel", 16f, cPrimary(), true), mlp(WRAP, WRAP, 0, dp(14), 0, 0))
            val pm = Store.pasteMode(this)
            val pastes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Clipboard", "Langsung").forEachIndexed { i, nm ->
                pastes.addView(pill(nm, if (pm == i) cBlue() else cBtnGray(), if (pm == i) Color.WHITE else cPrimary()) {
                    Store.setPasteMode(this, i)
                    openPanel("set")
                })
            }
            box.addView(pastes, mlp(MATCH, WRAP, 0, dp(6), 0, 0))
            box.addView(
                txt("Langsung = tanpa clipboard, jadi tidak ada popup 'disalin/ditempel' dan lebih ringan. Kalau teks tidak masuk di aplikasimu, kembali ke Clipboard.", 12f, cGray(), false),
                mlp(WRAP, WRAP, 0, dp(4), 0, 0)
            )
            box.addView(txt("Cadangan template", 16f, cPrimary(), true), mlp(WRAP, WRAP, 0, dp(14), 0, 0))
            val bk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bk.addView(pill("Ekspor", cBtnGray(), cPrimary()) { exportFile() })
            bk.addView(pill("Pulihkan", cBtnGray(), cPrimary()) { importFile() })
            box.addView(bk, mlp(MATCH, WRAP, 0, dp(6), 0, 0))
            box.addView(
                txt("Ekspor menyimpan semua template ke satu file .json. Pulihkan membaca file itu lagi, template yang sama dilewati.", 12f, cGray(), false),
                mlp(WRAP, WRAP, 0, dp(4), 0, 0)
            )
            val done = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            done.addView(pill("Selesai", cBlue(), Color.WHITE) { openPanel("list") })
            box.addView(done, mlp(MATCH, WRAP, 0, dp(14), 0, 0))
        } else {
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val hasInfo = curKey != "" && lines.isNotEmpty()
            val infoView = txt(infoText(), if (hasInfo) 15f else 20f, cPrimary(), true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            panelInfo = infoView
            head.addView(infoView, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(circleBtn("+", cBlue(), 36) {
                closePanel()
                showEditor(-1)
            })
            head.addView(circleBtn("TXT", cBtnGray(), 36) { importFile() })
            head.addView(circleBtn("\u2699", cBtnGray(), 36) { openPanel("set") })
            box.addView(head)

            val items = Store.load(this)
            if (items.isEmpty()) {
                box.addView(
                    txt("Belum ada template. Ketuk + untuk membuat atau TXT untuk impor file.", 14f, cGray(), false),
                    mlp(MATCH, WRAP, 0, dp(12), 0, 0)
                )
            }
            items.forEachIndexed { i, t ->
                val key = keyOf(t)
                val total = expand(t.text, t.mode).size
                val done = pos[key] ?: 0
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = card(dp(18).toFloat())
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(txt(t.name, 16f, cPrimary(), true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val tag = (if (t.mode == 1) "Angka" else "Standar") + (if (t.loop) " \u00B7 ulang" else "")
                val nm = numMeta(t)
                val nw = nm?.get(1)?.toInt() ?: 0
                val sub = tag + " \u00B7 " + (
                    if (done in 1 until total) {
                        (if (nm != null) (nm[0] + done - 1).toString().padStart(nw, '0') + "/" + "9".repeat(nw) else done.toString() + "/" + total.toString()) + ", lanjut"
                    } else if (nm != null) {
                        nm[0].toString().padStart(nw, '0') + " - " + "9".repeat(nw)
                    } else total.toString() + " baris"
                    )
                col.addView(txt(sub, 12f, cGray(), false))
                col.setOnLongClickListener {
                    closePanel()
                    showJump(t, key, total)
                    true
                }
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(circleBtn("\u270E", cBtnGray(), 36) {
                    closePanel()
                    showEditor(i)
                })
                if (done > 0) {
                    row.addView(circleBtn("\u21BA", cBtnGray(), 36) {
                        if (running && curKey == key) pause()
                        pos[key] = 0
                        if (curKey == key) {
                            idx = 0
                            refreshBubble()
                        }
                        openPanel("list")
                    })
                }
                val isRun = running && curKey == key
                row.addView(circleBtn(if (isRun) "\u23F8" else "\u25B6", if (isRun) cRed() else cGreen(), 40) {
                    if (isRun) {
                        pause()
                        openPanel("list")
                    } else {
                        closePanel()
                        play(t)
                    }
                })
                box.addView(row, mlp(MATCH, WRAP, 0, dp(8), 0, 0))
            }
            if (items.isNotEmpty()) {
                box.addView(txt("Tahan nama template untuk lompat ke ketikan tertentu", 11f, cGray(), false), mlp(WRAP, WRAP, 0, dp(10), 0, 0))
            }
        }

        // Panel ditaruh di bawah bubble kalau muat, kalau tidak di atasnya, jadi bubble tidak pernah tertutup
        val dm = resources.displayMetrics
        val below = dm.heightPixels - (bp.y + dp(64)) - dp(16)
        val above = bp.y - dp(32)
        val placeBelow = below >= dp(408) || below >= above
        val avail = if (placeBelow) below else above
        val maxH = (avail - dp(28)).coerceIn(dp(120), dp(380))

        val sv = MaxScroll(this, maxH)
        sv.addView(box)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        wrap.addView(sv)

        val sw = dm.widthPixels
        val pw = minOf(dp(300), sw - dp(16))
        wrap.measure(
            View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ph = wrap.measuredHeight
        val pp = lp().apply {
            width = pw
            x = maxOf(0, minOf(bp.x, sw - pw - dp(8)))
            y = if (placeBelow) bp.y + dp(64) + dp(8) else maxOf(dp(24), bp.y - ph - dp(8))
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        // ketuk di luar panel = tutup panel (kecuali yang diketuk adalah bubble, itu sudah ditangani bubble)
        wrap.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                v.postDelayed({
                    if (panel === v && SystemClock.uptimeMillis() - lastBubbleDown > 350) closePanel()
                }, 120)
            }
            false
        }
        blur(pp)
        wm.addView(wrap, pp)
        panel = wrap
    }

    private fun closePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        panel = null
        panelMode = ""
        panelInfo = null
    }

    // ----- editor template (nama + isi) -----
    private fun showEditor(index: Int) {
        closeEditor()
        val items = Store.load(this)
        val cur: Tpl? = if (index >= 0 && index < items.size) items[index] else null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(txt(if (cur == null) "Template baru" else "Edit template", 20f, cPrimary(), true))
        val etName = field("Nama", false)
        etName.setText(cur?.name ?: "")
        val etText = field("Isi (satu baris = satu ketikan; mode Angka: tulis angka awal, contoh pesan000)", true)
        etText.setText(cur?.text ?: "")
        box.addView(etName, mlp(MATCH, WRAP, 0, dp(12), 0, 0))
        box.addView(etText, mlp(MATCH, WRAP, 0, dp(8), 0, 0))

        var selMode = cur?.mode ?: 0
        var selLoop = cur?.loop ?: false
        val pStd = pill("Standar", cBtnGray(), cPrimary()) {}
        val pNum = pill("Angka", cBtnGray(), cPrimary()) {}
        val pOnce = pill("Sekali", cBtnGray(), cPrimary()) {}
        val pLoop = pill("Ulang \u21BB", cBtnGray(), cPrimary()) {}
        fun refreshSel() {
            setSel(pStd, selMode == 0)
            setSel(pNum, selMode == 1)
            setSel(pOnce, !selLoop)
            setSel(pLoop, selLoop)
            etText.hint = if (selMode == 1) "Satu baris berisi angka, contoh: pesan000 (jalan 000 sampai 999)" else "Isi (satu baris = satu ketikan)"
        }
        pStd.setOnClickListener { selMode = 0; refreshSel() }
        pNum.setOnClickListener { selMode = 1; refreshSel() }
        pOnce.setOnClickListener { selLoop = false; refreshSel() }
        pLoop.setOnClickListener { selLoop = true; refreshSel() }
        refreshSel()
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(pStd)
        modeRow.addView(pNum)
        val loopRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loopRow.addView(pOnce)
        loopRow.addView(pLoop)
        box.addView(txt("Mode", 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(10), 0, 0))
        box.addView(modeRow, mlp(MATCH, WRAP, 0, dp(4), 0, 0))
        box.addView(txt("Putaran", 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(8), 0, 0))
        box.addView(loopRow, mlp(MATCH, WRAP, 0, dp(4), 0, 0))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("Batal", cBtnGray(), cPrimary()) {
            closeEditor()
            openPanel("list")
        })
        if (cur != null) {
            btns.addView(pill("Hapus", cRed(), Color.WHITE) {
                val all = Store.load(this)
                if (index >= 0 && index < all.size) {
                    pos.remove(keyOf(all[index]))
                    all.removeAt(index)
                    Store.save(this, all)
                }
                closeEditor()
                openPanel("list")
            })
        }
        btns.addView(pill("Simpan", cBlue(), Color.WHITE) {
            val nm = etName.text.toString().trim()
            val tx = etText.text.toString().replace("\r", "").trim()
            if (tx.isEmpty()) {
                toast("Isi template masih kosong")
                return@pill
            }
            if (selMode == 1 && (tx.contains("\n") || !NUM_PATTERN.containsMatchIn(tx))) {
                toast("Mode Angka: tulis satu baris yang berisi angka, contoh pesan000")
                return@pill
            }
            val all = Store.load(this)
            val name = if (nm.isEmpty()) "Template " + (all.size + 1).toString() else nm
            if (cur != null && index >= 0 && index < all.size) {
                pos.remove(keyOf(all[index]))
                all[index] = Tpl(name, tx, selMode, selLoop)
            } else {
                all.add(Tpl(name, tx, selMode, selLoop))
            }
            Store.save(this, all)
            closeEditor()
            openPanel("list")
        })
        box.addView(btns, mlp(MATCH, WRAP, 0, dp(14), 0, 0))

        val sw = resources.displayMetrics.widthPixels
        val ep = WindowManager.LayoutParams(
            minOf(dp(320), sw - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        ep.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ep.y = dp(60)
        ep.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        blur(ep)
        wm.addView(box, ep)
        editor = box
    }

    private fun closeEditor() {
        editor?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        editor = null
    }

    private fun exportFile() {
        closePanel()
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("export", true)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // Lompat ke ketikan ke-N (mulai dari 1) tanpa mengubah template
    private fun showJump(t: Tpl, key: String, total: Int) {
        closeEditor()
        val cur = (pos[key] ?: 0).coerceIn(0, maxOf(total - 1, 0))
        val nm = numMeta(t)
        val nw = nm?.get(1)?.toInt() ?: 0
        val lo = nm?.get(0) ?: 1L
        val hi = if (nm != null) ("9".repeat(nw)).toLong() else total.toLong()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(txt(if (nm != null) "Lompat ke angka" else "Lompat ke ketikan", 20f, cPrimary(), true))
        val rangeTxt = if (nm != null) lo.toString().padStart(nw, '0') + " sampai " + hi.toString() else "1 sampai " + total
        box.addView(txt(t.name + " \u00B7 " + rangeTxt, 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(2), 0, 0))
        val et = field(if (nm != null) "Angka" else "Nomor ketikan", false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((lo + cur).toString())
            setSelection(text.length)
        }
        box.addView(et, mlp(MATCH, WRAP, 0, dp(12), 0, 0))
        val preview = txt("", 13f, cBlue(), true)
        box.addView(preview, mlp(WRAP, WRAP, 0, dp(8), 0, 0))
        val src = expand(t.text, t.mode)
        // indeks (mulai 0) dari angka/nomor yang diketik
        fun n(): Int = ((et.text.toString().toLongOrNull() ?: lo).coerceIn(lo, maxOf(hi, lo)) - lo).toInt()
        fun upd() {
            val k = n()
            preview.text = if (src.isNotEmpty()) "Mulai dari: " + src[k] else ""
            preview.maxLines = 1
            preview.ellipsize = TextUtils.TruncateAt.END
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { upd() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        upd()
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("Batal", cBtnGray(), cPrimary()) {
            closeEditor()
            openPanel("list")
        })
        btns.addView(pill("Lompat", cBlue(), Color.WHITE) {
            val k = n()
            if (running && curKey == key) pause()
            pos[key] = k
            if (curKey == key) {
                idx = k
                refreshBubble()
            }
            closeEditor()
            openPanel("list")
        })
        box.addView(btns, mlp(MATCH, WRAP, 0, dp(14), 0, 0))

        val sw = resources.displayMetrics.widthPixels
        val ep = WindowManager.LayoutParams(
            minOf(dp(320), sw - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        ep.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ep.y = dp(60)
        ep.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        blur(ep)
        wm.addView(box, ep)
        editor = box
    }

    // Isi jeda per ketikan secara manual (ms). 0 = Turbo.
    private fun showDelayEditor() {
        closeEditor()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(txt("Jeda per ketikan", 20f, cPrimary(), true))
        box.addView(txt("Dalam milidetik (ms), 0 sampai 5000. Isi 0 untuk Turbo.", 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(2), 0, 0))
        val et = field("Milidetik", false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.delayMs(this@FloatService).toString())
            setSelection(text.length)
        }
        box.addView(et, mlp(MATCH, WRAP, 0, dp(12), 0, 0))
        val preview = txt("", 13f, cBlue(), true)
        box.addView(preview, mlp(WRAP, WRAP, 0, dp(8), 0, 0))
        fun v(): Int = (et.text.toString().toLongOrNull() ?: 0L).coerceIn(0L, 5000L).toInt()
        fun upd() {
            val x = v()
            preview.text = if (x == 0) "Turbo: secepat yang bisa diterima aplikasi" else "Sekitar " + (1000 / x) + " ketikan per detik"
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { upd() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        upd()

        fun chips(vararg items: Int): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (x in items) {
                row.addView(pill(if (x == 0) "Turbo" else x.toString(), cBtnGray(), cPrimary()) {
                    et.setText(x.toString())
                    et.setSelection(et.text.length)
                })
            }
            return row
        }
        box.addView(chips(0, 5, 10), mlp(MATCH, WRAP, 0, dp(8), 0, 0))
        box.addView(chips(20, 50, 100), mlp(MATCH, WRAP, 0, dp(6), 0, 0))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("Batal", cBtnGray(), cPrimary()) {
            closeEditor()
            openPanel("set")
        })
        btns.addView(pill("Simpan", cBlue(), Color.WHITE) {
            Store.setDelayMs(this, v())
            closeEditor()
            openPanel("set")
        })
        box.addView(btns, mlp(MATCH, WRAP, 0, dp(14), 0, 0))

        val sw = resources.displayMetrics.widthPixels
        val ep = WindowManager.LayoutParams(
            minOf(dp(320), sw - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        ep.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ep.y = dp(60)
        ep.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        blur(ep)
        wm.addView(box, ep)
        editor = box
    }

    private fun importFile() {
        closePanel()
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("pick", true)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ----- mengetik otomatis -----
    // Teks isi kolom yang sebenarnya: bukan petunjuk (hint) dan bukan sisa sebelum terkirim.
    private fun currentText(node: AccessibilityNodeInfo): String {
        if (afterEnter) return ""
        if (node.isShowingHintText) return ""
        val s = node.text?.toString() ?: ""
        val hint = node.hintText?.toString()
        if (hint != null && hint == s) return ""
        return s
    }

    private fun paste(node: AccessibilityNodeInfo, t: String): Boolean {
        val before = currentText(node)
        afterEnter = false
        try {
            if (Store.pasteMode(this) == 1) {
                val a = Bundle()
                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + t)
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)) return true
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("tpl", t)
            if (Build.VERSION.SDK_INT >= 33) {
                val ex = android.os.PersistableBundle()
                ex.putBoolean("android.content.extra.IS_SENSITIVE", true)
                clip.description.extras = ex
            }
            cm.setPrimaryClip(clip)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + t)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            return false
        }
    }

    // Turbo: langsung isi teks (tanpa clipboard). Kalau gagal, jatuh ke cara biasa.
    private fun pasteTurbo(node: AccessibilityNodeInfo, t: String): Boolean {
        afterEnter = false
        try {
            val a = Bundle()
            a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)) return true
        } catch (e: Exception) {
        }
        return paste(node, t)
    }

    // Tampilan bubble cukup diperbarui ~10x per detik supaya tidak memperlambat pengetikan
    private fun refreshThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastUi >= 100) {
            lastUi = now
            refreshBubble()
        }
    }

    private fun clickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (key in listOf("Kirim", "Send")) {
            val found = root.findAccessibilityNodeInfosByText(key) ?: continue
            for (f in found) {
                var x: AccessibilityNodeInfo? = f
                while (x != null && !x.isClickable) x = x.parent
                if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    private fun pressEnter(given: AccessibilityNodeInfo? = null) {
        needEnter = false
        val n = given ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var ok = false
        try {
            when (Store.enterMode(this)) {
                1 -> {
                    val nx = n?.focusSearch(View.FOCUS_FORWARD)
                    ok = nx?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
                }
                2 -> ok = clickSend()
                else -> if (Build.VERSION.SDK_INT >= 30) {
                    ok = n?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        afterEnter = ok
        if (!ok && !warned) {
            warned = true
            toast("Enter gagal. Coba ganti Cara Enter di pengaturan (ikon gerigi)")
        }
    }

    // Mode Angka: satu baris berisi angka (pesan000 atau pesan{000}) diubah jadi deretan 000, 001, ... 999.
    // Angka itu = angka awal, jumlah digit = lebar angka (maksimal 9 digit).
    // Daftar dibuat malas: angka dihitung saat dibutuhkan, jadi 9 digit pun tidak memenuhi memori.
    private fun expand(text: String, mode: Int): List<String> {
        val t = text.trim()
        val m = NUM_PATTERN.find(t)
        if (mode == 1 && m != null && !t.contains("\n")) {
            val digits = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val w = digits.length
            var max = 1L
            repeat(w) { max *= 10L }
            val start = digits.toLong()
            val pre = t.substring(0, m.range.first)
            val suf = t.substring(m.range.last + 1)
            val count = (max - start).toInt()
            return object : AbstractList<String>() {
                override val size: Int get() = count
                override fun get(index: Int): String =
                    pre + (start + index).toString().padStart(w, '0') + suf
            }
        }
        return t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun play(t: Tpl) {
        val repeat = t.loop
        val ls = expand(t.text, t.mode)
        if (ls.isEmpty()) return
        if ((ls.size > 1 || repeat) && Build.VERSION.SDK_INT < 30 && Store.enterMode(this) == 0) {
            toast("Enter otomatis butuh Android 11+")
            return
        }
        h.removeCallbacksAndMessages(null)
        waitingFocus = false
        loop = repeat
        warned = false
        afterEnter = false
        val key = keyOf(t)
        if (key != curKey) needEnter = false
        lines = ls
        curKey = key
        idx = pos[key] ?: 0
        if (idx >= ls.size) idx = 0
        running = true
        typingPkg = ""
        turboNode = null
        turboCount = 0
        val nm = numMeta(t)
        numStart = nm?.get(0) ?: -1L
        numW = nm?.get(1)?.toInt() ?: 0
        lastStepAt = SystemClock.uptimeMillis()
        h.postDelayed(watchdog, 1000)
        refreshBubble()
        if (needEnter) {
            pressEnter()
            h.postDelayed({ step() }, 250)
        } else {
            h.postDelayed({ step() }, 150)
        }
    }

    private fun pause() {
        running = false
        waitingFocus = false
        h.removeCallbacksAndMessages(null)
        refreshBubble()
        if (panel != null && panelMode == "list") openPanel("list")
    }

    private fun finish() {
        running = false
        needEnter = false
        waitingFocus = false
        h.removeCallbacksAndMessages(null)
        pos[curKey] = 0
        refreshBubble()
        if (panel != null && panelMode == "list") openPanel("list")
    }

    private fun step() {
        if (!running) return
        lastStepAt = SystemClock.uptimeMillis()
        try {
            stepInner()
        } catch (e: Exception) {
            pause()
            toast("Pengetikan dijeda karena gangguan. Ketuk play untuk lanjut")
        }
    }

    private fun stepInner() {
        val ms = Store.delayMs(this)
        val turbo = ms == 0
        val text = lines[idx]

        // Turbo memakai ulang kolom ketik yang sama dan memeriksa ulang tiap 10 ketikan
        var node: AccessibilityNodeInfo? = null
        var cached = false
        if (turbo && turboNode != null && turboCount % 10 != 0 && Store.enterMode(this) != 1) {
            node = turboNode
            cached = true
        }
        turboCount++
        if (node == null) {
            val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f == null) {
                pause()
                toast("Kolom ketik hilang, dijeda. Klik kolom ketik lalu ketuk bubble > play")
                return
            }
            val pkg = f.packageName?.toString() ?: ""
            if (typingPkg == "") {
                typingPkg = pkg
            } else if (pkg != "" && pkg != typingPkg) {
                pause()
                toast("Pindah aplikasi, dijeda. Kembali ke kolom ketik lalu ketuk play")
                return
            }
            node = f
            turboNode = if (turbo) f else null
        }

        var cur: AccessibilityNodeInfo = node ?: return
        var ok = if (turbo) pasteTurbo(cur, text) else paste(cur, text)
        if (!ok && cached) {
            // kolom lama sudah tidak valid, cari ulang sekali
            val fresh = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (fresh != null) {
                cur = fresh
                turboNode = fresh
                ok = pasteTurbo(fresh, text)
            }
        }
        if (!ok) {
            turboNode = null
            pause()
            toast("Teks tidak masuk, dijeda. Klik kolom ketik lalu ketuk play")
            return
        }
        val target: AccessibilityNodeInfo = cur

        idx++
        pos[curKey] = idx
        if (idx >= lines.size) {
            if (loop) {
                idx = 0
                pos[curKey] = 0
            } else {
                // item terakhir tetap di-Enter dulu, baru selesai
                refreshBubble()
                val last = ms * 3 / 10
                h.postDelayed({
                    pressEnter(target)
                    h.postDelayed({
                        finish()
                        celebrate()
                    }, 150)
                }, maxOf(last, 20).toLong())
                return
            }
        }
        needEnter = true
        refreshThrottled()
        if (turbo) {
            h.post {
                pressEnter(target)
                h.post { step() }
            }
        } else {
            h.postDelayed({
                pressEnter(target)
                h.postDelayed({ step() }, (ms * 7 / 10).toLong())
            }, (ms * 3 / 10).toLong())
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(lockReceiver)
        } catch (e: Exception) {
        }
        h.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        hideCloseTarget()
        closePanel()
        closeEditor()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
        super.onDestroy()
    }
}
