package com.amnesia.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Обёртка над настройками. Сами настройки — не данные сайтов, их не стираем. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    init {
        PreferenceManager.setDefaultValues(ctx, R.xml.preferences, false)
    }

    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)

    val rotationMode get() = s("rotation_mode", "launch")
    val uaMode get() = s("ua_mode", "chromium")
    val customUa get() = s("custom_ua", "").trim()
    val deviceType get() = s("device_type", "mobile")
    val screenMode get() = s("screen_mode", "random")
    val customScreen get() = s("custom_screen", "1280x720@1.5")
    val region get() = s("region", "real")
    val customLangs get() = s("custom_langs", "").trim()
    val spoofHardware get() = b("spoof_hardware", true)

    val noiseCanvas get() = b("noise_canvas", true)
    val noiseWebgl get() = b("noise_webgl", true)
    val noiseAudio get() = b("noise_audio", true)
    val noiseFonts get() = b("noise_fonts", true)
    val noiseRects get() = b("noise_rects", false)
    val blockWebRtc get() = b("block_webrtc", true)
    val hideMedia get() = b("hide_media", true)
    val blockVoices get() = b("block_voices", true)
    val spoofBattery get() = b("spoof_battery", true)

    val wipeOnStart get() = b("wipe_on_start", true)
    val wipeOnExit get() = b("wipe_on_exit", true)
    val wipeOnBackground get() = b("wipe_on_background", false)
    val wipeOnRotate get() = b("wipe_on_rotate", true)
    val cookies get() = s("cookies", "first")

    val searchTemplate get() = s("search", "https://duckduckgo.com/?q=%s")
    val home get() = s("home", "https://duckduckgo.com/").ifBlank { "about:blank" }
    val javascript get() = b("javascript", true)
    val stripTracking get() = b("strip_tracking", true)
    val blockImages get() = b("block_images", false)
    val safeBrowsing get() = b("safe_browsing", false)
    val blockScreenshots get() = b("block_screenshots", true)
    val proxy get() = s("proxy", "").trim()

    /** Совместимость с антиботами везде (подмена JS выключена, остаются UA и Client Hints). */
    val compatAll get() = b("compat_all", false)
    /** Автоматически включать сторонние куки, когда на странице проверка Cloudflare. */
    val autoChallengeCookies get() = b("auto_challenge_cookies", true)

    /** Сайты, для которых включена совместимость (подмена JS не применяется). */
    var compatHosts: Set<String>
        get() = sp.getStringSet("compat_hosts", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("compat_hosts", HashSet(v)).apply()

    var desktopMode: Boolean
        get() = b("desktop_mode", false)
        set(v) = sp.edit().putBoolean("desktop_mode", v).apply()

    /** Сохранённая личность (нужна для ручного режима, чтобы переживала перезапуск). */
    var savedProfile: String?
        get() = sp.getString("saved_profile", null)
        set(v) = sp.edit().putString("saved_profile", v).apply()

    companion object {
        /** Ключи, изменение которых не требует пересоздания личности. */
        val INTERNAL_KEYS = setOf("saved_profile", "desktop_mode", "compat_hosts")
    }
}
