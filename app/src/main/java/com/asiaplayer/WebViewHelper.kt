package com.asiaplayer

import android.net.http.SslError
import android.webkit.*

object WebViewHelper {

    fun setup(webView: WebView) {
        webView.settings.apply {
            // JavaScript کامل
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // دسترسی
            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            // Mixed Content
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Cache
            cacheMode = WebSettings.LOAD_DEFAULT

            // Media
            mediaPlaybackRequiresUserGesture = false

            // Zoom
            setSupportZoom(false)
            builtInZoomControls = false

            // User Agent - Chrome Mobile واقعی
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
        }

        // Cookie کامل
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // WebChrome برای alert و console
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm(); return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm(); return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // SSL خطا رو قبول کن
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // خطا رو نادیده بگیر
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                // HTTP خطا رو نادیده بگیر
            }
        }
    }
}
