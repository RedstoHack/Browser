package com.amnesia.browser

import android.os.LocaleList
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Генератор согласованных «личностей».
 * Значения подобраны так, чтобы не противоречить друг другу:
 * UA Windows → platform Win32, десктопная видеокарта, нет тач-скрина и т.д.
 * Версии браузеров вычисляются от текущей даты, поэтому не устаревают.
 */
class ProfileGenerator(engineFullVersion: String? = null) {

    /**
     * Реальная версия движка WebView (например 142.0.7444.171).
     * Версия Chrome в UA обязана совпадать с ней: антиботы (Cloudflare) проверяют,
     * что браузер поддерживает ровно те возможности, которые есть у заявленной версии.
     */
    private val engineFull: String? = engineFullVersion?.takeIf { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) }
    private val engineMajor: Int? = engineFull?.substringBefore('.')?.toIntOrNull()

    private val rnd: Random = SecureRandom().asKotlinRandom()
    private fun <T> List<T>.pick(): T = this[rnd.nextInt(size)]
    private fun chance(p: Double) = rnd.nextDouble() < p

    // ---------- версии ----------
    private fun releasesSince(y: Int, m: Int, d: Int): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, m - 1, d)
        val diff = System.currentTimeMillis() - c.timeInMillis
        return if (diff < 0) 0 else (diff / (28L * 24 * 3600 * 1000)).toInt()
    }

    private val chromeStable = 137 + releasesSince(2025, 6, 24)
    private val firefoxStable = 139 + releasesSince(2025, 6, 24)
    private val safariMajor: Int = run {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        maxOf(26, 26 + (y - 2025) - if (m < 9) 1 else 0)
    }

    private fun chromeMajor() = engineMajor ?: (chromeStable - listOf(0, 0, 0, 1, 1, 2).pick())
    private fun chromeFull(major: Int): String {
        if (major == engineMajor && engineFull != null) return engineFull
        val build = 6099 + (major - 120) * 61 + rnd.nextInt(0, 8)
        return "$major.0.$build.${rnd.nextInt(30, 230)}"
    }

    // ---------- справочники ----------
    private data class Gpu(val vendor: String, val renderer: String)

    private val androidGpus = listOf(
        Gpu("Qualcomm", "Adreno (TM) 740"), Gpu("Qualcomm", "Adreno (TM) 750"),
        Gpu("Qualcomm", "Adreno (TM) 730"), Gpu("Qualcomm", "Adreno (TM) 660"),
        Gpu("Qualcomm", "Adreno (TM) 642L"), Gpu("Qualcomm", "Adreno (TM) 619"),
        Gpu("ARM", "Mali-G710 MC10"), Gpu("ARM", "Mali-G78 MP14"), Gpu("ARM", "Mali-G68 MC4"),
        Gpu("ARM", "Mali-G57 MC2"), Gpu("ARM", "Immortalis-G715 MC11"),
        Gpu("Imagination Technologies", "PowerVR B-Series BXM-8-256")
    )

    private val windowsGpus = listOf(
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 (0x00002504) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Laptop GPU (0x000028E0) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1650 (0x00001F82) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 (0x00002786) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6600 (0x000073FF) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 620 (0x00005917) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics (0x00009A49) Direct3D11 vs_5_0 ps_5_0, D3D11)"),
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 770 (0x00004680) Direct3D11 vs_5_0 ps_5_0, D3D11)")
    )

    private val macGpus = listOf(
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Pro, Unspecified Version)"),
        Gpu("Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M4, Unspecified Version)")
    )

    private val linuxGpus = listOf(
        Gpu("Google Inc. (Intel)", "ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CFL GT2), OpenGL 4.6)"),
        Gpu("Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 580 Series (radeonsi, polaris10, LLVM 17.0.6, DRM 3.54), OpenGL 4.6)"),
        Gpu("Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1660 SUPER/PCIe/SSE2, OpenGL 4.5.0)")
    )

    /** Модель → видеокарта её чипа (чтобы Client Hints и WebGL не противоречили друг другу). */
    private val androidDevices: List<Pair<String, Gpu>> = listOf(
        "SM-S928B" to Gpu("Qualcomm", "Adreno (TM) 750"),          // Galaxy S24 Ultra
        "SM-S918B" to Gpu("Qualcomm", "Adreno (TM) 740"),          // Galaxy S23 Ultra
        "SM-S911B" to Gpu("Qualcomm", "Adreno (TM) 740"),          // Galaxy S23
        "SM-A546B" to Gpu("ARM", "Mali-G68 MP5"),                  // Galaxy A54 (Exynos 1380)
        "SM-A356B" to Gpu("ARM", "Mali-G68 MP5"),                  // Galaxy A35 (Exynos 1380)
        "SM-G991B" to Gpu("ARM", "Mali-G78 MP14"),                 // Galaxy S21 (Exynos 2100)
        "SM-A155F" to Gpu("ARM", "Mali-G57 MC2"),                  // Galaxy A15 (Helio G99)
        "Pixel 9 Pro" to Gpu("ARM", "Mali-G715 MP7"),              // Tensor G4
        "Pixel 9" to Gpu("ARM", "Mali-G715 MP7"),
        "Pixel 8" to Gpu("ARM", "Mali-G715 MP7"),                  // Tensor G3
        "Pixel 8a" to Gpu("ARM", "Mali-G715 MP7"),
        "Pixel 7a" to Gpu("ARM", "Mali-G710 MP7"),                 // Tensor G2
        "2201116SG" to Gpu("Qualcomm", "Adreno (TM) 619"),         // Redmi Note 11 Pro 5G (SD695)
        "M2101K6G" to Gpu("Qualcomm", "Adreno (TM) 618"),          // Redmi Note 10 Pro (SD732G)
        "CPH2581" to Gpu("Qualcomm", "Adreno (TM) 750"),           // OnePlus 12
        "NE2213" to Gpu("Qualcomm", "Adreno (TM) 730"),            // OnePlus 10 Pro
        "XQ-DQ72" to Gpu("Qualcomm", "Adreno (TM) 740"),           // Xperia 1 V
        "moto g84 5G" to Gpu("Qualcomm", "Adreno (TM) 619"),       // SD695
        "RMX3771" to Gpu("ARM", "Mali-G68 MC4")                    // Realme 11 Pro (Dimensity 7050)
    )

    private val androidModels = listOf(
        "SM-S928B", "SM-S918B", "SM-S911B", "SM-A546B", "SM-A356B", "SM-G991B", "SM-A155F",
        "Pixel 9 Pro", "Pixel 9", "Pixel 8", "Pixel 8a", "Pixel 7a",
        "23078PND5G", "2201116SG", "24049RN28L", "M2101K6G",
        "CPH2581", "RMX3771", "moto g84 5G", "XQ-DQ72", "V2244", "NE2213"
    )

    private data class Scr(val w: Int, val h: Int, val dpr: Double)

    private val androidScreens = listOf(
        Scr(360, 800, 3.0), Scr(412, 915, 2.625), Scr(393, 873, 2.75), Scr(384, 854, 2.8125),
        Scr(360, 780, 3.0), Scr(412, 892, 2.625), Scr(400, 889, 2.7), Scr(385, 854, 2.8)
    )
    private val iphoneScreens = listOf(
        Scr(390, 844, 3.0), Scr(393, 852, 3.0), Scr(430, 932, 3.0), Scr(375, 812, 3.0), Scr(402, 874, 3.0)
    )
    private val windowsScreens = listOf(
        Scr(1920, 1080, 1.0), Scr(1366, 768, 1.0), Scr(1536, 864, 1.25), Scr(1280, 720, 1.5),
        Scr(1600, 900, 1.0), Scr(2560, 1440, 1.0), Scr(1440, 900, 1.0), Scr(1280, 800, 1.5)
    )
    private val macScreens = listOf(
        Scr(1440, 900, 2.0), Scr(1512, 982, 2.0), Scr(1728, 1117, 2.0), Scr(1470, 956, 2.0), Scr(1920, 1080, 2.0)
    )
    private val linuxScreens = listOf(Scr(1920, 1080, 1.0), Scr(2560, 1440, 1.0), Scr(1366, 768, 1.0))

    private val regions = mapOf(
        "Europe/Moscow" to listOf("ru-RU", "ru", "en-US", "en"),
        "Asia/Novosibirsk" to listOf("ru-RU", "ru", "en-US", "en"),
        "Asia/Almaty" to listOf("ru-RU", "ru", "kk", "en"),
        "Europe/Berlin" to listOf("de-DE", "de", "en-US", "en"),
        "Europe/Amsterdam" to listOf("nl-NL", "nl", "en-US", "en"),
        "Europe/Paris" to listOf("fr-FR", "fr", "en-US", "en"),
        "Europe/Warsaw" to listOf("pl-PL", "pl", "en-US", "en"),
        "Europe/Madrid" to listOf("es-ES", "es", "en"),
        "Europe/Istanbul" to listOf("tr-TR", "tr", "en-US", "en"),
        "Europe/London" to listOf("en-GB", "en"),
        "America/New_York" to listOf("en-US", "en"),
        "America/Chicago" to listOf("en-US", "en", "es"),
        "America/Los_Angeles" to listOf("en-US", "en"),
        "America/Sao_Paulo" to listOf("pt-BR", "pt", "en-US", "en"),
        "Asia/Dubai" to listOf("en-US", "en", "ar"),
        "Asia/Singapore" to listOf("en-SG", "en", "zh-CN"),
        "Asia/Tokyo" to listOf("ja-JP", "ja", "en-US", "en")
    )

    private val greaseBrands = listOf("Not)A;Brand", "Not/A)Brand", "Not_A Brand", "Not A(Brand", "Not;A=Brand", "Not.A/Brand")
    private val greaseVersions = listOf("8", "24", "99")

    // ---------- публичное API ----------
    fun generate(p: Prefs): Profile {
        val wantDesktop = when {
            p.desktopMode -> true
            p.deviceType == "desktop" -> true
            p.deviceType == "any" -> rnd.nextBoolean()
            else -> false
        }

        val o: JSONObject = when (p.uaMode) {
            "custom" -> if (p.customUa.isNotBlank()) fromCustomUa(p.customUa) else chromium(wantDesktop)
            "fictional" -> fictional(wantDesktop)
            "any" -> if (chance(0.6)) chromium(wantDesktop) else nonChromium(wantDesktop)
            else -> chromium(wantDesktop)
        }

        o.put("key", "k" + java.lang.Long.toHexString(rnd.nextLong()))
        o.put("seed", rnd.nextInt(1, Int.MAX_VALUE))
        o.put("desktopMode", p.desktopMode)

        applyScreen(o, p)
        applyRegion(o, p)

        val sc = o.optJSONObject("screen")
        val vw = if (sc != null && sc.optInt("w") >= 1024) sc.optInt("w") else listOf(1280, 1366, 1440).pick()
        o.put("viewportWidth", vw)

        if (o.optBoolean("mobile", true) && !p.desktopMode) {
            o.put("battery", JSONObject().put("level", rnd.nextInt(25, 100) / 100.0).put("charging", chance(0.3)))
        } else {
            o.put("battery", JSONObject().put("level", 1.0).put("charging", true))
        }
        return Profile(o)
    }

    // ---------- Chromium-семейство ----------
    private fun chromium(desktop: Boolean): JSONObject {
        val major = chromeMajor()
        val full = chromeFull(major)
        val reduced = "$major.0.0.0"
        val o = JSONObject()
        o.put("vendor", "Google Inc.")
        o.put("productSub", "20030107")
        o.put("chromeObj", true)

        if (!desktop) {
            val brandName: String
            val brandVer: String
            val brandFull: String
            val tail: String
            val flavor = listOf("chrome", "chrome", "chrome", "chrome", "samsung", "yandex", "opera").pick()
            val androidVer = listOf("13", "14", "14", "15", "15", "16").pick()
            // модель и видеокарта всегда из одного реального телефона
            val (model, gpu) = if (flavor == "samsung") androidDevices.filter { it.first.startsWith("SM-") }.pick()
            else androidDevices.pick()
            when (flavor) {
                "samsung" -> {
                    val sv = 27 + rnd.nextInt(0, 3)
                    brandName = "Samsung Internet"; brandVer = "$sv"; brandFull = "$sv.0.0.${rnd.nextInt(10, 70)}"
                    tail = "SamsungBrowser/$sv.0"
                }
                "yandex" -> {
                    val yv = "25.${rnd.nextInt(2, 12)}.${rnd.nextInt(0, 9)}.${rnd.nextInt(10, 200)}"
                    brandName = "YaBrowser"; brandVer = yv.substringBefore('.'); brandFull = yv
                    tail = " YaBrowser/$yv"
                }
                "opera" -> {
                    val ov = major - 47
                    brandName = "OperaMobile"; brandVer = "$ov"
                    brandFull = "$ov.${rnd.nextInt(0, 3)}.${rnd.nextInt(4900, 5400)}.${rnd.nextInt(10000, 99999)}"
                    tail = " OPR/$brandFull"
                }
                else -> {
                    brandName = "Google Chrome"; brandVer = "$major"; brandFull = full
                    tail = ""
                }
            }
            // Chrome на Android с 2023 г. отдаёт «урезанный» UA: Android 10; K — у всех одинаково
            val base = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)"
            val ua = when (flavor) {
                // реальные форматы: Samsung — токен перед Chrome, Opera — в самом конце
                "samsung" -> "$base $tail Chrome/$reduced Mobile Safari/537.36"
                "opera" -> "$base Chrome/$reduced Mobile Safari/537.36$tail"
                else -> "$base Chrome/$reduced$tail Mobile Safari/537.36"
            }
            o.put("userAgent", ua)
            o.put("platform", listOf("Linux armv8l", "Linux aarch64").pick())
            o.put("mobile", true)
            o.put("maxTouchPoints", listOf(5, 5, 10).pick())
            o.put("hardwareConcurrency", listOf(8, 8, 8, 6, 4).pick())
            o.put("deviceMemory", listOf(4, 8, 8, 8).pick())
            o.put("webgl", gpu(gpu))
            o.put("pdfViewer", false)
            o.put("connection", connection(true))
            o.put("uaData", uaData(brandName, brandVer, brandFull, major, full, true, "Android",
                "$androidVer.0.0", "", "", model))
            val nice = when (flavor) { "samsung" -> "Samsung $brandVer"; "yandex" -> "Яндекс $brandVer"; "opera" -> "Opera $brandVer"; else -> "Chrome $major" }
            o.put("label", "$nice · Android $androidVer · $model")
            o.put("short", "$nice · 📱")
            o.put("os", "android")
            return o
        }

        // десктоп
        val os = listOf("win", "win", "win", "win", "win", "mac", "mac", "linux").pick()
        val flavor = if (os == "linux") listOf("chrome", "chrome", "opera").pick()
        else listOf("chrome", "chrome", "chrome", "edge", "edge", "yandex", "opera").pick()
        val brandName: String
        val brandVer: String
        val brandFull: String
        val tail: String
        when (flavor) {
            "edge" -> { brandName = "Microsoft Edge"; brandVer = "$major"; brandFull = full; tail = " Edg/$reduced" }
            "yandex" -> {
                val yv = "25.${rnd.nextInt(2, 12)}.${rnd.nextInt(0, 9)}.${rnd.nextInt(1000, 2000)}"
                brandName = "YaBrowser"; brandVer = yv.substringBefore('.'); brandFull = yv; tail = " YaBrowser/$yv"
            }
            "opera" -> {
                val ov = major - 15
                brandName = "Opera"; brandVer = "$ov"; brandFull = "$ov.0.${rnd.nextInt(5000, 5999)}.${rnd.nextInt(10, 99)}"; tail = " OPR/$ov.0.0.0"
            }
            else -> { brandName = "Google Chrome"; brandVer = "$major"; brandFull = full; tail = "" }
        }
        val osToken: String
        val platform: String
        val chPlatform: String
        val chPlatformVer: String
        val arch: String
        val g: Gpu
        when (os) {
            "mac" -> {
                osToken = "Macintosh; Intel Mac OS X 10_15_7"; platform = "MacIntel"; chPlatform = "macOS"
                chPlatformVer = listOf("14.7.1", "15.5.0", "15.6.1", "26.1.0").pick(); arch = "arm"; g = macGpus.pick()
            }
            "linux" -> {
                osToken = "X11; Linux x86_64"; platform = "Linux x86_64"; chPlatform = "Linux"
                chPlatformVer = listOf("6.8.0", "6.11.0", "6.14.0").pick(); arch = "x86"; g = linuxGpus.pick()
            }
            else -> {
                osToken = "Windows NT 10.0; Win64; x64"; platform = "Win32"; chPlatform = "Windows"
                chPlatformVer = listOf("10.0.0", "15.0.0", "19.0.0", "19.0.0").pick(); arch = "x86"; g = windowsGpus.pick()
            }
        }
        o.put("userAgent", "Mozilla/5.0 ($osToken) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$reduced Safari/537.36$tail")
        o.put("platform", platform)
        o.put("mobile", false)
        o.put("maxTouchPoints", 0)
        o.put("hardwareConcurrency", if (os == "mac") listOf(8, 10, 12).pick() else listOf(4, 6, 8, 12, 16, 16, 20, 24).pick())
        o.put("deviceMemory", listOf(8, 8, 8, 4).pick())
        o.put("webgl", gpu(g))
        o.put("pdfViewer", true)
        o.put("pdfPlugins", true)
        o.put("connection", connection(false))
        o.put("uaData", uaData(brandName, brandVer, brandFull, major, full, false, chPlatform, chPlatformVer, arch, "64", ""))
        val nice = when (flavor) { "edge" -> "Edge $major"; "yandex" -> "Яндекс $brandVer"; "opera" -> "Opera $brandVer"; else -> "Chrome $major" }
        val osName = when (os) { "mac" -> "macOS"; "linux" -> "Linux"; else -> "Windows" }
        o.put("label", "$nice · $osName")
        o.put("short", "$nice · 🖥")
        o.put("os", os)
        return o
    }

    // ---------- Firefox / Safari ----------
    private fun nonChromium(desktop: Boolean): JSONObject {
        val o = JSONObject()
        val ff = chance(0.6)
        o.put("uaData", JSONObject.NULL)
        o.put("chromeObj", false)
        o.put("connection", JSONObject.NULL)
        o.put("deviceMemory", JSONObject.NULL)
        if (ff) {
            val v = firefoxStable - listOf(0, 0, 1).pick()
            o.put("vendor", "")
            o.put("productSub", "20100101")
            if (!desktop) {
                val av = listOf("13", "14", "15", "16").pick()
                o.put("userAgent", "Mozilla/5.0 (Android $av; Mobile; rv:$v.0) Gecko/$v.0 Firefox/$v.0")
                o.put("platform", "Linux aarch64"); o.put("oscpu", "Linux aarch64")
                o.put("mobile", true); o.put("maxTouchPoints", 5)
                o.put("hardwareConcurrency", 8)
                o.put("webgl", JSONObject().put("vendor", "Mozilla").put("renderer", "Adreno (TM) 740, or similar"))
                o.put("label", "Firefox $v · Android $av"); o.put("short", "Firefox $v · 📱"); o.put("os", "android")
                o.put("pdfViewer", false)
            } else {
                val win = chance(0.75)
                val token = if (win) "Windows NT 10.0; Win64; x64" else "X11; Linux x86_64"
                o.put("userAgent", "Mozilla/5.0 ($token; rv:$v.0) Gecko/20100101 Firefox/$v.0")
                o.put("platform", if (win) "Win32" else "Linux x86_64")
                o.put("oscpu", if (win) "Windows NT 10.0; Win64; x64" else "Linux x86_64")
                o.put("mobile", false); o.put("maxTouchPoints", 0)
                o.put("hardwareConcurrency", listOf(4, 8, 12, 16).pick())
                o.put("webgl", JSONObject().put("vendor", "Mozilla").put("renderer", listOf(
                    "NVIDIA GeForce GTX 980, or similar", "Intel(R) HD Graphics, or similar", "Radeon R9 200 Series, or similar").pick()))
                o.put("label", "Firefox $v · ${if (win) "Windows" else "Linux"}"); o.put("short", "Firefox $v · 🖥")
                o.put("os", if (win) "win" else "linux")
                o.put("pdfViewer", true); o.put("pdfPlugins", true)
            }
        } else {
            val sv = "$safariMajor.${rnd.nextInt(0, 3)}"
            o.put("vendor", "Apple Computer, Inc.")
            o.put("productSub", "20030107")
            o.put("hardwareConcurrency", if (desktop) 8 else 4) // Safari округляет
            o.put("webgl", JSONObject().put("vendor", "Apple Inc.").put("renderer", "Apple GPU"))
            if (!desktop) {
                o.put("userAgent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$sv Mobile/15E148 Safari/604.1")
                o.put("platform", "iPhone"); o.put("mobile", true); o.put("maxTouchPoints", 5)
                o.put("label", "Safari $sv · iPhone"); o.put("short", "Safari · 📱"); o.put("os", "ios")
                o.put("pdfViewer", false)
            } else {
                o.put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$sv Safari/605.1.15")
                o.put("platform", "MacIntel"); o.put("mobile", false); o.put("maxTouchPoints", 0)
                o.put("label", "Safari $sv · macOS"); o.put("short", "Safari · 🖥"); o.put("os", "mac")
                o.put("pdfViewer", true); o.put("pdfPlugins", true)
            }
        }
        return o
    }

    // ---------- выдуманные браузеры ----------
    private val syl = listOf("zor", "vex", "lu", "mi", "kra", "nim", "qua", "tal", "sy", "ro", "pex", "dra", "fen", "gli", "ox", "ber", "nu", "tra", "zu", "el")
    private fun word(n: Int = 2 + rnd.nextInt(0, 2)): String =
        (1..n).joinToString("") { syl.pick() }.replaceFirstChar { it.uppercase() }

    private fun fictional(desktop: Boolean): JSONObject {
        val o = JSONObject()
        val name = word()
        val ver = "${rnd.nextInt(1, 300)}.${rnd.nextInt(0, 50)}.${rnd.nextInt(0, 9999)}"
        val engine = listOf(
            "AppleWebKit/${rnd.nextInt(530, 999)}.${rnd.nextInt(1, 99)} (KHTML, like Gecko)",
            "Gecko/${rnd.nextInt(2010, 2099)}${"%02d".format(rnd.nextInt(1, 13))}${"%02d".format(rnd.nextInt(1, 29))}",
            "${word(2)}Engine/${rnd.nextInt(1, 40)}.${rnd.nextInt(0, 9)}"
        ).pick()
        val osToken: String
        val platform: String
        if (desktop) {
            osToken = listOf(
                "Windows NT ${rnd.nextInt(11, 15)}.${rnd.nextInt(0, 9)}; Win64; x64",
                "X11; ${word()}OS ${rnd.nextInt(1, 30)}; x86_64",
                "Macintosh; ${word()} Mac OS X ${rnd.nextInt(16, 30)}_${rnd.nextInt(0, 9)}",
                "${word()}BSD ${rnd.nextInt(5, 20)}.${rnd.nextInt(0, 9)}; amd64",
                "Haiku R${rnd.nextInt(1, 9)}; x86_64"
            ).pick()
            platform = listOf("Win32", "Linux x86_64", "MacIntel", "${word()} x86_64").pick()
        } else {
            val model = "${word(1).uppercase()}-${rnd.nextInt(100, 9999)}${listOf("", "X", "Pro", "Ultra", "Max").pick()}"
            osToken = listOf(
                "Linux; Android ${rnd.nextInt(17, 40)}; $model",
                "${word()}OS ${rnd.nextInt(1, 30)}.${rnd.nextInt(0, 9)}; Mobile; $model",
                "Mobile; KaiOS ${rnd.nextInt(4, 12)}.${rnd.nextInt(0, 9)}; $model"
            ).pick()
            platform = listOf("Linux armv8l", "Linux aarch64", "${word()} arm64").pick()
        }
        val mobileTag = if (desktop) "" else " Mobile"
        o.put("userAgent", "Mozilla/5.0 ($osToken) $engine $name/$ver$mobileTag ${word()}/${rnd.nextInt(1, 999)}.${rnd.nextInt(0, 99)}")
        o.put("platform", platform)
        o.put("vendor", listOf("", "${name} Labs", "Google Inc.").pick())
        o.put("productSub", listOf("20030107", "20100101").pick())
        o.put("mobile", !desktop)
        o.put("maxTouchPoints", if (desktop) 0 else listOf(5, 10, 3, 7).pick())
        o.put("hardwareConcurrency", listOf(2, 3, 6, 10, 14, 18, 24, 32, 48, 64).pick())
        o.put("deviceMemory", listOf(1, 2, 4, 8).pick())
        o.put("webgl", JSONObject().put("vendor", "${word()} ${listOf("Labs", "Graphics", "Silicon", "Corp").pick()}")
            .put("renderer", "${word()} ${listOf("Fusion", "Vortex", "Quantum", "Nova", "Prism").pick()} ${rnd.nextInt(100, 9999)}"))
        o.put("chromeObj", false)
        o.put("pdfViewer", desktop)
        o.put("connection", connection(!desktop))
        val bv = ver.substringBefore('.')
        o.put("uaData", JSONObject()
            .put("brands", JSONArray().put(brand(name, bv)).put(brand(greaseBrands.pick(), greaseVersions.pick())))
            .put("fullVersionList", JSONArray().put(brand(name, ver)).put(brand(greaseBrands.pick(), "${greaseVersions.pick()}.0.0.0")))
            .put("mobile", !desktop)
            .put("platform", word())
            .put("platformVersion", "${rnd.nextInt(1, 40)}.${rnd.nextInt(0, 9)}.0")
            .put("architecture", listOf("x86", "arm", "risc-v", "").pick())
            .put("bitness", listOf("64", "32", "128").pick())
            .put("model", if (desktop) "" else word())
            .put("uaFullVersion", ver)
            .put("wow64", false))
        o.put("label", "$name $bv (выдуманный)")
        o.put("short", "$name · 👽")
        o.put("os", if (desktop) "fictional-desktop" else "fictional-mobile")
        return o
    }

    // ---------- свой UA ----------
    private fun fromCustomUa(ua: String): JSONObject {
        val o = JSONObject()
        val l = ua.lowercase()
        val mobile = listOf("mobile", "android", "iphone", "ipad").any { l.contains(it) }
        val chromeVer = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull()
        o.put("userAgent", ua)
        o.put("mobile", mobile)
        val platform = when {
            l.contains("iphone") -> "iPhone"
            l.contains("ipad") -> "iPad"
            l.contains("android") -> "Linux armv8l"
            l.contains("windows") -> "Win32"
            l.contains("mac os") || l.contains("macintosh") -> "MacIntel"
            l.contains("linux") -> "Linux x86_64"
            else -> if (mobile) "Linux armv8l" else "Win32"
        }
        o.put("platform", platform)
        o.put("maxTouchPoints", if (mobile) 5 else 0)
        o.put("hardwareConcurrency", if (mobile) 8 else listOf(4, 8, 12, 16).pick())
        val os = when (platform) { "iPhone", "iPad" -> "ios"; "MacIntel" -> "mac"; "Win32" -> "win"; "Linux armv8l" -> "android"; else -> "linux" }
        o.put("os", os)
        o.put("webgl", gpu(when (os) {
            "android" -> androidGpus.pick(); "mac" -> macGpus.pick(); "linux" -> linuxGpus.pick()
            "ios" -> Gpu("Apple Inc.", "Apple GPU"); else -> windowsGpus.pick()
        }))
        if (chromeVer != null) {
            o.put("vendor", "Google Inc."); o.put("productSub", "20030107"); o.put("chromeObj", true)
            o.put("deviceMemory", 8)
            o.put("connection", connection(mobile))
            val chPlat = when (os) { "android" -> "Android"; "mac" -> "macOS"; "linux" -> "Linux"; else -> "Windows" }
            o.put("uaData", uaData("Google Chrome", "$chromeVer", chromeFull(chromeVer), chromeVer, chromeFull(chromeVer),
                mobile, chPlat, if (mobile) "14.0.0" else "15.0.0", if (mobile) "" else "x86", if (mobile) "" else "64", ""))
            o.put("pdfViewer", !mobile); o.put("pdfPlugins", !mobile)
        } else {
            o.put("uaData", JSONObject.NULL); o.put("chromeObj", false); o.put("deviceMemory", JSONObject.NULL)
            o.put("connection", JSONObject.NULL)
            o.put("vendor", if (l.contains("firefox")) "" else if (l.contains("safari")) "Apple Computer, Inc." else "")
            o.put("productSub", if (l.contains("firefox")) "20100101" else "20030107")
        }
        o.put("label", "Свой UA")
        o.put("short", "Свой UA")
        return o
    }

    // ---------- части ----------
    private fun brand(b: String, v: String) = JSONObject().put("brand", b).put("version", v)

    private fun uaData(
        brandName: String, brandVer: String, brandFull: String, chromium: Int, chromiumFull: String,
        mobile: Boolean, platform: String, platformVersion: String, arch: String, bitness: String, model: String
    ): JSONObject {
        val g = greaseBrands.pick()
        val gv = greaseVersions.pick()
        val low = mutableListOf(brand(g, gv), brand("Chromium", "$chromium"), brand(brandName, brandVer))
        val full = mutableListOf(brand(g, "$gv.0.0.0"), brand("Chromium", chromiumFull), brand(brandName, brandFull))
        // порядок брендов в Chrome перемешивается — делаем так же, но согласованно
        val order = listOf(listOf(0, 1, 2), listOf(1, 0, 2), listOf(2, 1, 0), listOf(1, 2, 0)).pick()
        val lowArr = JSONArray(); val fullArr = JSONArray()
        order.forEach { lowArr.put(low[it]); fullArr.put(full[it]) }
        return JSONObject()
            .put("brands", lowArr)
            .put("fullVersionList", fullArr)
            .put("mobile", mobile)
            .put("platform", platform)
            .put("platformVersion", platformVersion)
            .put("architecture", arch)
            .put("bitness", bitness)
            .put("model", model)
            .put("uaFullVersion", brandFull)
            .put("wow64", false)
    }

    private fun gpu(g: Gpu) = JSONObject().put("vendor", g.vendor).put("renderer", g.renderer)

    private fun connection(mobile: Boolean): JSONObject = JSONObject()
        .put("effectiveType", "4g")
        .put("rtt", if (mobile) listOf(50, 100, 150, 200).pick() else listOf(0, 50, 50, 100).pick())
        .put("downlink", listOf(1.45, 2.3, 3.9, 5.6, 7.8, 9.25, 10.0).pick())

    private fun applyScreen(o: JSONObject, p: Prefs) {
        val mobile = o.optBoolean("mobile", true) && !p.desktopMode
        val os = o.optString("os", "android")
        val s: Scr? = when (p.screenMode) {
            "real" -> null
            "random" -> when {
                os == "ios" -> iphoneScreens.pick()
                os.startsWith("fictional") -> if (mobile) Scr(rnd.nextInt(320, 480), rnd.nextInt(640, 1000), listOf(1.5, 2.0, 2.5, 3.0, 3.5).pick())
                else Scr(rnd.nextInt(1024, 3840), rnd.nextInt(700, 2160), listOf(1.0, 1.25, 1.5, 2.0).pick())
                mobile -> androidScreens.pick()
                os == "mac" -> macScreens.pick()
                os == "linux" -> linuxScreens.pick()
                else -> windowsScreens.pick()
            }
            "custom" -> parseScreen(p.customScreen)
            else -> parseScreen(p.screenMode)
        }
        if (s == null) {
            o.remove("screen")
            return
        }
        val ah = when {
            mobile || os == "ios" -> s.h
            os == "mac" -> s.h - 25
            os == "linux" -> s.h - 27
            else -> s.h - listOf(40, 48).pick()
        }
        o.put("screen", JSONObject().put("w", s.w).put("h", s.h).put("aw", s.w).put("ah", ah)
            .put("dpr", s.dpr).put("cd", if (os == "mac" || os == "ios") 30 else 24))
    }

    private fun parseScreen(v: String): Scr? {
        val m = Regex("^\\s*(\\d{2,5})\\s*[xх×*]\\s*(\\d{2,5})\\s*(?:@\\s*([0-9.]+))?\\s*$").find(v) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        val dpr = m.groupValues[3].toDoubleOrNull()?.takeIf { it in 0.5..6.0 } ?: 1.0
        return Scr(w, h, dpr)
    }

    private fun applyRegion(o: JSONObject, p: Prefs) {
        var langs: List<String>? = null
        when (val r = p.region) {
            "real" -> o.remove("timezone")
            "random" -> {
                val tz = regions.keys.toList().pick()
                o.put("timezone", tz); langs = regions[tz]
            }
            else -> { o.put("timezone", r); langs = regions[r] }
        }
        if (p.customLangs.isNotBlank()) {
            langs = p.customLangs.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (langs.isNullOrEmpty()) {
            // реальные языки системы, чтобы navigator.languages совпадал с Accept-Language
            val tags = LocaleList.getDefault().toLanguageTags().split(',').filter { it.isNotBlank() }
            if (tags.isNotEmpty()) langs = tags
        }
        if (!langs.isNullOrEmpty()) {
            val a = JSONArray()
            langs.forEach { a.put(it) }
            o.put("languages", a)
        }
    }
}
