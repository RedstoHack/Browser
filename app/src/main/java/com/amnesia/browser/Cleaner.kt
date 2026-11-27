package com.amnesia.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File

/** «Деменция»: всё, что сайт мог оставить на устройстве, удаляется. */
object Cleaner {

    /**
     * Очистка через API WebView (безопасно при работающем WebView).
     * Куки, localStorage, sessionStorage, IndexedDB, Cache Storage, Service Workers,
     * HTTP-кэш (ETag-суперкуки), формы, HTTP-авторизация, SSL-исключения, разрешения.
     */
    fun wipe(ctx: Context, webView: WebView?, clearHistory: Boolean = false, done: (() -> Unit)? = null) {
        try { WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
        try {
            webView?.clearCache(true)
            webView?.clearFormData()
            webView?.clearSslPreferences()
            if (clearHistory) webView?.clearHistory()
        } catch (_: Throwable) {}
        try { WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword() } catch (_: Throwable) {}
        try { GeolocationPermissions.getInstance().clearAll() } catch (_: Throwable) {}
        val cm = CookieManager.getInstance()
        try {
            cm.removeSessionCookies(null)
            cm.removeAllCookies {
                cm.flush()
                done?.invoke()
            }
        } catch (_: Throwable) {
            done?.invoke()
        }
    }

    /**
     * Полное физическое удаление папок WebView. Вызывать ТОЛЬКО до того,
     * как WebView был создан в этом процессе.
     */
    fun deepWipeFiles(ctx: Context) {
        try {
            val dataDir = File(ctx.applicationInfo.dataDir)
            dataDir.listFiles()?.forEach { f ->
                if (f.isDirectory && (f.name.startsWith("app_webview") || f.name == "app_textures" || f.name == "app_geolocation")) {
                    f.deleteRecursively()
                }
            }
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            ctx.codeCacheDir.listFiles()?.forEach { it.deleteRecursively() }
            File(dataDir, "shared_prefs/WebViewChromiumPrefs.xml").delete()
        } catch (_: Throwable) {}
    }
}
