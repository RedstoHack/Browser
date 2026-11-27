package com.amnesia.browser

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Возможности новых версий WebView. Вызываются через рефлексию и с проверкой
 * поддержки, чтобы приложение работало и на старых WebView.
 */
object WebCompat {

    private fun supported(feature: String): Boolean = try {
        WebViewFeature.isFeatureSupported(feature)
    } catch (_: Throwable) {
        false
    }

    /**
     * Убирает заголовок X-Requested-With (по нему сайт видит имя пакета приложения).
     */
    fun removeRequestedWithHeader(settings: WebSettings) {
        if (!supported("REQUESTED_WITH_HEADER_ALLOW_LIST")) return
        try {
            WebSettingsCompat::class.java
                .getMethod("setRequestedWithHeaderOriginAllowList", WebSettings::class.java, Set::class.java)
                .invoke(null, settings, emptySet<String>())
        } catch (_: Throwable) {}
    }

    /**
     * Подменяет заголовки Sec-CH-UA-* (Client Hints), которые иначе выдают «Android WebView».
     * Возвращает true при успехе.
     */
    fun setUserAgentMetadata(settings: WebSettings, ua: JSONObject?, mobile: Boolean, fallbackPlatform: String): Boolean {
        if (!supported("USER_AGENT_METADATA")) return false
        return try {
            val bvBuilderCls = Class.forName("androidx.webkit.UserAgentMetadata\$BrandVersion\$Builder")
            val bvCls = Class.forName("androidx.webkit.UserAgentMetadata\$BrandVersion")
            val mdBuilderCls = Class.forName("androidx.webkit.UserAgentMetadata\$Builder")
            val mdCls = Class.forName("androidx.webkit.UserAgentMetadata")

            fun brand(name: String, major: String, full: String): Any {
                val b = bvBuilderCls.getConstructor().newInstance()
                bvBuilderCls.getMethod("setBrand", String::class.java).invoke(b, name)
                bvBuilderCls.getMethod("setMajorVersion", String::class.java).invoke(b, major)
                bvBuilderCls.getMethod("setFullVersion", String::class.java).invoke(b, full)
                return bvBuilderCls.getMethod("build").invoke(b)!!
            }

            val list = ArrayList<Any>()
            var fullVersion = "99.0.0.0"
            var platform = fallbackPlatform
            var platformVersion = ""
            var arch = ""
            var model = ""
            var bitness = 0
            if (ua != null) {
                val low = ua.optJSONArray("brands")
                val full = ua.optJSONArray("fullVersionList")
                if (low != null) {
                    for (i in 0 until low.length()) {
                        val b = low.getJSONObject(i)
                        val name = b.optString("brand")
                        var fv = "${b.optString("version")}.0.0.0"
                        if (full != null) {
                            for (j in 0 until full.length()) {
                                val f = full.getJSONObject(j)
                                if (f.optString("brand") == name) fv = f.optString("version")
                            }
                        }
                        list.add(brand(name, b.optString("version"), fv))
                    }
                }
                fullVersion = ua.optString("uaFullVersion", fullVersion).ifBlank { fullVersion }
                platform = ua.optString("platform", platform)
                platformVersion = ua.optString("platformVersion", "")
                arch = ua.optString("architecture", "")
                model = ua.optString("model", "")
                bitness = ua.optString("bitness", "0").toIntOrNull() ?: 0
            }
            if (list.isEmpty()) list.add(brand("Not=A?Brand", "99", "99.0.0.0"))

            val md = mdBuilderCls.getConstructor().newInstance()
            fun set(name: String, type: Class<*>, value: Any) {
                try { mdBuilderCls.getMethod(name, type).invoke(md, value) } catch (_: Throwable) {}
            }
            set("setBrandVersionList", List::class.java, list)
            set("setFullVersion", String::class.java, fullVersion)
            set("setPlatform", String::class.java, platform)
            set("setPlatformVersion", String::class.java, platformVersion)
            set("setArchitecture", String::class.java, arch)
            set("setModel", String::class.java, model)
            set("setMobile", java.lang.Boolean.TYPE, mobile)
            set("setBitness", Integer.TYPE, bitness)
            set("setWow64", java.lang.Boolean.TYPE, false)
            val metadata = mdBuilderCls.getMethod("build").invoke(md)
            WebSettingsCompat::class.java
                .getMethod("setUserAgentMetadata", WebSettings::class.java, mdCls)
                .invoke(null, settings, metadata)
            bvCls.name // keep reference
            true
        } catch (_: Throwable) {
            false
        }
    }
}
