package com.yogi.sathi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

/**
 * A simple home screen for grandparents: a compact clock with the Nepali date and tithi (tap to hear it in Nepali),
 * big tiles for the apps they use (chosen by the family), a built-in phone with photos of the family, a torch and Wi-Fi.
 */
class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var tts: TextToSpeech? = null
    private var voice = "en"
    private var torchOn = false
    private var setup = false
    /** "home" or "phone" */
    private var screen = "home"
    private var pendingCall: String? = null
    private var editing: Contact? = null
    /** today's calendar facts, worked out once a day (saves battery) */
    private var dayCache: Pair<Long, DayInfo?>? = null

    private fun np() = Prefs.nepaliText
    private fun t(n: String, e: String) = if (np()) n else e
    private fun cols() = Store.int("cols", 2).coerceIn(1, 4)
    private fun phoneCols() = Store.int("phone_cols", 3).coerceIn(1, 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)   // true black: saves battery on OLED screens
        setContentView(root)
        tts = TextToSpeech(this) { st ->
            if (st != TextToSpeech.SUCCESS) return@TextToSpeech
            // Nepali voice first; a Hindi voice reads Devanagari well too; English last
            voice = when {
                ok(Locale("ne", "NP")) -> "ne"
                ok(Locale("hi", "IN")) -> "hi"
                else -> { try { tts?.setLanguage(Locale.ENGLISH) } catch (_: Exception) {}; "en" }
            }
        }
    }

    private fun ok(l: Locale): Boolean = try {
        val r = tts?.isLanguageAvailable(l) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (r >= TextToSpeech.LANG_AVAILABLE) { tts?.setLanguage(l); true } else false
    } catch (_: Exception) { false }

    override fun onResume() {
        super.onResume()
        draw()
        scheduleTick()
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    override fun onDestroy() {
        if (torchOn) torch(false)
        tts?.shutdown()
        super.onDestroy()
    }

    /** redraw exactly when the minute changes, and only while the screen is showing */
    private val tick = Runnable { if (!setup) draw(); scheduleTick() }
    private fun scheduleTick() {
        h.removeCallbacks(tick)
        val c = Calendar.getInstance()
        h.postDelayed(tick, (60 - c.get(Calendar.SECOND)) * 1000L + 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen == "phone" -> { screen = "home"; draw() }
            setup -> { setup = false; draw() }
            !isHome() -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button: always back to the main screen
        screen = "home"
        draw()
    }

    private fun isHome(): Boolean {
        val r = try { packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0) } catch (_: Exception) { null }
        return r?.activityInfo?.packageName == packageName
    }

    private fun today(): DayInfo? {
        val d = Bs.todayEpoch()
        dayCache?.let { if (it.first == d) return it.second }
        val info = try { Patro.day(d) } catch (_: Throwable) { null }
        dayCache = d to info
        return info
    }

    // ===================================================================== tiles (chosen by the family)

    /** built-in tiles: key → (emoji, Nepali, English) */
    private val BUILTIN = linkedMapOf(
        "phone" to Triple("📞", "फोन", "Phone"),
        "torch" to Triple("🔦", "टर्च", "Torch"),
        "wifi" to Triple("📶", "वाइफाइ", "Wi-Fi"),
        "camera" to Triple("📷", "क्यामेरा", "Camera"),
        "photos" to Triple("🖼", "फोटो", "Photos")
    )

    private fun tiles(): MutableList<String> {
        val s = Store.str("tiles", "")
        if (s.isNotEmpty()) return try { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } } catch (_: Exception) { defaults() }
        return defaults()
    }

    private fun defaults(): MutableList<String> {
        val out = mutableListOf("phone")
        for (p in listOf("com.whatsapp", "com.google.android.youtube", "com.amma.tv", "com.yogi.meds", "com.yogi.patro"))
            if (packageManager.getLaunchIntentForPackage(p) != null) out.add("app:$p")
        out.addAll(listOf("camera", "photos", "torch", "wifi"))
        return out
    }

    private fun saveTiles(l: List<String>) = Store.setStr("tiles", JSONArray(l).toString())

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg.substringAfterLast('.') }

    // ===================================================================== drawing

    private fun draw() {
        nightDim()
        root.removeAllViews()
        if (screen == "phone") { phone(); return }
        val sv = ScrollView(this)
        val col = vbox()
        col.setPadding(dp(10), dp(24), dp(10), dp(16))
        sv.addView(col)
        root.addView(sv)
        col.addView(clockCard())
        if (setup) col.addView(label(t("⚙ सेटिङ: टाइल छोएर हटाउनुहोस्/सार्नुहोस्, ＋ ले थप्नुहोस्", "⚙ Setup: tap a tile to remove or move it, ＋ to add"),
            15f, C.GOLD, true).apply { setPadding(dp(8), dp(8), dp(8), dp(4)) })
        val list = tiles()
        val items: List<String?> = list + if (setup) listOf(null) else emptyList()
        val n = cols()
        val size = (resources.displayMetrics.widthPixels - dp(20)) / n - dp(10)
        var row: LinearLayout? = null
        for ((i, k) in items.withIndex()) {
            if (i % n == 0) { row = hbox(); col.addView(row) }
            val v = if (k == null) addTile() else tile(k, i)
            row?.addView(v, LinearLayout.LayoutParams(0, (size * if (n >= 3) 1.05f else 0.75f).toInt(), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        val rest = items.size % n
        if (rest != 0) for (k in 0 until n - rest) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        if (setup) settings(col)
        col.addView(gearButton())
    }

    /** compact clock: time, Nepali date, tithi, festival and battery in little space */
    private fun clockCard(): View {
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        val day = Bs.todayEpoch()
        val b = Bs.fromEpoch(day)
        val info = today()
        val c = card(0xFF1E1410.toInt())
        c.setPadding(dp(16), dp(12), dp(16), dp(12))
        val r = hbox()
        val time = Bs.num(h12) + ":" + (if (Prefs.nepaliDigits) Bs.num(mm).padStart(2, '०') else mm.toString().padStart(2, '0'))
        r.addView(label(time, 48f, C.TEXT, true).apply { includeFontPadding = false })
        val side = vbox()
        side.addView(label(dayPart(hh), 18f, C.GOLD, true))
        if (b != null) side.addView(label(Bs.dayName(Bs.weekday(day)), 16f, C.SUB))
        r.addView(side.lin(0, WRAP, 1f, l = dp(12)))
        r.addView(label("🔊", 26f).apply { setPadding(dp(8), 0, 0, 0) })
        c.addView(r)
        if (b != null) c.addView(label(Bs.format(b) + "   ·   " + (info?.let { Astro.tithiName(it.tithi) } ?: ""), 19f, C.TEXT, true).lin(t = dp(4)))
        info?.fests?.firstOrNull()?.let { f -> c.addView(label(f.emoji + " " + f.name(), 16f, 0xFFFF8A80.toInt(), true)) }
        battery()?.let { c.addView(label(it, 14f, C.SUB).lin(t = dp(2))) }
        c.setOnClickListener { speak() }
        return c
    }

    private fun dayPart(hh: Int): String = when (hh) {
        in 4..11 -> t("बिहान", "Morning")
        in 12..15 -> t("दिउँसो", "Afternoon")
        in 16..19 -> t("साँझ", "Evening")
        else -> t("राति", "Night")
    }

    private fun tile(k: String, index: Int): View {
        val v = vbox()
        v.gravity = Gravity.CENTER
        val on = (k == "torch" && torchOn) || (k == "wifi" && wifiOn())
        v.background = ripple(if (on) 0xFF5A4810.toInt() else C.CARD, 26f)
        if (k.startsWith("app:")) {
            val pkg = k.removePrefix("app:")
            val icon = ImageView(this)
            try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
            v.addView(icon, LinearLayout.LayoutParams(dp(if (cols() >= 3) 54 else 64), dp(if (cols() >= 3) 54 else 64)))
            v.addView(label(appLabel(pkg), if (cols() >= 3) 17f else 22f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(4), dp(6), dp(4), 0) })
        } else {
            val b = BUILTIN[k] ?: Triple("❔", k, k)
            v.addView(label(b.first, if (cols() >= 3) 38f else 46f).apply { gravity = Gravity.CENTER })
            val name = when {
                k == "torch" && torchOn -> t("टर्च बन्द", "Torch off")
                k == "wifi" -> t(b.second, b.third) + if (wifiOn()) " ✓" else ""
                else -> t(b.second, b.third)
            }
            v.addView(label(name, if (cols() >= 3) 17f else 22f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
        }
        if (setup) {
            // move and remove right on the tile
            val bar = hbox()
            bar.gravity = Gravity.CENTER
            fun b(txt: String, f: () -> Unit) = bar.addView(label(txt, 22f, C.GOLD, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { f() }
            })
            b("◀") { moveTile(index, -1) }
            b("🗑") { val l = tiles(); l.removeAt(index); saveTiles(l); draw() }
            b("▶") { moveTile(index, 1) }
            v.addView(bar)
        } else v.setOnClickListener { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); open(k) }
        return v
    }

    private fun moveTile(i: Int, by: Int) {
        val l = tiles()
        val j = i + by
        if (j !in l.indices) return
        l.add(j, l.removeAt(i))
        saveTiles(l)
        draw()
    }

    private fun addTile(): View = vbox().apply {
        gravity = Gravity.CENTER
        background = rounded(C.CARD, dpf(26f), dp(2), C.GOLD)
        addView(label("＋", 40f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        addView(label(t("थप्नुहोस्", "Add"), 18f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        setOnClickListener { addMenu() }
    }

    private fun gearButton(): View {
        val gear = label(if (setup) t("✓ सकियो", "✓ Done") else "⚙", if (setup) 18f else 22f, if (setup) 0xFF1A1110.toInt() else C.SUB, setup)
        gear.gravity = Gravity.CENTER
        gear.setPadding(dp(18), dp(10), dp(18), dp(10))
        gear.background = rounded(if (setup) C.GOLD else C.CARD, dpf(22f))
        if (setup) gear.setOnClickListener { setup = false; draw() }
        else {
            val hold = Runnable { gear.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); setup = true; draw() }
            gear.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { (v as TextView).text = "⏳"; h.postDelayed(hold, 2000) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(hold); (v as TextView).text = "⚙" }
                }
                true
            }
        }
        return gear.lin(WRAP, WRAP, t = dp(14)).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun battery(): String? = try {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val ch = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (lv < 0) null else {
            val p = lv * 100 / sc
            (if (ch) "⚡ " else if (p <= 20) "🪫 " else "🔋 ") + Bs.num(p) + "%" + if (p <= 20 && !ch) t("  चार्ज गर्नुहोस्!", "  please charge!") else ""
        }
    } catch (_: Exception) { null }

    private fun nightDim() {
        val hh = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val lp = window.attributes
        lp.screenBrightness = if (Store.bool("night_dim", true) && (hh >= 21 || hh < 5)) 0.12f else -1f
        window.attributes = lp
    }

    // ===================================================================== speaking (Nepali, 12-hour)

    private val ONES = listOf("", "एक", "दुई", "तीन", "चार", "पाँच", "छ", "सात", "आठ", "नौ", "दश", "एघार", "बाह्र")

    private fun speak() {
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        val day = Bs.todayEpoch()
        val b = Bs.fromEpoch(day)
        val info = today()
        val text = if (voice != "en") {
            val part = when (hh) { in 4..11 -> "बिहानको"; in 12..15 -> "दिउँसोको"; in 16..19 -> "साँझको"; else -> "रातिको" }
            val time = "अहिले $part ${ONES[h12]} बजे" + if (mm > 0) "र $mm मिनेट गयो।" else "।"
            val date = if (b != null) " आज ${Bs.DAYS_NP[Bs.weekday(day)]}, ${Bs.MONTHS_NP[b.m - 1]} ${b.d} गते।" else ""
            val tithi = info?.let { " तिथि ${Astro.pakshaName(it.tithi, true)} ${Astro.tithiName(it.tithi, true)}।" + it.fests.joinToString("") { f -> " आज ${f.np}।" } } ?: ""
            time + date + tithi
        } else {
            val time = "It is $h12" + (if (mm > 0) ":" + mm.toString().padStart(2, '0') else " o'clock") + " in the " +
                listOf("morning", "afternoon", "evening", "night")[when (hh) { in 4..11 -> 0; in 12..15 -> 1; in 16..19 -> 2; else -> 3 }] + "."
            val date = if (b != null) " Today is ${Bs.DAYS_EN[Bs.weekday(day)]}, ${b.d} ${Bs.MONTHS_EN[b.m - 1]}." else ""
            val tithi = info?.let { " Tithi: ${Astro.pakshaName(it.tithi, false)} ${Astro.tithiName(it.tithi, false)}." } ?: ""
            time + date + tithi
        }
        try { tts?.setSpeechRate(0.85f); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "time") } catch (_: Exception) { toast(text) }
        if (voice == "en" && np() && !Store.bool("voice_tip", false)) {
            Store.setBool("voice_tip", true)
            AlertDialog.Builder(this).setMessage("To hear it in Nepali: Settings → Accessibility → Text-to-speech → Google → install the Nepali (or Hindi) voice.")
                .setPositiveButton("Open") { _, _ -> try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) {} }
                .setNegativeButton("Later", null).show()
        }
    }

    // ===================================================================== opening things

    private fun open(k: String) {
        when (k) {
            "phone" -> { screen = "phone"; draw() }
            "torch" -> { torch(!torchOn); draw() }
            "wifi" -> wifi()
            "camera" -> try { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) } catch (_: Exception) { toast("No camera app") }
            "photos" -> {
                val p = packageManager.getLaunchIntentForPackage("com.yogi.photos")
                try { startActivity(p ?: Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) } catch (_: Exception) {}
            }
            else -> {
                val pkg = k.removePrefix("app:")
                val i = packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) startActivity(i) else toast(t("यो एप छैन", "This app isn't installed"))
            }
        }
    }

    private fun wifiOn(): Boolean = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun wifi() {
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: apps may not switch Wi-Fi directly; this opens the small Wi-Fi panel with its switch
            try { startActivity(Intent(Settings.Panel.ACTION_WIFI)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        } else {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = !wm.isWifiEnabled
                toast(if (wm.isWifiEnabled) t("वाइफाइ बन्द", "Wi-Fi off") else t("वाइफाइ खुल्यो", "Wi-Fi on"))
                h.postDelayed({ draw() }, 1500)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
    }

    private fun torch(on: Boolean) {
        if (Build.VERSION.SDK_INT < 23) { toast("Torch needs Android 6 or newer"); return }
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: run { toast(t("टर्च छैन", "No torch")); return }
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) { toast(t("टर्च बल्न सकेन", "Couldn't turn on the torch")) }
    }

    // ===================================================================== setup: tiles

    private fun tileMenu(k: String, index: Int) {
        val l = tiles()
        AlertDialog.Builder(this).setItems(arrayOf(t("◀ अगाडि सार्नुहोस्", "◀ Move earlier"), t("पछाडि सार्नुहोस् ▶", "Move later ▶"), t("🗑 हटाउनुहोस्", "🗑 Remove"))) { _, w ->
            when (w) {
                0 -> if (index > 0) { l.add(index - 1, l.removeAt(index)) }
                1 -> if (index < l.size - 1) { l.add(index + 1, l.removeAt(index)) }
                else -> l.removeAt(index)
            }
            saveTiles(l)
            draw()
        }.show()
    }

    private fun addMenu() {
        val have = tiles()
        val built = BUILTIN.keys.filter { it !in have }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName && "app:$it" !in have }.map { it to appLabel(it) }.sortedBy { it.second.lowercase() }
        val names = built.map { val b = BUILTIN[it]!!; b.first + "  " + t(b.second, b.third) } + apps.map { "📱  " + it.second }
        AlertDialog.Builder(this).setTitle(t("के थप्ने?", "Add a tile")).setItems(names.toTypedArray()) { _, w ->
            val key = if (w < built.size) built[w] else "app:" + apps[w - built.size].first
            saveTiles(have + key)
            draw()
        }.setNegativeButton(t("रद्द", "Cancel"), null).show()
    }

    private fun settings(col: LinearLayout) {
        val c = card()
        fun row(name: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            c.addView(label(name, 14f, C.SUB, true).lin(t = dp(10), b = dp(4)))
            val r = hbox()
            opts.forEachIndexed { i, o -> r.addView(chip(o, i == sel) { pick(i); draw() }) }
            c.addView(HorizontalScrollView(this).apply { addView(r) })
        }
        row(t("एक पङ्क्तिमा टाइल", "Tiles per row"), listOf("1", "2", "3", "4"), cols() - 1) { Store.setInt("cols", it + 1) }
        row(t("फोनमा एक पङ्क्तिमा फोटो", "Photos per row in Phone"), listOf("1", "2", "3", "4"), phoneCols() - 1) { Store.setInt("phone_cols", it + 1) }
        row(t("फोटो छुँदा", "When a photo is tapped"), listOf(t("३ सेकेन्ड गन्ती", "3-second countdown"), t("सोधेर", "Ask first"), t("तुरुन्त", "Call at once")),
            Store.int("call_mode", 0)) { Store.setInt("call_mode", it) }
        row(t("भाषा", "Language"), listOf("नेपाली", "English"), if (Prefs.nepaliText) 0 else 1) { Store.setBool("np_text", it == 0) }
        row(t("अंक", "Numbers"), listOf("१२३", "123"), if (Prefs.nepaliDigits) 0 else 1) { Store.setBool("np_digits", it == 0) }
        row(t("आपतकालीन नम्बर देखाउने", "Show emergency numbers"), listOf(t("हो", "On"), t("होइन", "Off")), if (Store.bool("sos_show", true)) 0 else 1) {
            Store.setBool("sos_show", it == 0)
        }
        row(t("राति स्क्रिन मधुरो", "Dim at night"), listOf(t("हो", "On"), t("होइन", "Off")), if (Store.bool("night_dim", true)) 0 else 1) { Store.setBool("night_dim", it == 0) }
        c.addView(label(t("📞 फोनका सम्पर्कहरू: फोन टाइल खोलेर थप्नुहोस्।", "📞 Contacts: open the Phone tile to add people."), 14f, C.SUB).lin(t = dp(10)))
        c.addView(goldButton(if (isHome()) t("🏠 यो होम स्क्रिन हो", "🏠 It is the home screen") else t("🏠 होम स्क्रिन बनाउनुहोस्", "🏠 Make it the home screen")) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
        }.lin(t = dp(12)))
        col.addView(c)
    }

    private fun goldButton(s: String, f: () -> Unit): TextView = label(s, 16f, 0xFF1A1110.toInt(), true).apply {
        gravity = Gravity.CENTER
        background = ripple(C.GOLD, 16f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { f() }
    }

    // ===================================================================== the phone (family photos)

    private fun phone() {
        val sv = ScrollView(this)
        val col = vbox()
        col.setPadding(dp(10), dp(24), dp(10), dp(16))
        sv.addView(col)
        root.addView(sv)
        val head = hbox()
        head.addView(label("‹", 34f, C.TEXT, true).apply { setPadding(dp(10), 0, dp(14), 0); setOnClickListener { screen = "home"; draw() } })
        head.addView(label(t("📞 फोन", "📞 Phone"), 30f, C.TEXT, true).lin(0, WRAP, 1f))
        if (setup) head.addView(label(t("✓ सकियो", "✓ Done"), 16f, C.GOLD, true).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { setup = false; draw() } })
        col.addView(head)
        val people = People.all()
        val n = phoneCols()
        val gap = dp(8)
        val w = (resources.displayMetrics.widthPixels - dp(20) - gap * (n + 1)) / n
        var row: LinearLayout? = null
        val items: List<Contact?> = people + if (setup || people.isEmpty()) listOf(null) else emptyList()
        for ((i, c) in items.withIndex()) {
            if (i % n == 0) { row = hbox(); row.gravity = Gravity.TOP; col.addView(row) }
            val v = if (c == null) addPersonTile(w) else personTile(c, w)
            row?.addView(v, LinearLayout.LayoutParams(w, WRAP).apply { setMargins(gap / 2 + dp(2), gap / 2, gap / 2 + dp(2), gap / 2) })
        }
        if (Store.bool("sos_show", true) || setup) emergency(col)
        col.addView(goldButton(t("🔢 नम्बर थिच्नुहोस्", "🔢 Dial a number")) {
            try { startActivity(Intent(Intent.ACTION_DIAL)) } catch (_: Exception) {}
        }.lin(t = dp(14)))
        if (!setup) col.addView(label(t("सम्पर्क थप्न/बदल्न: तलको ⚙ दुई सेकेन्ड थिच्नुहोस्।", "To add or change people: hold ⚙ below for two seconds."), 13f, C.SUB).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        })
        if (!setup) col.addView(gearButton())
    }

    /** emergency numbers: the usual 100 / 102 / 101 or the family's own (son, neighbour…) */
    private fun sos(): MutableList<Triple<String, String, String>> {
        val s = Store.str("sos", "")
        if (s.isEmpty()) return mutableListOf(Triple("🚓", t("प्रहरी", "Police"), "100"), Triple("🚑", t("एम्बुलेन्स", "Ambulance"), "102"),
            Triple("🚒", t("दमकल", "Fire"), "101"))
        return try {
            JSONArray(s).let { a -> (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.optString("i", "🆘"), o.optString("n"), o.optString("p")) }.toMutableList() }
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveSos(l: List<Triple<String, String, String>>) {
        val a = JSONArray()
        for ((i, n, p) in l) a.put(org.json.JSONObject().put("i", i).put("n", n).put("p", p))
        Store.setStr("sos", a.toString())
    }

    private fun emergency(col: LinearLayout) {
        val list = sos()
        col.addView(label(t("आपतकालीन", "EMERGENCY") + if (setup && !Store.bool("sos_show", true)) t("  (लुकेको)", "  (hidden)") else "", 13f, C.SUB, true)
            .apply { setPadding(dp(10), dp(18), 0, dp(6)) })
        var er: LinearLayout? = null
        val items: List<Triple<String, String, String>?> = list + if (setup) listOf(null) else emptyList()
        for ((k, e) in items.withIndex()) {
            if (k % 3 == 0) { er = hbox(); col.addView(er) }
            val b = vbox()
            b.gravity = Gravity.CENTER
            b.setPadding(0, dp(10), 0, dp(10))
            if (e == null) {
                b.background = rounded(C.CARD, dpf(18f), dp(2), C.GOLD)
                b.addView(label("＋", 26f, C.GOLD, true).apply { gravity = Gravity.CENTER })
                b.addView(label(t("नम्बर थप्नुहोस्", "Add number"), 13f, C.GOLD, true).apply { gravity = Gravity.CENTER })
                b.setOnClickListener { editSos(-1) }
            } else {
                val (icon, name, num) = e
                b.background = ripple(0xFF3A1414.toInt(), 18f)
                b.addView(label(icon, 26f).apply { gravity = Gravity.CENTER })
                b.addView(label(name, 14f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
                b.addView(label(num, 13f, C.SUB).apply { gravity = Gravity.CENTER; maxLines = 1 })
                b.setOnClickListener {
                    if (setup) editSos(k)
                    else AlertDialog.Builder(this).setTitle("$name ($num)?").setPositiveButton(t("📞 फोन गर्नुहोस्", "📞 Call")) { _, _ -> call(num) }
                        .setNegativeButton(t("होइन", "No"), null).show()
                }
            }
            er?.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(5), dp(4), dp(5), dp(4)) })
        }
        val rest = items.size % 3
        if (rest != 0) for (x in 0 until 3 - rest) er?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
    }

    private fun editSos(i: Int) {
        val l = sos()
        val cur = l.getOrNull(i)
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this).apply { hint = t("नाम (जस्तै: छोरा, छिमेकी)", "Name (e.g. Son, Neighbour)"); setText(cur?.second ?: ""); textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val num = EditText(this).apply { hint = t("फोन नम्बर", "Phone number"); setText(cur?.third ?: ""); textSize = 20f; inputType = InputType.TYPE_CLASS_PHONE }
        box.addView(name)
        box.addView(num)
        val icons = listOf("🆘", "👨", "👩", "👦", "👧", "🏠", "🚓", "🚑", "🚒", "🏥")
        var icon = cur?.first ?: "🆘"
        val r = hbox()
        fun drawIcons() { r.removeAllViews(); for (e in icons) r.addView(chip(e, e == icon) { icon = e; drawIcons() }) }
        drawIcons()
        box.addView(HorizontalScrollView(this).apply { addView(r) }.lin(t = dp(8)))
        val d = AlertDialog.Builder(this).setTitle(t("आपतकालीन नम्बर", "Emergency number")).setView(box)
            .setPositiveButton(t("सेभ", "Save")) { _, _ ->
                val n = name.text.toString().trim()
                val p = People.clean(num.text.toString())
                if (n.isEmpty() || p.isEmpty()) { toast(t("नाम र नम्बर लेख्नुहोस्", "Write a name and number")); return@setPositiveButton }
                if (i >= 0) l[i] = Triple(icon, n, p) else l.add(Triple(icon, n, p))
                saveSos(l)
                draw()
            }.setNegativeButton(t("रद्द", "Cancel"), null)
        if (i >= 0) {
            d.setNeutralButton(t("हटाउनुहोस्", "Remove")) { _, _ -> l.removeAt(i); saveSos(l); draw() }
        }
        d.show()
    }

    private fun personTile(c: Contact, w: Int): View {
        val t = vbox()
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.background = ripple(C.CARD, 22f)
        t.setPadding(dp(6), dp(6), dp(6), dp(10))
        val pic = FrameLayout(this)
        pic.background = rounded(c.color, dpf(18f))
        pic.clipToOutline = true
        val f = c.photo(this)
        if (f.exists()) pic.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        else pic.addView(label(c.name.trim().take(1).uppercase(), w / resources.displayMetrics.density * 0.32f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH, MATCH))
        if (setup) pic.addView(label("✎", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(0x99000000.toInt(), dpf(16f)) },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        val side = w - dp(12)
        t.addView(pic, LinearLayout.LayoutParams(side, side))
        t.addView(label(c.name, if (phoneCols() >= 4) 15f else 20f, C.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6), 0, 0) })
        t.setOnClickListener { if (setup) editPerson(c) else tapCall(c) }
        t.setOnLongClickListener { if (!setup) more(c); true }
        return t
    }

    private fun addPersonTile(w: Int): View = vbox().apply {
        gravity = Gravity.CENTER
        background = rounded(C.CARD, dpf(22f), dp(2), C.GOLD)
        minimumHeight = w
        addView(label("＋", 40f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        addView(label(t("मान्छे थप्नुहोस्", "Add person"), 16f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        setOnClickListener { addPerson() }
    }

    private fun tapCall(c: Contact) {
        try { tts?.speak(c.name, TextToSpeech.QUEUE_FLUSH, null, "n") } catch (_: Exception) {}
        when (Store.int("call_mode", 0)) {
            2 -> call(c.number)
            1 -> AlertDialog.Builder(this).setTitle(t("${c.name} लाई फोन गर्ने?", "Call ${c.name}?"))
                .setPositiveButton(t("📞 हो", "📞 Yes")) { _, _ -> call(c.number) }.setNegativeButton(t("होइन", "No"), null).show()
            else -> countdown(c)
        }
    }

    private fun countdown(c: Contact) {
        val ov = vbox()
        ov.gravity = Gravity.CENTER
        ov.setBackgroundColor(0xF2000000.toInt())
        ov.isClickable = true
        val pic = FrameLayout(this)
        pic.background = rounded(c.color, dpf(28f))
        pic.clipToOutline = true
        val f = c.photo(this)
        if (f.exists()) pic.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(f.path)) })
        else pic.addView(label(c.name.take(1).uppercase(), 80f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(pic, LinearLayout.LayoutParams(dp(190), dp(190)))
        ov.addView(label(t("${c.name} लाई फोन गर्दै…", "Calling ${c.name}…"), 28f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(16), dp(20), 0) })
        val n = label(Bs.num(3), 60f, C.GOLD, true).apply { gravity = Gravity.CENTER }
        ov.addView(n)
        val cancel = label(t("✕  रद्द", "✕  Cancel"), 26f, Color.WHITE, true)
        cancel.gravity = Gravity.CENTER
        cancel.background = ripple(0xFFD32F2F.toInt(), 30f)
        cancel.setPadding(dp(40), dp(16), dp(40), dp(16))
        ov.addView(cancel.lin(WRAP, WRAP, t = dp(18)))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        var left = 3
        val step = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) { root.removeView(ov); call(c.number) } else { n.text = Bs.num(left); h.postDelayed(this, 1000) }
            }
        }
        cancel.setOnClickListener { h.removeCallbacks(step); root.removeView(ov) }
        h.postDelayed(step, 1000)
    }

    @SuppressLint("MissingPermission")
    private fun call(number: String) {
        val num = People.clean(number)
        if (num.isEmpty()) { toast("No number"); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = num
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5)
            return
        }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))) } catch (_: Exception) { dial(num) }
    }

    private fun dial(num: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        val n = pendingCall ?: return
        pendingCall = null
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) call(n) else dial(n)
    }

    private fun more(c: Contact) {
        val num = People.clean(c.number)
        AlertDialog.Builder(this).setTitle(c.name).setItems(arrayOf("📞  " + t("फोन", "Call"), "🟢  WhatsApp", "💬  " + t("सन्देश", "Message"))) { _, w ->
            when (w) {
                0 -> call(c.number)
                1 -> {
                    val wa = num.removePrefix("+").let { if (it.length == 10 && it.startsWith("9")) "977$it" else it }
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa"))) } catch (_: Exception) {}
                }
                else -> try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))) } catch (_: Exception) {}
            }
        }.show()
    }

    private fun addPerson() {
        AlertDialog.Builder(this).setTitle(t("मान्छे थप्नुहोस्", "Add a person"))
            .setItems(arrayOf(t("📇 फोनका सम्पर्कबाट", "📇 From phone contacts"), t("⌨️ नाम र नम्बर लेखेर", "⌨️ Type name and number"))) { _, w ->
                if (w == 0) try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT)
                } catch (_: Exception) { toast("No contacts app") }
                else editPerson(Contact(People.newId(), "", "", Contact.COLORS[People.all().size % Contact.COLORS.size]), true)
            }.show()
    }

    private fun editPerson(c: Contact, isNew: Boolean = false) {
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this).apply { hint = t("नाम (जस्तै: छोरा, दिदी)", "Name"); setText(c.name); textSize = 20f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val num = EditText(this).apply { hint = t("फोन नम्बर", "Phone number"); setText(c.number); textSize = 20f; inputType = InputType.TYPE_CLASS_PHONE }
        box.addView(name)
        box.addView(num)
        box.addView(goldButton(t("🖼 फोटो छान्नुहोस्", "🖼 Choose photo")) {
            editing = c.also { it.name = name.text.toString().trim(); it.number = num.text.toString().trim() }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_PHOTO)
            } catch (_: Exception) {}
        }.lin(t = dp(10)))
        if (!isNew) {
            val mv = hbox()
            mv.addView(goldButton("◀") { People.move(c, -1); draw() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            mv.addView(goldButton("▶") { People.move(c, 1); draw() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(mv.lin(t = dp(10)))
        }
        val d = AlertDialog.Builder(this).setTitle(if (isNew) t("नयाँ मान्छे", "New person") else c.name).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("सेभ", "Save")) { _, _ ->
                c.name = name.text.toString().trim()
                c.number = num.text.toString().trim()
                if (c.name.isEmpty() || People.clean(c.number).isEmpty()) { toast(t("नाम र नम्बर लेख्नुहोस्", "Write a name and number")); return@setPositiveButton }
                People.put(c)
                draw()
            }.setNegativeButton(t("रद्द", "Cancel"), null)
        if (!isNew) d.setNeutralButton(t("हटाउनुहोस्", "Remove")) { _, _ -> People.remove(this, c); draw() }
        d.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        when (req) {
            REQ_CONTACT -> try {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val c = Contact(People.newId(), cur.getString(0) ?: "", cur.getString(1) ?: "", Contact.COLORS[People.all().size % Contact.COLORS.size])
                        cur.getString(2)?.let { p -> People.savePhoto(this, Uri.parse(p), c) }
                        editPerson(c, true)
                    }
                }
            } catch (_: Exception) { toast("Couldn't read that contact") }
            REQ_PHOTO -> editing?.let { c ->
                People.savePhoto(this, uri, c)
                if (c.name.isNotEmpty() && People.clean(c.number).isNotEmpty()) People.put(c)
                draw()
                editPerson(c, People.all().none { it.id == c.id })
            }
        }
    }

    companion object {
        const val REQ_CONTACT = 11
        const val REQ_PHOTO = 12
    }
}
