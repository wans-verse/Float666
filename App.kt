package com.wans.tpl

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
class Tpl(val name: String, val text: String)

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
                    out.add(Tpl(o.getString("n"), o.getString("c")))
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

    fun save(c: Context, l: List<Tpl>) {
        val a = JSONArray()
        l.forEach {
            val o = JSONObject()
            o.put("n", it.name)
            o.put("c", it.text)
            a.put(o)
        }
        c.getSharedPreferences("tpl", 0).edit().putString("data", a.toString()).apply()
    }

    fun speed(c: Context): Int = c.getSharedPreferences("tpl", 0).getInt("speed", 35)

    fun setSpeed(c: Context, v: Int) {
        c.getSharedPreferences("tpl", 0).edit().putInt("speed", v).apply()
    }
}

// ScrollView dengan tinggi maksimal
class MaxScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

// ===== Halaman apk (aktivasi + pemilih file) =====
class MainActivity : Activity() {
    private var pickMode = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pickMode = intent.getBooleanExtra("pick", false)
        if (pickMode) {
            openPicker()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        val info = TextView(this).apply {
            textSize = 16f
            text = "Template Melayang\n\n1) Aktifkan 'Template Melayang' di Aksesibilitas.\n2) Bubble akan muncul, bisa digeser.\n3) Atur template (nama, isi, kecepatan) langsung dari bubble.\n4) Klik kolom di form, ketuk bubble, pilih template, tekan play.\n\nKetuk bubble saat berjalan untuk pause."
        }
        val btnAcc = Button(this).apply {
            text = "Buka Pengaturan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnImp = Button(this).apply {
            text = "Impor template dari file .txt"
            setOnClickListener { openPicker() }
        }
        root.addView(info)
        root.addView(btnAcc)
        root.addView(btnImp)
        setContentView(root)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/*"
        startActivityForResult(i, 1)
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
        if (pickMode) finish()
    }
}

// ===== Bubble melayang + pengetik otomatis =====
class FloatService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var bp: WindowManager.LayoutParams
    private var bubble: TextView? = null
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
    private var loop = false
    private var waitingFocus = false
    private val pos = HashMap<String, Int>()
    private val proceed = Runnable {
        waitingFocus = false
        step()
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
        if (active) setColor(cBlue()) else setColor(if (dark()) Color.parseColor("#EE2C2C2E") else Color.parseColor("#F5FFFFFF"))
        cornerRadius = dp(28).toFloat()
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

    private fun circleBtn(label: String, color: Int, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = if (label.length > 1) 10f else 16f
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = mlp(dp(size), dp(size), dp(6), 0, 0, 0)
        setOnClickListener { onClick() }
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

    private fun keyOf(t: Tpl): String = t.name + "|" + t.text.hashCode().toString()

    private fun countLines(s: String): Int = s.split("\n").count { it.isNotBlank() }

    private fun speedLabel(v: Int): String =
        if (v == 0) "Super Cepat (otomatis pindah kolom)" else (v * 20).toString() + " ms per baris"

    // ----- bubble -----
    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bp = lp().apply {
            x = dp(8)
            y = dp(160)
        }

        val b = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(56)
            minHeight = dp(56)
            setPadding(dp(14), 0, dp(14), 0)
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
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        if (running) pause() else toggle()
                    }
                }
                return true
            }
        })
        wm.addView(b, bp)
        bubble = b
        refreshBubble()
    }

    private fun refreshBubble() {
        val l = bubble ?: return
        if (running) {
            l.text = (if (loop) "\u23F8\u21BB " else "\u23F8 ") + idx.toString() + "/" + lines.size.toString()
            l.setTextColor(Color.WHITE)
            l.background = bubbleBg(true)
        } else if (curKey != "" && lines.isNotEmpty()) {
            l.text = idx.toString() + "/" + lines.size.toString()
            l.setTextColor(cPrimary())
            l.background = bubbleBg(false)
        } else {
            l.text = "T"
            l.setTextColor(cBlue())
            l.background = bubbleBg(false)
        }
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (mode == "set") {
            box.addView(txt("Kecepatan mengetik", 20f, cPrimary(), true))
            val lbl = txt(speedLabel(Store.speed(this)), 14f, cBlue(), true).apply {
                setPadding(0, dp(10), 0, dp(4))
            }
            box.addView(lbl)
            val sb = SeekBar(this).apply {
                max = 100
                progress = Store.speed(this@FloatService)
                progressTintList = ColorStateList.valueOf(cBlue())
                thumbTintList = ColorStateList.valueOf(cBlue())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        lbl.text = speedLabel(p)
                        Store.setSpeed(this@FloatService, p)
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(sb, mlp(MATCH, WRAP, 0, dp(4), 0, 0))
            box.addView(txt("Kiri = Super Cepat, kanan = lambat", 12f, cGray(), false), mlp(WRAP, WRAP, 0, dp(4), 0, 0))
            val done = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            done.addView(pill("Selesai", cBlue(), Color.WHITE) { openPanel("list") })
            box.addView(done, mlp(MATCH, WRAP, 0, dp(14), 0, 0))
        } else {
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(txt("Template", 20f, cPrimary(), true), LinearLayout.LayoutParams(0, WRAP, 1f))
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
                val total = countLines(t.text)
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
                val sub = if (done in 1 until total) done.toString() + "/" + total.toString() + " baris, lanjut" else total.toString() + " baris"
                col.addView(txt(sub, 12f, cGray(), false))
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(circleBtn("\u270E", cBtnGray(), 36) {
                    closePanel()
                    showEditor(i)
                })
                row.addView(circleBtn("\u21BB", cBlue(), 36) {
                    closePanel()
                    play(t, true)
                })
                row.addView(circleBtn("\u25B6", cGreen(), 40) {
                    closePanel()
                    play(t)
                })
                box.addView(row, mlp(MATCH, WRAP, 0, dp(8), 0, 0))
            }
        }

        val sv = MaxScroll(this, dp(380))
        sv.addView(box)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(dp(28).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        wrap.addView(sv)

        val sw = resources.displayMetrics.widthPixels
        val pw = minOf(dp(300), sw - dp(16))
        val pp = lp().apply {
            width = pw
            x = maxOf(0, minOf(bp.x, sw - pw - dp(8)))
            y = bp.y + dp(64)
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
        val etText = field("Isi (satu baris = satu ketikan)", true)
        etText.setText(cur?.text ?: "")
        box.addView(etName, mlp(MATCH, WRAP, 0, dp(12), 0, 0))
        box.addView(etText, mlp(MATCH, WRAP, 0, dp(8), 0, 0))

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
            val all = Store.load(this)
            val name = if (nm.isEmpty()) "Template " + (all.size + 1).toString() else nm
            if (cur != null && index >= 0 && index < all.size) {
                pos.remove(keyOf(all[index]))
                all[index] = Tpl(name, tx)
            } else {
                all.add(Tpl(name, tx))
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

    private fun importFile() {
        closePanel()
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("pick", true)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ----- mengetik otomatis -----
    private fun paste(node: AccessibilityNodeInfo, t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tpl", t))
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            val cur = node.text?.toString() ?: ""
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + t)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun pressEnter() {
        needEnter = false
        if (Build.VERSION.SDK_INT >= 30) {
            val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            n?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private fun play(t: Tpl, repeat: Boolean = false) {
        val ls = t.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (ls.isEmpty()) return
        if ((ls.size > 1 || repeat) && Build.VERSION.SDK_INT < 30) {
            toast("Enter otomatis butuh Android 11+")
            return
        }
        h.removeCallbacksAndMessages(null)
        waitingFocus = false
        loop = repeat
        val key = keyOf(t)
        if (key != curKey) needEnter = false
        lines = ls
        curKey = key
        idx = pos[key] ?: 0
        if (idx >= ls.size) idx = 0
        running = true
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
    }

    private fun finish() {
        running = false
        needEnter = false
        waitingFocus = false
        h.removeCallbacksAndMessages(null)
        pos[curKey] = 0
        refreshBubble()
    }

    private fun step() {
        if (!running) return
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            pause()
            toast("Klik kolom ketik dulu, lalu ketuk bubble > play")
            return
        }
        paste(node, lines[idx])
        idx++
        pos[curKey] = idx
        if (idx >= lines.size) {
            if (loop) {
                idx = 0
                pos[curKey] = 0
            } else {
                finish()
                return
            }
        }
        needEnter = true
        refreshBubble()
        val ms = Store.speed(this) * 20
        if (ms == 0) {
            h.postDelayed({
                pressEnter()
                waitingFocus = true
                h.postDelayed(proceed, 200)
            }, 60)
        } else {
            h.postDelayed({
                pressEnter()
                h.postDelayed({ step() }, (ms * 7 / 10).toLong())
            }, (ms * 3 / 10).toLong())
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
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
