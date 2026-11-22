package com.wans.tpl

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// ===== Penyimpanan template =====
object Store {
    private const val SEP = "\u0001"

    fun load(c: Context): MutableList<String> {
        val s = c.getSharedPreferences("tpl", 0).getString("items", "") ?: ""
        return if (s.isEmpty()) mutableListOf() else s.split(SEP).toMutableList()
    }

    fun save(c: Context, l: List<String>) {
        c.getSharedPreferences("tpl", 0).edit().putString("items", l.joinToString(SEP)).apply()
    }
}

// ===== Halaman utama apk =====
class MainActivity : Activity() {
    private lateinit var list: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        list = Store.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        val info = TextView(this).apply {
            text = "1) Aktifkan 'Template Melayang' di Aksesibilitas.\n2) Bubble 'T' akan muncul, bisa digeser.\n3) Klik kolom di form, ketuk bubble, pilih template.\n\nTemplate multi-baris: tiap baris diketik berurutan, Enter di antara baris (butuh Android 11+). Bubble menampilkan progres, misal 3/50.\n\nTahan lama sebuah template di daftar untuk menghapusnya."
        }
        val btnAcc = Button(this).apply {
            text = "Buka Pengaturan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnImp = Button(this).apply {
            text = "Impor dari file .txt"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "text/*"
                startActivityForResult(i, 1)
            }
        }
        val input = EditText(this).apply {
            hint = "Atau tulis template baru (satu baris = satu ketikan)"
            minLines = 4
            gravity = Gravity.TOP
        }
        val btnAdd = Button(this).apply { text = "Tambah Template" }
        val lv = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list)
        lv.adapter = adapter

        btnAdd.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                addTemplate(t)
                input.setText("")
            }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            list.removeAt(pos)
            Store.save(this, list)
            adapter.notifyDataSetChanged()
            true
        }

        root.addView(info)
        root.addView(btnAcc)
        root.addView(btnImp)
        root.addView(input)
        root.addView(btnAdd)
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addTemplate(t: String) {
        list.add(t)
        Store.save(this, list)
        adapter.notifyDataSetChanged()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 1 || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val clean = txt.replace("\r", "").replace("\uFEFF", "").trim()
            if (clean.isEmpty()) {
                Toast.makeText(this, "File kosong", Toast.LENGTH_SHORT).show()
                return
            }
            addTemplate(clean)
            val n = clean.split("\n").count { it.isNotBlank() }
            Toast.makeText(this, "Diimpor: " + n + " baris", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membaca file", Toast.LENGTH_SHORT).show()
        }
    }
}

// ===== Bubble melayang + pengetik otomatis =====
class FloatService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var bp: WindowManager.LayoutParams
    private var bubble: View? = null
    private var label: TextView? = null
    private var panel: View? = null
    private val h = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun lp(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun bg(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r
    }

    private fun tv(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(24, 20, 24, 20)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        background = bg(Color.parseColor("#33FFFFFF"), 16f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) }
    }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bp = lp().apply { x = 0; y = 400 }

        val b = TextView(this).apply {
            text = "T"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            minWidth = 120
            minHeight = 120
            setPadding(28, 0, 28, 0)
            background = bg(Color.parseColor("#E6007AFF"), 60f)
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
                        sx = bp.x; sy = bp.y; tx = ev.rawX; ty = ev.rawY; moved = false
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
                    MotionEvent.ACTION_UP -> if (!moved) toggle()
                }
                return true
            }
        })
        wm.addView(b, bp)
        bubble = b
        label = b
    }

    private fun setProgress(done: Int, total: Int) {
        label?.text = done.toString() + "/" + total.toString()
    }

    private fun toggle() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        val items = Store.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#EE222222"), 24f)
            setPadding(16, 16, 16, 16)
        }
        if (items.isEmpty()) box.addView(tv("Belum ada template. Tambahkan di apk."))
        items.forEach { t -> box.addView(tv(t).apply { setOnClickListener { insert(t) } }) }
        val sv = ScrollView(this).apply { addView(box) }
        val pp = lp().apply { x = bp.x; y = bp.y + 140; width = 640; height = 700 }
        wm.addView(sv, pp)
        panel = sv
    }

    private fun closePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

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

    private fun insert(t: String) {
        closePanel()
        val lines = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        if (lines.size > 1 && Build.VERSION.SDK_INT < 30) {
            toast("Enter otomatis butuh Android 11+")
            return
        }
        h.removeCallbacksAndMessages(null)
        setProgress(0, lines.size)
        typeLines(lines, 0)
    }

    private fun typeLines(lines: List<String>, i: Int) {
        if (i >= lines.size) return
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            toast("Klik kolom ketik dulu")
            return
        }
        paste(node, lines[i])
        setProgress(i + 1, lines.size)
        if (i < lines.size - 1) {
            h.postDelayed({
                val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (n != null && Build.VERSION.SDK_INT >= 30) {
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                h.postDelayed({ typeLines(lines, i + 1) }, 700)
            }, 300)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        closePanel()
        bubble?.let { wm.removeView(it) }
        bubble = null
        label = null
        super.onDestroy()
    }
}
