package com.amnesia.browser

import android.net.Uri
import java.net.URLEncoder

object UrlUtil {
    private val trackers = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "yclid", "ysclid", "_openstat", "igshid", "igsh", "twclid", "ttclid", "li_fat_id", "_hsenc", "_hsmi",
        "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "rb_clickid", "s_cid", "ef_id", "zanpid",
        "_ga", "_gl", "srsltid", "epik", "sc_cid", "wickedid", "irclickid"
    )

    private fun isTracker(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("utm_") || n in trackers
    }

    /** Убирает трекинг-параметры, не перекодируя остальную часть адреса. */
    fun stripTracking(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val q = uri.encodedQuery ?: return url
            val parts = q.split('&')
            val kept = parts.filter { part ->
                val name = Uri.decode(part.substringBefore('='))
                part.isNotEmpty() && !isTracker(name)
            }
            if (kept.size == parts.size) return url
            uri.buildUpon().encodedQuery(if (kept.isEmpty()) null else kept.joinToString("&")).build().toString()
        } catch (_: Exception) {
            url
        }
    }

    private val schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")

    /** Текст из адресной строки → URL (или поисковый запрос). */
    fun toUrl(input: String, searchTemplate: String): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        val hasSpace = t.contains(' ')
        if (!hasSpace && (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("about:", true))) return t
        if (!hasSpace && schemeRe.matches(t) && t.contains("://")) return t
        val looksLikeHost = !hasSpace && (t.contains('.') || t.startsWith("localhost")) &&
            !t.startsWith('.') && !t.endsWith('.')
        if (looksLikeHost) return "https://$t"
        return searchTemplate.replace("%s", URLEncoder.encode(t, "UTF-8"))
    }
}
