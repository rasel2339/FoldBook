package com.rasel.foldbook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        private const val MOBILE_HOME = "https://m.facebook.com/"
        private const val DESKTOP_HOME = "https://www.facebook.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Panes at least this wide (dp) get the desktop layout in "auto" mode. */
        private const val DESKTOP_MIN_DP = 600
        /** Screens at least this wide (dp) count as unfolded. */
        private const val UNFOLDED_MIN_DP = 600
        /** Share of the width the feed gets in split view. */
        private const val MAIN_PANE_SHARE = 0.62f

        private const val FILE_CHOOSER_REQUEST = 1001
        private const val LOCK_REQUEST = 1002
        private const val RELOCK_AFTER_MS = 30_000L

        private const val FONT_PATH = "/__foldbook/fonts/"
        private val FONT_FILE = Regex("(bn|en)-\\d{3}\\.woff2")
        private const val BN_RANGE = "U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC"
        private const val EN_RANGE =
            "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"

        private val INTERNAL_HOSTS = listOf("facebook.com", "fbcdn.net", "fb.com", "fb.me", "messenger.com")

        // Settings keys
        private const val K_LAYOUT = "layout"          // auto | mobile | desktop
        private const val K_SPLIT = "split"
        private const val K_SIDE = "side"              // notifications | messages
        private const val K_PULL = "pull_refresh"
        private const val K_ZOOM = "text_zoom"
        private const val K_BN_FONT = "bn_font"
        private const val K_LIGHT = "light_font"
        private const val K_EN_FONT = "en_font"
        private const val K_AMOLED = "amoled"
        private const val K_HIDE_ADS = "hide_sponsored"
        private const val K_HIDE_REELS = "hide_reels"
        private const val K_HIDE_STORIES = "hide_stories"
        private const val K_LOCK = "app_lock"

        /** Injected into every Facebook page: styles + best-effort hiding of sponsored posts / reels. */
        private const val INJECT_JS = """
(function(){
  var css = %CSS%; var cfg = %CFG%;
  var s = document.getElementById('foldbook-style');
  if (!s) { s = document.createElement('style'); s.id = 'foldbook-style'; (document.head || document.documentElement).appendChild(s); }
  s.textContent = css;
  window.__fbCfg = cfg;
  function unit(el){ return el.closest('[data-pagelet^="FeedUnit"]') || el.closest('div[role="article"]') || el.closest('[data-tracking-duration-id]'); }
  function hide(el){ var u = unit(el); if (u && u.style.display !== 'none') u.style.display = 'none'; }
  function scan(){
    var c = window.__fbCfg || {};
    if (c.sponsored) {
      document.querySelectorAll('a[href*="/ads/about"],a[aria-label="Sponsored"],[aria-label="Sponsored"],[aria-label="স্পনসর্ড"]').forEach(hide);
      document.querySelectorAll('a span, span span').forEach(function(e){
        if (e.childElementCount === 0) { var t = (e.textContent || '').trim(); if (t === 'Sponsored' || t === 'স্পনসর্ড') hide(e); }
      });
    }
    if (c.reels) { document.querySelectorAll('a[href*="/reel/"],a[href*="/reels/"]').forEach(hide); }
    if (c.amoled && document.body) {
      var m = (getComputedStyle(document.body).backgroundColor || '').match(/\d+/g);
      if (m && (+m[0] + +m[1] + +m[2]) < 180) document.documentElement.classList.add('fb-amoled');
    }
  }
  if (!window.__fbObs) {
    window.__fbObs = new MutationObserver(function(){ clearTimeout(window.__fbT); window.__fbT = setTimeout(scan, 400); });
    window.__fbObs.observe(document.documentElement, { childList: true, subtree: true });
  }
  scan();
})();
"""
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var row: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private var sideWebView: WebView? = null
    private var divider: View? = null
    private lateinit var settingsButton: TextView

    private lateinit var mobileUa: String
    private var isDesktop: Boolean? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var lockOverlay: View? = null
    private var unlocked = false
    private var authInProgress = false
    private var backgroundedAt = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        requestHighestRefreshRate()

        root = FrameLayout(this)
        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        webView = createWebView(isSide = false)
        mobileUa = webView.settings.userAgentString.replace("; wv", "")
        webView.settings.userAgentString = mobileUa

        refresh = SwipeRefreshLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setColorSchemeColors(Color.parseColor("#4F46E5"))
            setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
            setOnRefreshListener {
                webView.reload()
                postDelayed({ isRefreshing = false }, 8000)
            }
        }
        row.addView(refresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        settingsButton = TextView(this).apply {
            text = "⚙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            alpha = 0.55f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#CC312E81"))
            }
            setOnClickListener { showSettings() }
        }
        val size = dp(42)
        root.addView(settingsButton, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), 0, 0, dp(14))
        })

        setContentView(root)
        applyLook()
        updateLayout()

        if (savedInstanceState != null) {
            isDesktop = wantDesktop()
            applyUserAgent(webView, isDesktop!!)
            webView.restoreState(savedInstanceState)
        } else {
            applyMode(force = true, startUrl = intent?.data?.toString())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestHighestRefreshRate() // inner and outer screens have different modes
        updateLayout()
        applyMode(force = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { applyMode(force = true, startUrl = it.toString()) }
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) unlocked = false
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        sideWebView?.onResume()
        lockIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipMode()) {
            webView.onPause()
            sideWebView?.onPause()
        }
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        sideWebView?.destroy()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> customViewCallback?.onCustomViewHidden()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_REQUEST -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            LOCK_REQUEST -> {
                authInProgress = false
                if (resultCode == RESULT_OK) unlock()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------------------------------------------------------------- web views

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(isSide: Boolean): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            offscreenPreRaster = true
            textZoom = prefs.getInt(K_ZOOM, 100)
        }
        wv.setLayerType(View.LAYER_TYPE_NONE, null)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                return openExternal(uri)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                fontResponse(request)

            override fun onPageCommitVisible(view: WebView, url: String?) = inject(view)

            override fun onPageFinished(view: WebView, url: String?) {
                inject(view)
                if (!isSide) refresh.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!isSide || url == null) return
                // Something tapped in the side pane (e.g. a notification) opens in the main feed pane.
                if (!sideStays(Uri.parse(url))) {
                    webView.loadUrl(mapForMain(url))
                    view.post { view.loadUrl(sideHomeUrl()) }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
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
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.setBackgroundColor(Color.BLACK)
                settingsButton.visibility = View.GONE
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customViewCallback = null
                settingsButton.visibility = View.VISIBLE
                setFullscreen(false)
            }
        }

        // Long-press an image -> save it to the gallery.
        wv.setOnLongClickListener {
            val type = wv.hitTestResult.type
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val fallback = wv.hitTestResult.extra
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val src = msg.data.getString("src") ?: fallback
                    if (src != null) confirmSaveImage(src, wv.settings.userAgentString)
                    true
                }
                wv.requestImageRef(handler.obtainMessage())
                true
            } else {
                false
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, URLUtil.guessFileName(url, contentDisposition, mimeType), Environment.DIRECTORY_DOWNLOADS)
        }
        return wv
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host ?: return true
        return INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri)); true
    } catch (e: Exception) {
        false
    }

    private fun fontResponse(request: WebResourceRequest): WebResourceResponse? {
        val path = request.url.path ?: return null
        if (!path.startsWith(FONT_PATH)) return null
        val name = path.removePrefix(FONT_PATH)
        if (!name.matches(FONT_FILE)) return null
        return try {
            WebResourceResponse("font/woff2", null, assets.open("fonts/$name")).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- layout & modes

    private fun unfolded() = resources.configuration.screenWidthDp >= UNFOLDED_MIN_DP

    private fun splitActive() = prefs.getBoolean(K_SPLIT, true) && unfolded()

    private fun wantDesktop(): Boolean {
        val mainWidth = resources.configuration.screenWidthDp * (if (splitActive()) MAIN_PANE_SHARE else 1f)
        return when (prefs.getString(K_LAYOUT, "auto")) {
            "desktop" -> true
            "mobile" -> false
            else -> mainWidth >= DESKTOP_MIN_DP
        }
    }

    private fun applyUserAgent(wv: WebView, desktop: Boolean) {
        wv.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa
    }

    private fun toDesktop(url: String) = url
        .replace("://m.facebook.com", "://www.facebook.com")
        .replace("://mbasic.facebook.com", "://www.facebook.com")

    private fun toMobile(url: String) = url
        .replace("://www.facebook.com", "://m.facebook.com")
        .replace("://web.facebook.com", "://m.facebook.com")

    private fun mapForMain(url: String) = if (isDesktop == true) toDesktop(url) else toMobile(url)

    /** Main pane: desktop or mobile site. Reloads only when the mode changes (or when forced). */
    private fun applyMode(force: Boolean, startUrl: String? = null) {
        val desktop = wantDesktop()
        if (!force && startUrl == null && desktop == isDesktop) return
        isDesktop = desktop
        applyUserAgent(webView, desktop)
        val current = startUrl ?: webView.url
        val target = when {
            current.isNullOrBlank() -> if (desktop) DESKTOP_HOME else MOBILE_HOME
            desktop -> toDesktop(current)
            else -> toMobile(current)
        }
        webView.loadUrl(target)
    }

    private fun sideIsMessages() = prefs.getString(K_SIDE, "notifications") == "messages"

    private fun sideHomeUrl() =
        if (sideIsMessages()) "https://www.facebook.com/messages/" else "https://m.facebook.com/notifications.php"

    private fun sideStays(uri: Uri): Boolean {
        val path = uri.path ?: return true
        if (!isInternal(uri)) return true
        if (path.contains("login") || path.contains("checkpoint")) return true
        return if (sideIsMessages()) path.startsWith("/messages") || path.startsWith("/t/")
        else path.startsWith("/notifications")
    }

    /** Adds or removes the side pane (notifications / messages) depending on fold state and settings. */
    private fun updateLayout() {
        val split = splitActive()
        (refresh.layoutParams as LinearLayout.LayoutParams).weight = if (split) MAIN_PANE_SHARE else 1f
        if (split && sideWebView == null) {
            val d = View(this).apply { setBackgroundColor(Color.parseColor("#3A3B3C")) }
            row.addView(d, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            divider = d
            val side = createWebView(isSide = true)
            applyUserAgent(side, sideIsMessages())
            row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - MAIN_PANE_SHARE))
            sideWebView = side
            side.loadUrl(sideHomeUrl())
        } else if (!split && sideWebView != null) {
            sideWebView?.let { row.removeView(it); it.destroy() }
            divider?.let { row.removeView(it) }
            sideWebView = null
            divider = null
        }
        row.requestLayout()
    }

    // ---------------------------------------------------------------- look: fonts, dark, hiding

    private fun applyLook() {
        val bg = if (prefs.getBoolean(K_AMOLED, false)) Color.BLACK else Color.parseColor("#18191A")
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        refresh.isEnabled = prefs.getBoolean(K_PULL, true)
        val zoom = prefs.getInt(K_ZOOM, 100)
        webView.settings.textZoom = zoom
        sideWebView?.settings?.textZoom = zoom
    }

    private fun buildCss(): String {
        val sb = StringBuilder()
        val light = prefs.getBoolean(K_LIGHT, true)
        val families = mutableListOf<String>()
        val available = listOf(300, 400, 500, 600, 700)

        fun faces(family: String, prefix: String, range: String) {
            for (w in 100..900 step 100) {
                val target = if (light) w - 100 else w
                val file = available.minByOrNull { abs(it - target) }!!
                sb.append("@font-face{font-family:'").append(family).append("';src:url('")
                    .append(FONT_PATH).append(prefix).append('-').append(file).append(".woff2') format('woff2');")
                    .append("font-weight:").append(w).append(";font-style:normal;font-display:swap;unicode-range:")
                    .append(range).append(";}")
            }
            families.add("'$family'")
        }
        if (prefs.getBoolean(K_BN_FONT, true)) faces("FoldBookBangla", "bn", BN_RANGE)
        if (prefs.getBoolean(K_EN_FONT, false)) faces("FoldBookLatin", "en", EN_RANGE)
        if (families.isNotEmpty()) {
            sb.append("html body,html body *:not(svg):not(path):not(i){font-family:")
                .append(families.joinToString(","))
                .append(",system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif !important;}")
        }
        if (prefs.getBoolean(K_AMOLED, false)) {
            sb.append(".__fb-dark-mode,html.__fb-dark-mode,html.__fb-dark-mode :root{")
                .append("--web-wash:#000!important;--surface-background:#000!important;--card-background:#000!important;")
                .append("--card-background-flat:#000!important;--secondary-surface-background:#000!important;")
                .append("--comment-background:#121212!important;--wash:#121212!important;}")
                .append("html.fb-amoled,html.fb-amoled body{background:#000!important;}")
        }
        if (prefs.getBoolean(K_HIDE_STORIES, false)) {
            sb.append("[aria-label='Stories'],[aria-label='স্টোরি'],[aria-label='Stories tray'],[data-pagelet='Stories']{display:none!important;}")
        }
        return sb.toString()
    }

    private fun injectJs(): String {
        val cfg = JSONObject()
            .put("sponsored", prefs.getBoolean(K_HIDE_ADS, false))
            .put("reels", prefs.getBoolean(K_HIDE_REELS, false))
            .put("amoled", prefs.getBoolean(K_AMOLED, false))
        return INJECT_JS
            .replace("%CSS%", JSONObject.quote(buildCss()))
            .replace("%CFG%", cfg.toString())
    }

    private fun inject(view: WebView) {
        val url = view.url ?: return
        if (!isInternal(Uri.parse(url))) return
        view.evaluateJavascript(injectJs(), null)
    }

    // ---------------------------------------------------------------- settings screen

    private fun showSettings() {
        val pad = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, dp(8))
        }

        fun header(text: String) = content.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#A5B4FC"))
            setPadding(0, dp(16), 0, dp(4))
        })

        fun switch(text: String, key: String, default: Boolean): Switch {
            val s = Switch(this).apply {
                this.text = text
                isChecked = prefs.getBoolean(key, default)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(6), 0, dp(6))
            }
            content.addView(s)
            return s
        }

        fun radios(options: List<Pair<String, String>>, key: String, default: String): RadioGroup {
            val g = RadioGroup(this)
            val current = prefs.getString(key, default)
            options.forEachIndexed { i, (value, label) ->
                val rb = RadioButton(this).apply { id = 100 + i; text = label; tag = value }
                g.addView(rb)
                if (value == current) g.check(rb.id)
            }
            content.addView(g)
            return g
        }

        header("লেআউট")
        val layoutGroup = radios(
            listOf("auto" to "অটো (আনফোল্ডে ডেস্কটপ)", "mobile" to "সবসময় মোবাইল", "desktop" to "সবসময় ডেস্কটপ"),
            K_LAYOUT, "auto"
        )
        val split = switch("আনফোল্ডে স্প্লিট ভিউ", K_SPLIT, true)
        val sideGroup = radios(
            listOf("notifications" to "পাশে: নোটিফিকেশন", "messages" to "পাশে: মেসেজ"),
            K_SIDE, "notifications"
        )
        val pull = switch("টেনে রিফ্রেশ", K_PULL, true)

        header("লেখা")
        val zoomLabel = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        content.addView(zoomLabel)
        val zoom = SeekBar(this).apply {
            max = 80 // 70%..150%
            progress = prefs.getInt(K_ZOOM, 100) - 70
            zoomLabel.text = "লেখার সাইজ: ${progress + 70}%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    zoomLabel.text = "লেখার সাইজ: ${p + 70}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        content.addView(zoom)
        val bnFont = switch("বাংলা ফন্ট (Hind Siliguri)", K_BN_FONT, true)
        val light = switch("হালকা ফন্ট (iPhone-এর মতো)", K_LIGHT, true)
        val enFont = switch("ইংরেজি ফন্ট (Montserrat)", K_EN_FONT, false)

        header("দেখতে")
        val amoled = switch("AMOLED কালো ডার্ক মোড", K_AMOLED, false)
        content.addView(TextView(this).apply {
            text = "Facebook-এর নিজের Dark mode চালু থাকলে কাজ করবে"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
        })

        header("ফিড")
        val hideAds = switch("Sponsored পোস্ট লুকাও", K_HIDE_ADS, false)
        val hideReels = switch("Reels লুকাও", K_HIDE_REELS, false)
        val hideStories = switch("Stories লুকাও", K_HIDE_STORIES, false)

        header("নিরাপত্তা")
        val lock = switch("অ্যাপ লক (ফিঙ্গারপ্রিন্ট / স্ক্রিন লক)", K_LOCK, false)

        content.addView(TextView(this).apply {
            text = "FoldBook ${packageManager.getPackageInfo(packageName, 0).versionName} · ছবিতে লং-প্রেস করলে সেভ হবে"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("FoldBook সেটিংস")
            .setView(scroll)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("সেভ") { _, _ ->
                val oldSide = sideIsMessages()
                prefs.edit()
                    .putString(K_LAYOUT, layoutGroup.findViewById<RadioButton>(layoutGroup.checkedRadioButtonId)?.tag as? String ?: "auto")
                    .putBoolean(K_SPLIT, split.isChecked)
                    .putString(K_SIDE, sideGroup.findViewById<RadioButton>(sideGroup.checkedRadioButtonId)?.tag as? String ?: "notifications")
                    .putBoolean(K_PULL, pull.isChecked)
                    .putInt(K_ZOOM, zoom.progress + 70)
                    .putBoolean(K_BN_FONT, bnFont.isChecked)
                    .putBoolean(K_LIGHT, light.isChecked)
                    .putBoolean(K_EN_FONT, enFont.isChecked)
                    .putBoolean(K_AMOLED, amoled.isChecked)
                    .putBoolean(K_HIDE_ADS, hideAds.isChecked)
                    .putBoolean(K_HIDE_REELS, hideReels.isChecked)
                    .putBoolean(K_HIDE_STORIES, hideStories.isChecked)
                    .putBoolean(K_LOCK, lock.isChecked)
                    .apply()
                if (lock.isChecked) unlocked = true // just set by the owner; don't lock immediately
                if (oldSide != sideIsMessages()) {
                    sideWebView?.let { applyUserAgent(it, sideIsMessages()); it.loadUrl(sideHomeUrl()) }
                }
                applyLook()
                updateLayout()
                applyMode(force = true)
                sideWebView?.reload()
                Toast.makeText(this, "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------------------------------------------------------------- image save & downloads

    private fun confirmSaveImage(src: String, userAgent: String) {
        if (!src.startsWith("http")) {
            Toast.makeText(this, "এই ছবিটা সেভ করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ছবিটা সেভ করবে?")
            .setPositiveButton("সেভ") { _, _ ->
                var name = URLUtil.guessFileName(src, null, "image/jpeg")
                if (!name.contains('.')) name += ".jpg"
                download(src, userAgent, "FoldBook_${System.currentTimeMillis()}_$name", Environment.DIRECTORY_PICTURES)
            }
            .setNegativeButton("না", null)
            .show()
    }

    private fun download(url: String, userAgent: String, fileName: String, dir: String) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, "এটা ডাউনলোড করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(dir, "FoldBook/$fileName")
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "সেভ হচ্ছে…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "সেভ করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- video: fullscreen & picture-in-picture

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun isInPipMode() = isInPictureInPictureMode

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the app while a video is full screen -> keep it playing in a small window.
        if (customView != null) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { /* PiP not allowed */ }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        settingsButton.visibility = if (isInPictureInPictureMode || customView != null) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- app lock

    private fun lockIfNeeded() {
        if (!prefs.getBoolean(K_LOCK, false)) { unlocked = true; removeLockOverlay(); return }
        if (unlocked) { removeLockOverlay(); return }
        showLockOverlay()
        if (!authInProgress) authenticate()
    }

    private fun showLockOverlay() {
        if (lockOverlay != null) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(TextView(this@MainActivity).apply {
                text = "🔒\nআনলক করতে চাপো"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { if (!authInProgress) authenticate() }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lockOverlay = overlay
    }

    private fun removeLockOverlay() {
        lockOverlay?.let { root.removeView(it) }
        lockOverlay = null
    }

    private fun unlock() {
        unlocked = true
        removeLockOverlay()
    }

    private fun authenticate() {
        authInProgress = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val builder = BiometricPrompt.Builder(this)
                .setTitle("FoldBook আনলক")
                .setSubtitle("ফিঙ্গারপ্রিন্ট বা স্ক্রিন লক দিয়ে খোলো")
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
                else -> builder.setNegativeButton("বাতিল", mainExecutor) { _, _ -> authInProgress = false }
            }
            builder.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    authInProgress = false
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    authInProgress = false
                    // No fingerprint / screen lock set up on the phone: don't lock the user out.
                    if (errorCode in setOf(1, 11, 12, 14)) {
                        unlock()
                        Toast.makeText(this@MainActivity, "ফোনে কোনো লক সেট নেই, তাই অ্যাপ লক কাজ করছে না", Toast.LENGTH_LONG).show()
                    }
                }
            })
        } else {
            val km = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = km?.createConfirmDeviceCredentialIntent("FoldBook আনলক", null)
            if (intent == null) {
                authInProgress = false
                unlock()
            } else {
                startActivityForResult(intent, LOCK_REQUEST)
            }
        }
    }

    // ---------------------------------------------------------------- misc

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Ask for the panel's highest refresh rate (120Hz on Mix Fold 3) at the current resolution. */
    private fun requestHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        attrs.preferredRefreshRate = best.refreshRate
        window.attributes = attrs
    }
}
