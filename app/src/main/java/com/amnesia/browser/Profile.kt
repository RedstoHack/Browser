package com.amnesia.browser

import org.json.JSONObject

/**
 * «Личность» — набор всех значений, которые видит сайт.
 * Хранится как JSON, тот же JSON (плюс флаги) уходит в spoof.js.
 */
class Profile(val json: JSONObject) {

    val label: String get() = json.optString("label", "?")
    val shortLabel: String get() = json.optString("short", label)
    val userAgent: String get() = json.optString("userAgent", "")
    val mobile: Boolean get() = json.optBoolean("mobile", true)
    val desktopMode: Boolean get() = json.optBoolean("desktopMode", false)
    val uaData: JSONObject? get() = json.optJSONObject("uaData")
    val timezone: String? get() = json.optString("timezone", "").ifBlank { null }

    val languages: List<String>?
        get() {
            val a = json.optJSONArray("languages") ?: return null
            return List(a.length()) { a.getString(it) }
        }

    /** Значение заголовка Accept-Language, соответствующее языкам личности. */
    fun acceptLanguage(): String? {
        val langs = languages ?: return null
        if (langs.isEmpty()) return null
        val sb = StringBuilder(langs[0])
        var q = 9
        for (i in 1 until langs.size) {
            sb.append(',').append(langs[i]).append(";q=0.").append(q.coerceAtLeast(1))
            q--
        }
        return sb.toString()
    }

    fun summary(): String {
        val sb = StringBuilder()
        fun line(k: String, v: Any?) { sb.append(k).append(": ").append(v?.toString() ?: "—").append('\n') }
        line("Личность", label)
        line("User-Agent", userAgent)
        line("Платформа", json.optString("platform"))
        val sc = json.optJSONObject("screen")
        line("Экран", if (sc == null) "реальный" else "${sc.optInt("w")}×${sc.optInt("h")} @${sc.optDouble("dpr")}")
        line("Режим", if (desktopMode) "десктоп" else if (mobile) "мобильный" else "компьютер")
        line("Ядра CPU", json.opt("hardwareConcurrency") ?: "реальные")
        line("Память, ГБ", if (json.isNull("deviceMemory") && json.has("deviceMemory")) "скрыта" else json.opt("deviceMemory") ?: "реальная")
        val gl = json.optJSONObject("webgl")
        line("Видеокарта", if (gl == null) "реальная" else "${gl.optString("vendor")} / ${gl.optString("renderer")}")
        line("Часовой пояс", timezone ?: "реальный")
        line("Языки", languages?.joinToString(", ") ?: "реальные")
        val ud = uaData
        line("Client Hints", if (ud == null) "скрыты" else "${ud.optString("platform")} ${ud.optString("platformVersion")} ${ud.optString("model")}".trim())
        line("Сенсор (touch points)", json.opt("maxTouchPoints"))
        line("Seed шума", json.optLong("seed"))
        return sb.toString().trimEnd()
    }

    companion object {
        fun parse(s: String?): Profile? = try {
            if (s.isNullOrBlank()) null else Profile(JSONObject(s))
        } catch (e: Exception) {
            null
        }
    }
}
