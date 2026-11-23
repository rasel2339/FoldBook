package com.rasel.foldbook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {

    companion object {
        /** Screens wider than this (dp) get the full desktop layout; narrower get the mobile site. */
        private const val WIDE_SCREEN_DP = 600

        private const val MOBILE_HOME = "https://m.facebook.com/"
        private const val DESKTOP_HOME = "https://www.facebook.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val FILE_CHOOSER_REQUEST = 1001

        /** Bangla font (Hind Siliguri, SIL OFL). Served from the app under a fake path on Facebook's own
         *  domain, so Facebook's security rules allow it. Only Bangla characters use it. */
        private const val FONT_PATH = "/__foldbook/fonts/"
        private val BANGLA_FONT_JS = run {
            val range = "U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC"
            // iPhone-like lighter look: each weight uses the next-lighter cut (normal text -> 300, bold -> 600).
            val weights = mapOf(100 to 300, 200 to 300, 300 to 300, 400 to 300, 500 to 400, 600 to 500, 700 to 600, 800 to 600, 900 to 700)
            val faces = weights.entries.joinToString("") { (w, file) ->
                "@font-face{font-family:'FoldBookBangla';src:url('$FONT_PATH" + "bn-$file.woff2') format('woff2');" +
                    "font-weight:$w;font-style:normal;font-display:swap;unicode-range:$range;}"
            }
            val css = faces +
                "html body,html body *:not(svg):not(path){font-family:'FoldBookBangla',system-ui,-apple-system," +
                "'Segoe UI',Roboto,Helvetica,Arial,sans-serif !important;}"
            "(function(){if(document.getElementById('foldbook-bn'))return;" +
                "var s=document.createElement('style');s.id='foldbook-bn';s.textContent=\"$css\";" +
                "(document.head||document.documentElement).appendChild(s);})();"
        }

        /** Links on these domains stay in the app; everything else opens in your browser. */
        private val INTERNAL_HOSTS = listOf("facebook.com", "fbcdn.net", "fb.com", "fb.me", "messenger.com")
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var mobileUa: String
    private var isWide: Boolean? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#18191A")) }
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        // GPU: hardware-accelerated window + WebView drawn on the GPU
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        requestHighestRefreshRate()

        webView.setLayerType(View.LAYER_TYPE_NONE, null) // let Chromium's own GPU compositor do the work
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // Keep the renderer at high priority so scrolling isn't throttled
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            offscreenPreRaster = true // pre-render content just off screen -> smoother scrolling
            cacheMode = WebSettings.LOAD_DEFAULT
            // Drop the "wv" marker so Facebook treats this like normal Chrome.
            mobileUa = userAgentString.replace("; wv", "")
            userAgentString = mobileUa
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                val internal = INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
                if (internal) return false
                // External link: open in the default browser.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val path = request.url.path ?: return null
                if (!path.startsWith(FONT_PATH)) return null
                val name = path.removePrefix(FONT_PATH)
                if (!name.matches(Regex("bn-\\d{3}\\.woff2"))) return null
                return try {
                    WebResourceResponse("font/woff2", null, assets.open("fonts/$name")).apply {
                        responseHeaders = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "max-age=31536000"
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(BANGLA_FONT_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(BANGLA_FONT_JS, null)
                CookieManager.getInstance().flush() // keep you logged in
            }
        }

        // Lets you pick photos/videos when posting.
        webView.webChromeClient = object : WebChromeClient() {
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
        }

        val startUrl = intent?.data?.toString()
        if (savedInstanceState != null) {
            isWide = wideNow()
            applyUserAgent(isWide!!)
            webView.restoreState(savedInstanceState)
        } else {
            applyMode(startUrl)
        }
    }

    /** Ask for the panel's highest refresh rate (120Hz on Mix Fold 3) at the current resolution. */
    private fun requestHighestRefreshRate() {
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

    private fun wideNow() = resources.configuration.screenWidthDp >= WIDE_SCREEN_DP

    private fun applyUserAgent(wide: Boolean) {
        webView.settings.userAgentString = if (wide) DESKTOP_UA else mobileUa
    }

    /** Unfolded: full-screen desktop layout. Folded: mobile layout. Reloads only when the mode changes. */
    private fun applyMode(startUrl: String? = null) {
        val wide = wideNow()
        if (wide == isWide && startUrl == null) return
        isWide = wide
        applyUserAgent(wide)

        val current = startUrl ?: webView.url
        val target = when {
            current.isNullOrBlank() -> if (wide) DESKTOP_HOME else MOBILE_HOME
            wide -> current.replace("://m.facebook.com", "://www.facebook.com")
                .replace("://mbasic.facebook.com", "://www.facebook.com")
            else -> current.replace("://www.facebook.com", "://m.facebook.com")
                .replace("://web.facebook.com", "://m.facebook.com")
        }
        webView.loadUrl(target)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestHighestRefreshRate() // inner and outer screens have different modes
        applyMode() // runs every time you fold/unfold
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { applyMode(it.toString()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
