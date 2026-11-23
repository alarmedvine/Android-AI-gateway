package dev.aigw.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.gson.JsonObject
import dev.aigw.core.provider.newapi.CheckinCookie

/**
 * 应用内网页登录：为「签到站点」拿到会话 cookie。
 *
 * ## 为什么必须是应用内 WebView
 *
 * 签到站点的账号是用 GitHub / LinuxDO 的 OAuth 注册的，New API 给这类用户的是随机密码，
 * 用户并不知道；而 OAuth 授权页必须有人在场。**系统浏览器完成 OAuth 后 cookie 留在浏览器里，
 * 应用读不到**（进程隔离），所以只能让 WebView 自己做完整流程，再从 CookieManager 取。
 *
 * 这与 `ProviderDetailScreen` 里「登录一律交给系统浏览器」的策略并不冲突：那边要的是
 * 回调 URL（本地回环端口接），这边要的是 cookie 本身，两条路径的目标不同。
 *
 * ## 只做登录，不做签到
 *
 * 登录成功后立刻把 cookie 交给 `GatewayEngine.importAccountJson`，随后 finish。
 * 每日签到由 `AutoCheckin` 在后台用这份 cookie 直接打上游，不再经过 WebView。
 */
class CheckinWebLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var finished = false
    private var importing = false
    private val handler = Handler(Looper.getMainLooper())

    private val pollCookies = object : Runnable {
        override fun run() {
            if (finished || importing) return
            if (tryComplete()) return
            handler.postDelayed(this, COOKIE_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        val siteHost = intent.getStringExtra(EXTRA_SITE_HOST).orEmpty()
        if (providerId.isEmpty() || loginUrl.isEmpty() || siteHost.isEmpty()) {
            Toast.makeText(this, "登录参数缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this)
        val progress = ProgressBar(this).apply {
            // 顶部细进度条：页面加载时给一个「在做事」的反馈
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt(),
            )
        }
        val web = WebView(this)
        webView = web
        root.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(progress)
        setContentView(root)

        configure(web)
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 首页/控制台一出现就试一次：session 通常在这时已经写进 CookieManager
                if (url != null && isTargetPage(url, siteHost)) handler.post { tryComplete() }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && isTargetPage(url, siteHost)) handler.post { tryComplete() }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                // 自定义 scheme（如某些 OAuth 中转）直接放弃，避免 WebView 白屏卡住
                val url = request.url?.toString().orEmpty()
                return url.startsWith("intent:") || url.startsWith("market:")
            }
        }
        web.loadUrl(loginUrl)
        handler.postDelayed(pollCookies, COOKIE_POLL_MS)
    }

    private fun configure(web: WebView) {
        @SuppressLint("SetJavaScriptEnabled")
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 渲染修复：作者废弃内嵌 WebView 的三个症状（整页缩放错乱 / 输入框文字镜像）
        // 分别来自「概览模式缩放」「RTL 布局继承」「文本缩放不为 100」。
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.layoutDirection = View.LAYOUT_DIRECTION_LTR
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    /** 只有回到站点自己的页面（不是 OAuth 中间页）才认为流程走完。 */
    private fun isTargetPage(url: String, siteHost: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains(siteHost)) return false
        return !lower.contains("/oauth/") && !lower.contains("/api/oauth/")
    }

    /**
     * 读 cookie 并尝试导入账号。
     *
     * 导入本身要打一次上游（校验 cookie 并拿 uid），所以放到后台线程；
     * 期间用 [importing] 挡住轮询重入。返回 true 表示「已经发起一次导入」，
     * 调用方据此停止本轮轮询（下一轮由导入结束后的状态决定）。
     */
    private fun tryComplete(): Boolean {
        if (finished || importing) return false
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val siteHost = intent.getStringExtra(EXTRA_SITE_HOST).orEmpty()
        val cookieText = CookieManager.getInstance().getCookie("https://$siteHost").orEmpty()
        val cookies = CheckinCookie.parse(cookieText)
        if (!cookies.containsKey("session")) return false

        importing = true
        val raw = JsonObject().apply {
            addProperty("cookies", CheckinCookie.format(cookies))
        }.toString()
        Thread({
            val engine = (application as AiGatewayApp).engine
            val outcome = runCatching { engine?.importAccountJson(providerId, raw) }
            handler.post {
                importing = false
                val result = outcome.getOrNull()
                when {
                    result == null -> {
                        Toast.makeText(this, "网关尚未初始化", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    result.ok -> {
                        Toast.makeText(
                            this,
                            "已添加：${result.nickname.ifEmpty { result.uid }}",
                            Toast.LENGTH_LONG,
                        ).show()
                        finished = true
                        setResult(RESULT_OK)
                        finish()
                    }
                    else -> {
                        // cookie 拿到了但上游不认（多半是授权还没真正完成），继续等下一轮
                        Log.w(TAG, "导入失败，继续等待：${result.error}")
                    }
                }
            }
        }, "checkin-web-login").start()
        return true
    }

    @Deprecated("WebView 需要处理返回栈；Android 13+ 的返回键回调由系统派发到这个方法")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) {
            web.goBack()
            return
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.apply {
            stopLoading()
            webViewClient = null
            webChromeClient = null
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CheckinWebLogin"
        private const val COOKIE_POLL_MS = 1000L

        const val EXTRA_PROVIDER_ID = "providerId"
        const val EXTRA_LOGIN_URL = "loginUrl"
        const val EXTRA_SITE_HOST = "siteHost"

        fun intent(context: Context, providerId: String, loginUrl: String, siteHost: String): Intent =
            Intent(context, CheckinWebLoginActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_ID, providerId)
                putExtra(EXTRA_LOGIN_URL, loginUrl)
                putExtra(EXTRA_SITE_HOST, siteHost)
            }
    }
}
