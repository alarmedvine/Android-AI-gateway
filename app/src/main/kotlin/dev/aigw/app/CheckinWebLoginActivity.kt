package dev.aigw.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.gson.JsonObject
import dev.aigw.core.gateway.AccountImportGate
import dev.aigw.core.provider.newapi.CheckinCookie
import dev.aigw.core.provider.newapi.CheckinLoginPolicy
import dev.aigw.core.provider.newapi.NewApiSiteProfile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * 临时站点登录，不复用前一次账号的浏览器状态。
 * CookieManager 是进程共享的，因此一次只允许一个登录；清理回调完成才加载站点。
 * 登录身份从当前站点页面取得，仍由 provider 的 /self 严格校验，Cookie 本身不是完成信号。
 */
class CheckinWebLoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var popupWebView: WebView? = null
    private var root: FrameLayout? = null
    private var profile: NewApiSiteProfile? = null
    private var closed = false
    private var ready = false
    private var resettingStorage = false
    private var checking = false
    private var attempts = 0
    private var nextImportAt = 0L
    private var startedAt = 0L
    private val owner = Any()
    private var ownsSession = false
    private val commitGate = AccountImportGate()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "checkin-web-login").apply { isDaemon = true }
    }
    private var importFuture: Future<*>? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 只有这一条周期链；导入失败/JS 空结果不消耗掉下一轮。 */
    private val pollCookies = object : Runnable {
        override fun run() {
            if (closed || !ready) return
            if (SystemClock.elapsedRealtime() - startedAt >= LOGIN_TIMEOUT_MS) {
                notice("登录等待超时，请重新打开登录页")
                finish()
                return
            }
            if (!checking && SystemClock.elapsedRealtime() >= nextImportAt) tryComplete()
            if (!closed) schedulePoll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val target = NewApiSiteProfile.builtins.firstOrNull { it.providerId == providerId }
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        val siteHost = intent.getStringExtra(EXTRA_SITE_HOST).orEmpty()
        if (target == null || target.host != siteHost ||
            !CheckinLoginPolicy.isSitePage(loginUrl, target.domain)) {
            notice("登录参数无效")
            finish()
            return
        }
        if (!LOGIN_OWNER.compareAndSet(null, owner)) {
            notice("已有站点正在登录，请先关闭它")
            finish()
            return
        }
        ownsSession = true
        profile = target
        startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed({
            if (!closed) {
                notice("登录等待超时，请重新打开登录页")
                finish()
            }
        }, LOGIN_TIMEOUT_MS)

        val frame = FrameLayout(this)
        root = frame
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt(),
            )
        }
        val web = WebView(this)
        webView = web
        web.isEnabled = false
        frame.addView(web, fullScreenParams())
        frame.addView(progress)
        setContentView(frame)
        configure(web, progress)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val popup = popupWebView
                if (popup != null) {
                    if (popup.canGoBack()) popup.goBack() else finish()
                } else if (web.canGoBack() && web.copyBackForwardList().currentIndex > 1) {
                    web.goBack()
                } else finish()
            }
        })

        // WebStorage 的删除没有回调：随后用同源空文档显式清 localStorage/sessionStorage，
        // evaluateJavascript 确认成功后才发起真实登录页，避免异步删除留下旧 user.id。
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            if (closed) return@removeAllCookies
            CookieManager.getInstance().flush()
            resettingStorage = true
            web.loadDataWithBaseURL(target.domain + "/", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView, progress: ProgressBar) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            // AgentRouter GitHub OAuth 的实际入口用异步 window.open，不能漏掉 popup 边。
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        web.layoutDirection = View.LAYOUT_DIRECTION_LTR
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
            ): Boolean {
                val target = profile ?: return false
                if (closed || !ready || popupWebView != null ||
                    !CheckinLoginPolicy.isSitePage(view?.url, target.domain)) return false
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@CheckinWebLoginActivity)
                configure(popup, progress)
                popupWebView = popup
                root?.addView(popup, fullScreenParams())
                progress.bringToFront()
                transport.webView = popup
                message.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window === popupWebView) closePopup(window)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (closed) return
                val target = profile ?: return
                val current = view ?: return
                if (resettingStorage && current === webView) {
                    resettingStorage = false
                    current.evaluateJavascript(STORAGE_RESET_JS) { result ->
                        if (closed) return@evaluateJavascript
                        if (result != "\"cleared\"") {
                            notice("无法清理旧登录状态，请关闭后重试")
                            finish()
                            return@evaluateJavascript
                        }
                        ready = true
                        current.isEnabled = true
                        current.loadUrl(target.domain.trimEnd('/') + "/login")
                        schedulePoll()
                    }
                } else if (CheckinLoginPolicy.canReadIdentity(url, target.domain)) {
                    schedulePoll()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                !request.url.scheme.equals("https", ignoreCase = true)
        }
    }

    private fun schedulePoll() {
        handler.removeCallbacks(pollCookies)
        if (!closed && ready) handler.postDelayed(pollCookies, COOKIE_POLL_MS)
    }

    private fun tryComplete() {
        val target = profile ?: return
        val web = popupWebView ?: webView ?: return
        if (closed || !ready || checking || !CheckinLoginPolicy.canReadIdentity(web.url, target.domain)) return
        checking = true
        web.evaluateJavascript(PAGE_UID_JS) { value ->
            if (closed) return@evaluateJavascript
            val uid = CheckinLoginPolicy.pageUserId(value)
            val stillSite = CheckinLoginPolicy.canReadIdentity(web.url, target.domain)
            val cookies = CheckinCookie.parse(CookieManager.getInstance().getCookie(target.domain).orEmpty())
            if (!stillSite || uid == null || !cookies.containsKey("session")) {
                checking = false
                return@evaluateJavascript
            }
            val raw = JsonObject().apply {
                addProperty("cookies", CheckinCookie.format(cookies))
                addProperty("apiUserId", uid)
                // 把这次登录建立的 github.com 会话一起带走。
                // 没有它，下次「重新授权签到」会掉到 GitHub 登录页，
                // 用户又要输一次密码 + 再批准一次——摩擦比浏览器还大。
                // 每个账号各存各的：两个账号对应两个 GitHub 身份，共用会签错号。
                val gh = readGithubCookies()
                if (gh.isNotEmpty()) addProperty("githubCookies", gh)
            }.toString()
            attempts++
            importFuture = executor.submit {
                val result = runCatching {
                    (application as? AiGatewayApp)?.engine?.importAccountJson(target.providerId, raw, commitGate)
                }.getOrNull()
                handler.post {
                    if (closed) return@post
                    checking = false
                    importFuture = null
                    when {
                        result == null -> {
                            notice("网关尚未初始化")
                            finish()
                        }
                        result.ok -> {
                            notice("已添加：${result.nickname.ifEmpty { result.uid }}")
                            setResult(RESULT_OK)
                            finish()
                        }
                        attempts >= MAX_IMPORT_ATTEMPTS -> {
                            notice("登录校验失败，请重试：${result.error}")
                            finish()
                        }
                        else -> {
                            nextImportAt = SystemClock.elapsedRealtime() + IMPORT_RETRY_MS
                            Log.w(TAG, "登录校验未成功，将重试：${result.error}")
                            if (attempts == 1) notice("登录校验未成功，正在重试")
                            schedulePoll()
                        }
                    }
                }
            }
        }
    }

    /**
     * 读当前 WebView 里 github.com 的会话串（`k=v; k=v`）。
     *
     * 只能读 CookieManager：GitHub 的 `user_session` 是 HttpOnly，
     * `document.cookie` 看不到，但 native 的 `getCookie` 能读出来。
     */
    private fun readGithubCookies(): String =
        runCatching { CookieManager.getInstance().getCookie("https://github.com").orEmpty() }
            .getOrDefault("")
            .trim()

    private fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun closePopup(web: WebView?) {
        if (web == null || web !== popupWebView) return
        root?.removeView(web)
        destroyWeb(web)
        popupWebView = null
    }

    private fun destroyWeb(web: WebView?) {
        web?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = null
            destroy()
        }
    }

    /** 返回键/主动退出当场关闭提交门，不能等到异步 onDestroy 才生效。 */
    override fun finish() {
        stopLogin()
        super.finish()
    }

    private fun stopLogin() {
        if (closed) return
        closed = true
        commitGate.cancel()
        importFuture?.cancel(true)
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        stopLogin()
        destroyWeb(popupWebView)
        destroyWeb(webView)
        popupWebView = null
        webView = null
        root = null
        if (ownsSession) {
            // 退出清理只碰临时浏览器状态，已加密保存的账号凭据不删。
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                LOGIN_OWNER.compareAndSet(owner, null)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CheckinWebLogin"
        private const val COOKIE_POLL_MS = 1_000L
        private const val IMPORT_RETRY_MS = 5_000L
        private const val MAX_IMPORT_ATTEMPTS = 5
        private const val LOGIN_TIMEOUT_MS = 10 * 60_000L
        private val LOGIN_OWNER = AtomicReference<Any?>(null)
        private const val STORAGE_RESET_JS = "(function(){try{localStorage.clear();sessionStorage.clear();return 'cleared';}catch(e){return 'failed';}})()"
        private const val PAGE_UID_JS = "(function(){try{var u=JSON.parse(localStorage.getItem('user')||'null');var id=u&&u.id;if((typeof id==='number'&&Number.isSafeInteger(id)&&id>0)||(typeof id==='string'&&/^[1-9][0-9]*$/.test(id)))return String(id);return null;}catch(e){return null;}})()"

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
