package com.dmanime.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dmanime.sniff.MediaKind
import com.dmanime.sniff.MediaUrls
import com.dmanime.sniff.SniffScript
import com.dmanime.sniff.SniffSession
import kotlinx.serialization.json.JsonPrimitive

/**
 * WebView 嗅探器：负责「加载播放页 + 注入脚本 + 收候选地址」。
 *
 * 策略是「按需创建、用完销毁」——WebView 常驻会白吃 100MB+ 内存。
 */
@SuppressLint("SetJavaScriptEnabled")
class PageSniffer(
    context: Context,
    private val onCandidate: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val session = SniffSession()

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = DEFAULT_UA
        // 隐藏嗅探页绝不允许弹窗/新窗口
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        // 必须在 loadUrl 之前挂桥，否则页面早期的 fetch 会被漏掉
        addJavascriptInterface(Bridge(), SniffScript.BRIDGE_NAME)

        webViewClient = object : WebViewClient() {
            /**
             * 只放行 http/https。站点常用 intent://、market://、tbopen://、snssdk://
             * 之类把用户甩到别的 App（实测被跳到某个"异次元APP"的落地页），
             * 嗅探页是隐藏的，跳出去用户会一脸懵。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = request.url.scheme?.lowercase()
                val allowed = scheme == "http" || scheme == "https"
                if (!allowed) onStatus("已拦截跳转: ${request.url}")
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 尽早注入：部分播放器一加载就取流，等 onPageFinished 已经晚了。
                // 这个时机执行的脚本会随文档重建消失，所以 onPageFinished 还要再来一次。
                view.evaluateJavascript(SniffScript.script, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onStatus("页面加载完成: $url")
                view.evaluateJavascript(SniffScript.script, null)
            }

            /**
             * 兜底：JS 注入再早也可能赶不上首个请求，而 WebView 的网络层能看到
             * 所有子资源请求（含 XHR）。这里只上报、不拦截，不影响页面正常加载。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                // 只关心媒体地址；主文档、favicon、CSS/JS 都会走到这里，
                // 不过滤的话日志会被 UNKNOWN 刷屏。
                if (url.isNotEmpty() && MediaUrls.classify(url) != MediaKind.UNKNOWN) {
                    val message = """{"url":${JsonPrimitive(url)},"from":"intercept"}"""
                    val candidate = session.accept(message)
                    if (candidate != null) main.post { onCandidate(candidate.url) }
                }
                return null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    onStatus("主文档加载失败(${error.errorCode}): ${request.url}")
                }
            }
        }
    }

    fun load(url: String) {
        session.reset()
        onStatus("加载播放页: $url")
        webView.loadUrl(url, mapOf("Referer" to url))
    }

    fun destroy() {
        // 必须先摘掉，直接对还挂在窗口上的 WebView 调 destroy 会留下
        // "Application attempted to call on a destroyed WebView" 之类的崩溃。
        runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeJavascriptInterface(SniffScript.BRIDGE_NAME) }
        runCatching { webView.destroy() }
    }

    inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            val candidate = session.accept(message) ?: return
            main.post { onCandidate(candidate.url) }
        }
    }
}
