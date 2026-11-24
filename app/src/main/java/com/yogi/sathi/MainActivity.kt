package com.yogi.sathi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale

/**
 * A simple home screen for grandparents: a huge clock with the Nepali date and tithi (tap it to hear it),
 * and big buttons for calling, videos, medicines, photos, WhatsApp, camera and a torch.
 */
class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private var tts: TextToSpeech? = null
    private var nepaliVoice = false
    private var torchOn = false
    private var setup = false

    /** (key, emoji, Nepali name, English name, package or "" for a built-in action) */
    private val BUTTONS = listOf(
        listOf("call", "📞", "फोन", "Call", "com.yogi.ecall"),
        listOf("video", "📺", "भिडियो", "Videos", "com.amma.tv"),
        listOf("meds", "💊", "औषधि", "Medicines", "com.yogi.meds"),
        listOf("photos", "🖼", "फोटो", "Photos", "com.yogi.photos"),
        listOf("whatsapp", "🟢", "ह्वाट्सएप", "WhatsApp", "com.whatsapp"),
        listOf("camera", "📷", "क्यामेरा", "Camera", ""),
        listOf("patro", "🗓", "पात्रो", "Calendar", "com.yogi.patro"),
        listOf("torch", "🔦", "टर्च", "Torch", "")
    )

    private fun np() = Prefs.nepaliText
    private fun t(n: String, e: String) = if (np()) n else e
    private fun shown(key: String) = Store.bool("show_$key", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        col = vbox()
        col.setPadding(dp(12), dp(26), dp(12), dp(20))
        sv.addView(col)
        setContentView(sv)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = try { tts?.setLanguage(Locale("ne", "NP")) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                nepaliVoice = r != null && r >= TextToSpeech.LANG_AVAILABLE
                if (!nepaliVoice) try { tts?.setLanguage(Locale.ENGLISH) } catch (_: Exception) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        draw()
        h.removeCallbacks(tick)
        h.postDelayed(tick, 20_000)
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

    private val tick = object : Runnable {
        override fun run() {
            if (!setup) draw()
            h.postDelayed(this, 20_000)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (setup) { setup = false; draw() }
        else if (!isHome()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun isHome(): Boolean {
        val r = try { packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0) } catch (_: Exception) { null }
        return r?.activityInfo?.packageName == packageName
    }

    // ===================================================================== screen

    private fun dayPart(hh: Int): String = when (hh) {
        in 4..11 -> t("बिहान", "morning")
        in 12..15 -> t("दिउँसो", "afternoon")
        in 16..19 -> t("साँझ", "evening")
        else -> t("राति", "night")
    }

    private fun draw() {
        col.removeAllViews()
        nightDim()
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        val today = Bs.todayEpoch()
        val b = Bs.fromEpoch(today)
        val info = try { Patro.day(today) } catch (_: Throwable) { null }

        // the clock card: tap to hear the time and date
        val clock = card(0xFF2B1712.toInt())
        clock.gravity = Gravity.CENTER_HORIZONTAL
        clock.setPadding(dp(14), dp(18), dp(14), dp(18))
        clock.addView(label(dayPart(hh), 24f, C.GOLD, true).apply { gravity = Gravity.CENTER })
        clock.addView(label(Bs.num(h12) + ":" + Bs.num(mm.toString().padStart(2, '0').toInt()).padStart(2, if (Prefs.nepaliDigits) '०' else '0'),
            84f, C.TEXT, true).apply { gravity = Gravity.CENTER; includeFontPadding = false })
        if (b != null) {
            clock.addView(label(Bs.dayName(Bs.weekday(today)), 28f, C.GOLD, true).apply { gravity = Gravity.CENTER })
            clock.addView(label(Bs.format(b), 28f, C.TEXT, true).apply { gravity = Gravity.CENTER })
        }
        val ad = Bs.adFromEpoch(today)
        clock.addView(label("${Bs.DAYS_EN[Bs.weekday(today)]}, ${ad.d} ${Bs.AD_MONTHS[ad.m - 1]} ${ad.y}", 17f, C.SUB).apply { gravity = Gravity.CENTER })
        info?.let { i ->
            clock.addView(label("🌙 " + Astro.pakshaName(i.tithi) + " " + Astro.tithiName(i.tithi), 20f, C.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            for (f in i.fests) clock.addView(label(f.emoji + " " + f.name(), 20f, 0xFFFF8A80.toInt(), true).apply { gravity = Gravity.CENTER })
        }
        battery()?.let { clock.addView(label(it, 18f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }) }
        clock.addView(label(t("🔊 छुनुहोस्, समय सुन्नुहोस्", "🔊 Tap to hear the time"), 14f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        clock.setOnClickListener { speak() }
        col.addView(clock)

        // big buttons, two in a row
        val list = BUTTONS.filter { setup || shown(it[0]) }
        var row: LinearLayout? = null
        for ((i, bt) in list.withIndex()) {
            if (i % 2 == 0) {
                row = hbox()
                col.addView(row)
            }
            val v = bigButton(bt)
            row?.addView(v, LinearLayout.LayoutParams(0, dp(130), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        if (list.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, dp(130), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })

        if (setup) settings()
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
        col.addView(gear.lin(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, t = dp(18)).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    private fun bigButton(bt: List<String>): View {
        val key = bt[0]
        val v = vbox()
        v.gravity = Gravity.CENTER
        val on = key == "torch" && torchOn
        v.background = ripple(if (on) 0xFF6B5410.toInt() else C.CARD, 26f)
        v.addView(label(bt[1], 46f).apply { gravity = Gravity.CENTER })
        v.addView(label(if (key == "torch" && torchOn) t("टर्च बन्द", "Torch off") else t(bt[2], bt[3]), 24f, C.TEXT, true).apply { gravity = Gravity.CENTER })
        if (setup) {
            v.alpha = if (shown(key)) 1f else 0.35f
            v.setOnClickListener { Store.setBool("show_$key", !shown(key)); draw() }
        } else v.setOnClickListener {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            open(key, bt[4], t(bt[2], bt[3]))
        }
        return v
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

    /** gently dim the screen late at night */
    private fun nightDim() {
        val hh = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val lp = window.attributes
        lp.screenBrightness = if (Store.bool("night_dim", true) && (hh >= 21 || hh < 5)) 0.12f else -1f
        window.attributes = lp
    }

    // ===================================================================== speaking

    private fun speak() {
        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        val today = Bs.todayEpoch()
        val b = Bs.fromEpoch(today)
        val info = try { Patro.day(today) } catch (_: Throwable) { null }
        val text = if (nepaliVoice) {
            val time = "अहिले ${dayPart(hh)} $h12 बजे" + if (mm > 0) "र $mm मिनेट गयो।" else "।"
            val date = if (b != null) " आज ${Bs.DAYS_NP[Bs.weekday(today)]}, ${Bs.MONTHS_NP[b.m - 1]} ${b.d} गते।" else ""
            val tithi = info?.let { " ${Astro.tithiName(it.tithi, true)}।" + it.fests.joinToString("") { f -> " आज ${f.np}।" } } ?: ""
            time + date + tithi
        } else {
            val time = "It is $h12" + (if (mm > 0) ":" + mm.toString().padStart(2, '0') else " o'clock") + " in the " + listOf("morning", "afternoon", "evening", "night")[
                when (hh) { in 4..11 -> 0; in 12..15 -> 1; in 16..19 -> 2; else -> 3 }] + "."
            val date = if (b != null) " Today is ${Bs.DAYS_EN[Bs.weekday(today)]}, ${b.d} ${Bs.MONTHS_EN[b.m - 1]}." else ""
            val fest = info?.fests?.joinToString("") { " Today is ${it.en}." } ?: ""
            time + date + fest
        }
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "time") } catch (_: Exception) { toast(text) }
    }

    // ===================================================================== buttons

    private fun open(key: String, pkg: String, name: String) {
        when (key) {
            "torch" -> { torch(!torchOn); draw(); return }
            "camera" -> {
                try { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) } catch (_: Exception) { toast(t("क्यामेरा भेटिएन", "No camera app")) }
                return
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) { startActivity(launch); return }
        // fallbacks when the Yogi app isn't installed
        val fallback: Intent? = when (key) {
            "call" -> Intent(Intent.ACTION_DIAL)
            "photos" -> Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            else -> null
        }
        if (fallback != null) try { startActivity(fallback); return } catch (_: Exception) {}
        AlertDialog.Builder(this).setTitle(name)
            .setMessage(t("यो एप फोनमा छैन। Yogi Store बाट हाल्न सकिन्छ।", "This app isn't on the phone yet. You can get it from Yogi Store."))
            .setPositiveButton("Yogi Store") { _, _ ->
                val store = packageManager.getLaunchIntentForPackage("com.yogi.store")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yogeshhtr/YogiStore/releases/latest"))
                try { startActivity(store) } catch (_: Exception) {}
            }.setNegativeButton("OK", null).show()
    }

    private fun torch(on: Boolean) {
        if (Build.VERSION.SDK_INT < 23) { toast("Torch needs Android 6 or newer"); return }
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: run { toast(t("टर्च छैन", "No torch on this phone")); return }
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
            toast(t("टर्च बल्न सकेन", "Couldn't turn on the torch"))
        }
    }

    // ===================================================================== setup (family)

    private fun settings() {
        val c = card()
        c.addView(label(t("⚙ सेटिङ (परिवारका लागि)", "⚙ Setup (for family)"), 18f, C.GOLD, true))
        c.addView(label(t("माथिका बटन छोएर देखाउने/लुकाउने।", "Tap the buttons above to show or hide them."), 14f, C.SUB).lin(t = dp(4)))
        fun row(name: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            c.addView(label(name, 14f, C.SUB, true).lin(t = dp(10), b = dp(4)))
            val r = hbox()
            opts.forEachIndexed { i, o -> r.addView(chip(o, i == sel) { pick(i); draw() }) }
            c.addView(HorizontalScrollView(this).apply { addView(r) })
        }
        row(t("भाषा", "Language"), listOf("नेपाली", "English"), if (Prefs.nepaliText) 0 else 1) { Store.setBool("np_text", it == 0) }
        row(t("अंक", "Numbers"), listOf("१२३", "123"), if (Prefs.nepaliDigits) 0 else 1) { Store.setBool("np_digits", it == 0) }
        row(t("राति स्क्रिन मधुरो", "Dim the screen at night"), listOf(t("हो", "On"), t("होइन", "Off")), if (Store.bool("night_dim", true)) 0 else 1) {
            Store.setBool("night_dim", it == 0)
        }
        c.addView(button(if (isHome()) t("🏠 यो होम स्क्रिन हो", "🏠 It is the home screen") else t("🏠 होम स्क्रिन बनाउनुहोस्", "🏠 Make it the home screen")) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        }.lin(t = dp(14)))
        col.addView(c)
    }

    private fun button(s: String, f: () -> Unit): TextView = label(s, 16f, 0xFF1A1110.toInt(), true).apply {
        gravity = Gravity.CENTER
        background = ripple(C.GOLD, 16f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { f() }
    }
}
