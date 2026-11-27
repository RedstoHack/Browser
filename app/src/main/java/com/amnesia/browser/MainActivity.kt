package com.amnesia.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    companion object {
        /** WebView уже поднят в этом процессе — физически удалять его папки нельзя. */
        private var webViewStarted = false
    }

    private lateinit var prefs: Prefs
    private lateinit var generator: ProfileGenerator

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var chip: TextView

    private var profile: Profile? = null
    private var scriptHandler: ScriptHandler? = null
    private var fallbackJs: String? = null
    private var docStartSupported = false

    private var wentToBackground = false
    /** Сторонние куки временно разрешены, потому что на странице антибот-проверка. */
    @Volatile private var challengeCookies = false
    private var challengeCookiesHost: String? = null
    private val askedCompatHosts = HashSet<String>()
    /** Ушли в собственный экран (настройки, выбор файла) — это не «выход из браузера». */
    private var internalNavigation = false
    private var settingsDirty = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val mainExecutor = Executor { r -> runOnUiThread(r) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key !in Prefs.INTERNAL_KEYS) settingsDirty = true
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        var deepWiped = false
        if (prefs.wipeOnStart && !webViewStarted) {
            Cleaner.deepWipeFiles(this)
            deepWiped = true
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        chip = findViewById(R.id.identityChip)

        webView = WebView(this)
        webViewStarted = true
        findViewById<FrameLayout>(R.id.webContainer).addView(
            webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        // настоящая версия движка: версия Chrome в UA всегда будет равна ей
        val engine = try {
            Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1)
        } catch (_: Throwable) { null }
        generator = ProfileGenerator(engine)

        setupWebView()
        setupUi()
        applySettings()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)

        val startUrl = urlFromIntent(intent) ?: prefs.home
        val saved = Profile.parse(prefs.savedProfile)
        if (prefs.rotationMode == "manual" && saved != null) {
            applyProfile(saved)
            loadUrlSmart(startUrl)
        } else {
            rotateIdentity(wipe = prefs.wipeOnRotate && !deepWiped) { loadUrlSmart(startUrl) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { navigate(it) }
    }

    override fun onStart() {
        super.onStart()
        internalNavigation = false
        if (wentToBackground) {
            wentToBackground = false
            if (prefs.rotationMode == "launch") rotateIdentity(notify = true)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (settingsDirty) {
            settingsDirty = false
            applySettings()
            rotateIdentity(wipe = false, notify = true) { webView.url?.let { loadUrlSmart(it) } }
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        if (internalNavigation && !isFinishing) return
        wentToBackground = true
        if (isFinishing) {
            if (prefs.wipeOnExit) Cleaner.wipe(this, webView, clearHistory = true)
        } else if (prefs.wipeOnBackground) {
            Cleaner.wipe(this, webView)
        }
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (isFinishing) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.domStorageEnabled = true
        s.setGeolocationEnabled(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = 100 // размер системного шрифта — тоже отпечаток
        s.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        s.saveFormData = false
        WebCompat.removeRequestedWithHeader(s)

        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webView.isLongClickable = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                return when (scheme) {
                    "http", "https" -> {
                        if (!request.isForMainFrame) return false
                        val original = uri.toString()
                        val url = if (prefs.stripTracking) UrlUtil.stripTracking(original) else original
                        // POST сюда не попадает (по документации WebView), поэтому формы не ломаются.
                        // Переходы, которые сайт делает сам на тот же домен (например, Cloudflare
                        // после проверки), личность не меняют — иначе проверка зациклится.
                        val newHost = uri.host ?: ""
                        val curHost = Uri.parse(view.url ?: "").host ?: ""
                        val userInitiated = request.hasGesture() || !newHost.equals(curHost, true)
                        if (prefs.rotationMode == "pageload" && !request.isRedirect && userInitiated && !isCompatHost(newHost)) {
                            rotateIdentity { loadUrlSmart(url) }
                            return true
                        }
                        if (url != original) {
                            loadUrlSmart(url)
                            return true
                        }
                        false
                    }
                    "about", "data", "blob", "javascript" -> false
                    "file", "content" -> true // локальные файлы сайтам не отдаём
                    else -> {
                        openExternal(uri.toString())
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // фрейм Cloudflare/hCaptcha требует сторонние куки — разрешаем их на время проверки
                val host = request.url.host ?: return null
                if (!challengeCookies && prefs.autoChallengeCookies &&
                    (host == "challenges.cloudflare.com" || host.endsWith("hcaptcha.com"))) {
                    runOnUiThread { enableChallengeCookies() }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!docStartSupported) fallbackJs?.let { view.evaluateJavascript(it, null) }
                val host = url?.let { Uri.parse(it).host }
                if (challengeCookies && host != null && !host.equals(challengeCookiesHost, true)) {
                    challengeCookies = false
                    challengeCookiesHost = null
                    applyCookiePolicy()
                }
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                progress.visibility = View.GONE
                if (url != null) checkChallengeLater(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny() // камера, микрофон, MIDI, DRM-идентификаторы — всем отказ
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    internalNavigation = true
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                (window.decorView as FrameLayout).addView(
                    view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, contentDisposition, mimeType)
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    /** Применяет «личность» к WebView: UA, Client Hints, скрипт подмены. */
    private fun applyProfile(p: Profile) {
        profile = p
        prefs.savedProfile = p.json.toString()
        val s = webView.settings
        s.userAgentString = p.userAgent
        val nativeUaData = p.uaData != null && WebCompat.setUserAgentMetadata(
            s, p.uaData, p.mobile && !p.desktopMode,
            if (p.mobile) "Android" else "Windows"
        )

        val js = SpoofScript.build(this, p, prefs, nativeUaData, fallback = !docStartSupported)
        scriptHandler?.remove()
        scriptHandler = null
        if (docStartSupported) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
            fallbackJs = null
        } else {
            fallbackJs = js
        }
        chip.text = p.shortLabel
    }

    /** Новая личность. При необходимости сначала стираем всё, потом применяем и продолжаем. */
    private fun rotateIdentity(wipe: Boolean = prefs.wipeOnRotate, notify: Boolean = false, then: (() -> Unit)? = null) {
        val p = generator.generate(prefs)
        val doApply: () -> Unit = {
            applyProfile(p)
            if (notify) toast(getString(R.string.toast_new_identity, p.label))
            then?.invoke()
        }
        if (wipe) Cleaner.wipe(this, webView) { runOnUiThread { doApply() } } else doApply()
    }

    private fun applySettings() {
        val s = webView.settings
        s.javaScriptEnabled = prefs.javascript
        s.loadsImagesAutomatically = !prefs.blockImages
        s.blockNetworkImage = prefs.blockImages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = prefs.safeBrowsing

        applyCookiePolicy()

        if (prefs.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        applyProxy()
    }

    private fun applyCookiePolicy() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(challengeCookies || prefs.cookies != "none")
        cm.setAcceptThirdPartyCookies(webView, challengeCookies || prefs.cookies == "all")
    }

    private fun enableChallengeCookies() {
        if (challengeCookies) return
        challengeCookies = true
        challengeCookiesHost = webView.url?.let { Uri.parse(it).host }
        applyCookiePolicy()
    }

    // ------------------------------------------------------------------ совместимость с антиботами

    private fun currentHost(): String? = webView.url?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.")

    private fun isCompatHost(host: String?): Boolean {
        if (prefs.compatAll) return true
        val h = host?.lowercase() ?: return false
        return prefs.compatHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun toggleCompatForSite() {
        val host = currentHost() ?: return
        val set = prefs.compatHosts.toMutableSet()
        val on = if (set.remove(host)) false else { set.add(host); true }
        prefs.compatHosts = set
        profile?.let { applyProfile(it) } // пересобрать скрипт с новым списком сайтов
        toast(if (on) "Совместимость включена: $host" else "Совместимость выключена: $host")
        reload(rotate = false)
    }

    private val challengeJs = "(function(){var t=document.title||'';" +
        "var f=!!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage,script[src*=\"/cdn-cgi/challenge-platform/\"]');" +
        "return f||/just a moment|один момент|attention required|checking your browser|проверка браузера/i.test(t);})()"

    /**
     * Если через 8 секунд страница всё ещё показывает проверку Cloudflare — значит, не прошли.
     * Предлагаем включить совместимость для сайта.
     */
    private fun checkChallengeLater(url: String) {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return
        if (isCompatHost(host) || host in askedCompatHosts) return
        webView.postDelayed({
            if (isFinishing || webView.url != url) return@postDelayed
            webView.evaluateJavascript(challengeJs) { res ->
                if (res != "true" || host in askedCompatHosts || isCompatHost(host)) return@evaluateJavascript
                askedCompatHosts.add(host)
                AlertDialog.Builder(this)
                    .setTitle("Проверка Cloudflare не проходит?")
                    .setMessage("Сайт $host проверяет браузер. Включить для него совместимость? " +
                        "Подмена отпечатка внутри сайта отключится, но User-Agent, Client Hints, экран-профиль " +
                        "и «деменция» продолжат работать.")
                    .setPositiveButton("Включить") { _, _ -> toggleCompatForSite() }
                    .setNegativeButton("Не сейчас", null)
                    .show()
            }
        }, 8000)
    }

    private fun applyProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val p = prefs.proxy
        try {
            if (p.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) {}
            } else {
                // без addDirect(): если прокси упал, трафик не пойдёт напрямую в обход
                val cfg = ProxyConfig.Builder().addProxyRule(p).build()
                ProxyController.getInstance().setProxyOverride(cfg, mainExecutor) {}
            }
        } catch (e: Exception) {
            toast(getString(R.string.toast_proxy_bad))
        }
    }

    private fun loadUrlSmart(url: String) {
        val headers = HashMap<String, String>()
        profile?.acceptLanguage()?.let { headers["Accept-Language"] = it }
        webView.loadUrl(url, headers)
    }

    /** Переход по адресу, введённому пользователем или пришедшему извне. */
    private fun navigate(url: String) {
        val u = if (prefs.stripTracking) UrlUtil.stripTracking(url) else url
        if (prefs.rotationMode == "pageload") rotateIdentity { loadUrlSmart(u) } else loadUrlSmart(u)
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                val url = UrlUtil.toUrl(urlBar.text.toString(), prefs.searchTemplate)
                urlBar.clearFocus()
                hideKeyboard()
                navigate(url)
                true
            } else false
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) {
                if (prefs.rotationMode == "pageload") rotateIdentity { webView.goForward() } else webView.goForward()
            }
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { reload() }
        findViewById<ImageButton>(R.id.btnIdentity).setOnClickListener {
            rotateIdentity(notify = true) { reload(rotate = false) }
        }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        chip.setOnClickListener { showIdentityInfo() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun goBack() {
        if (!webView.canGoBack()) return
        if (prefs.rotationMode == "pageload") rotateIdentity { webView.goBack() } else webView.goBack()
    }

    private fun reload(rotate: Boolean = true) {
        val url = webView.url
        if (rotate && prefs.rotationMode == "pageload") {
            rotateIdentity { if (url != null) loadUrlSmart(url) else webView.reload() }
        } else {
            if (url != null && url != "about:blank") loadUrlSmart(url) else webView.reload()
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.main_menu, pm.menu)
        pm.menu.findItem(R.id.m_desktop).isChecked = prefs.desktopMode
        pm.menu.findItem(R.id.m_compat).isChecked = isCompatHost(currentHost())
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.m_new_identity -> rotateIdentity(notify = true) { reload(rotate = false) }
                R.id.m_desktop -> {
                    prefs.desktopMode = !prefs.desktopMode
                    rotateIdentity(notify = true) { reload(rotate = false) }
                }
                R.id.m_forget -> forgetEverything()
                R.id.m_compat -> toggleCompatForSite()
                R.id.m_identity_info -> showIdentityInfo()
                R.id.m_home -> navigate(prefs.home)
                R.id.m_settings -> {
                    internalNavigation = true
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.m_exit -> Cleaner.wipe(this, webView, clearHistory = true) { runOnUiThread { finishAndRemoveTask() } }
                R.id.c_creep -> navigate("https://abrahamjuliot.github.io/creepjs/")
                R.id.c_browserleaks -> navigate("https://browserleaks.com/")
                R.id.c_eff -> navigate("https://coveryourtracks.eff.org/")
                R.id.c_amiunique -> navigate("https://amiunique.org/fingerprint")
                R.id.c_pixelscan -> navigate("https://pixelscan.net/")
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        pm.show()
    }

    private fun forgetEverything() {
        Cleaner.wipe(this, webView, clearHistory = true) {
            runOnUiThread {
                rotateIdentity(wipe = false) {
                    toast(getString(R.string.toast_forgot))
                    loadUrlSmart(prefs.home)
                }
            }
        }
    }

    private fun showIdentityInfo() {
        val p = profile ?: return
        var msg = p.summary()
        currentHost()?.let { msg += "\nСовместимость для $it: " + if (isCompatHost(it)) "вкл (подмена JS отключена)" else "выкл" }
        if (!docStartSupported) msg += "\n\n" + getString(R.string.warn_no_docstart)
        val tv = TextView(this).apply {
            text = msg
            setTextIsSelectable(true)
            textSize = 13f
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.m_identity_info)
            .setView(scroll)
            .setPositiveButton(R.string.new_identity) { _, _ -> rotateIdentity(notify = true) { reload(rotate = false) } }
            .setNeutralButton("Копировать UA") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("User-Agent", p.userAgent))
                toast(getString(R.string.toast_copied))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun urlFromIntent(i: Intent?): String? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { UrlUtil.toUrl(it, prefs.searchTemplate) }
            else -> null
        }
    }

    private fun openExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:", true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // у intent:// бывает запасной http(s)-адрес
            val fallback = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (_: Exception) { null }
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) navigate(fallback)
            else toast(getString(R.string.toast_no_app))
        }
    }

    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.toast_download_fail)); return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
            mimeType?.let { req.setMimeType(it) }
            profile?.userAgent?.let { req.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setTitle(name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(getString(R.string.toast_download, name))
        } catch (e: Exception) {
            toast(getString(R.string.toast_download_fail))
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
