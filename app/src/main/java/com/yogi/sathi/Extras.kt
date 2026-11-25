package com.yogi.sathi

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Lets Yogi Sathi turn the screen off when you double-tap (used for nothing else). */
class AdminReceiver : DeviceAdminReceiver()

object ScreenOff {
    private fun comp(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun allowed(ctx: Context): Boolean = try { dpm(ctx).isAdminActive(comp(ctx)) } catch (_: Exception) { false }

    fun ask(a: Activity) {
        try {
            a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Yogi Sathi turn the screen off when you double-tap. It is used for nothing else."))
        } catch (_: Exception) {}
    }

    fun remove(ctx: Context) { try { dpm(ctx).removeActiveAdmin(comp(ctx)) } catch (_: Exception) {} }

    fun now(ctx: Context): Boolean = try { if (allowed(ctx)) { dpm(ctx).lockNow(); true } else false } catch (_: Exception) { false }
}

/** The hourly talking clock: says the time every hour from 7 am to 9 pm. */
object Chime {
    const val ACTION = "com.yogi.sathi.CHIME"
    private val ONES = listOf("", "एक", "दुई", "तीन", "चार", "पाँच", "छ", "सात", "आठ", "नौ", "दश", "एघार", "बाह्र")

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ChimeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!Store.bool("chime", false)) { am.cancel(pi(ctx)); return }
        val c = Calendar.getInstance()
        c.add(Calendar.HOUR_OF_DAY, 1)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 2); c.set(Calendar.MILLISECOND, 0)
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi(ctx))
        else am.set(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi(ctx))
    }

    fun text(nepali: Boolean, hh: Int): String {
        val h12 = if (hh % 12 == 0) 12 else hh % 12
        return if (nepali) {
            val part = when (hh) { in 4..11 -> "बिहानको"; in 12..15 -> "दिउँसोको"; in 16..19 -> "साँझको"; else -> "रातिको" }
            "अहिले $part ${ONES[h12]} बज्यो।"
        } else "It is $h12 o'clock."
    }
}

class ChimeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.init(ctx)
        Chime.schedule(ctx)
        if (i.action != Chime.ACTION || !Store.bool("chime", false)) return
        val hh = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hh !in 7..21) return
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        var tts: TextToSpeech? = null
        fun end() {
            if (finished.compareAndSet(false, true)) {
                try { tts?.shutdown() } catch (_: Exception) {}
                try { pending.finish() } catch (_: Exception) {}
            }
        }
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st != TextToSpeech.SUCCESS) { end(); return@TextToSpeech }
            val english = Store.int("speak_lang", 0) == 1
            fun has(l: Locale) = (try { tts?.isLanguageAvailable(l) } catch (_: Exception) { null } ?: -2) >= TextToSpeech.LANG_AVAILABLE
            val loc = when {
                english -> Locale.ENGLISH
                has(Locale("ne", "NP")) -> Locale("ne", "NP")
                has(Locale("hi", "IN")) -> Locale("hi", "IN")
                else -> Locale.ENGLISH
            }
            try { tts?.setLanguage(loc) } catch (_: Exception) {}
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { end() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { end() }
            })
            tts?.setSpeechRate(0.85f)
            tts?.speak(Chime.text(loc != Locale.ENGLISH, hh), TextToSpeech.QUEUE_FLUSH, null, "chime")
        }
        Handler(Looper.getMainLooper()).postDelayed({ end() }, 9000)
    }
}
