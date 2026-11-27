package com.amnesia.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Собирает итоговый JS: шаблон spoof.js + конфиг личности + флаги из настроек. */
object SpoofScript {
    private var template: String? = null

    private fun template(ctx: Context): String =
        template ?: ctx.assets.open("spoof.js").bufferedReader().use { it.readText() }.also { template = it }

    /** Фреймы антибот-проверок: там подмена JS не работает никогда. */
    private val CHALLENGE_HOSTS = listOf(
        "challenges.cloudflare.com", "hcaptcha.com", "newassets.hcaptcha.com",
        "recaptcha.net", "client-api.arkoselabs.com", "geo.captcha-delivery.com"
    )

    fun build(ctx: Context, profile: Profile, p: Prefs, nativeUaData: Boolean, fallback: Boolean): String {
        val c = JSONObject(profile.json.toString())
        c.put("nativeUaData", nativeUaData)
        c.put("fallback", fallback)
        c.put("compatAll", p.compatAll)
        c.put("compatHosts", JSONArray(p.compatHosts.toList()))
        c.put("challengeHosts", JSONArray(CHALLENGE_HOSTS))

        c.put("noise", JSONObject()
            .put("canvas", p.noiseCanvas)
            .put("webgl", p.noiseWebgl)
            .put("audio", p.noiseAudio)
            .put("fonts", p.noiseFonts)
            .put("rects", p.noiseRects))
        c.put("blockWebRTC", p.blockWebRtc)
        c.put("hideMedia", p.hideMedia)
        c.put("blockVoices", p.blockVoices)
        c.put("dnt", JSONObject.NULL) // как у Chrome по умолчанию: DNT сам по себе — лишний бит отпечатка

        if (!p.spoofHardware) {
            c.remove("hardwareConcurrency")
            c.remove("deviceMemory")
            c.remove("webgl")
        }
        if (!p.spoofBattery) {
            c.remove("battery")
            c.remove("connection")
        }
        return template(ctx).replace("/*__CONFIG__*/null", c.toString())
    }
}
