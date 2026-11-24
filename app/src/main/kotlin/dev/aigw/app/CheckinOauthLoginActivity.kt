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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.gson.JsonObject
import dev.aigw.core.provider.newapi.CheckinCookie
import dev.aigw.core.provider.newapi.CheckinLoginPolicy
import dev.aigw.core.provider.newapi.CheckinOauthPolicy
import dev.aigw.core.provider.newapi.CheckinQuotaDelta
import dev.aigw.core.provider.newapi.NewApiCheckinProvider
import dev.aigw.core.provider.newapi.NewApiSiteProfile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * 「签到 = 重新登录一次」的站点（AgentRouter）的签到界面。
 *
 * ## 与 [CheckinWebLoginActivity] 的区别（这是关键，别混用）
 *
 * [CheckinWebLoginActivity] 是**首次添加账号**：它把 CookieManager 全部清空，
 * 包括 github.com，逼用户完整登录一次。
 *
 * 本类做的是**每日签到**：站点没有签到接口，额度只在「一次新的登录」里发放，
 * 所以要重走一遍 GitHub OAuth。但 GitHub 那边的登录状态**必须保留**——
 * 应用内 WebView 无法完成 GitHub 的密码/2FA 登录。社区实现
 * （AnnaKarelovsky/AgentRouterautoCheckin）对此写得很直白：
 * 「The only automatic cleanup is scoped to the AgentRouter origin, so the GitHub
 * session remains available for the next OAuth authorization.」
 *
 * 因此清理只做 **agentrouter.org 一个源**，github.com 的会话原样保留。
 *
 * ## 流程
 *
 * 1. 只清站点源的 cookie（github.com 不动）
 * 2. 打开站点 `/login`
 * 3. 在页面里 `GET /api/oauth/state?mode=login` 拿签名 state
 * 4. 导航到 GitHub 授权页；GitHub 已登录则直接 302 回站点回调
 *    （若 GitHub 会话失效会停在登录页，此时交给用户手动登录一次，之后每天自动）
 * 5. 站点回调页 `/oauth/github` 自己兑换 code 并发放当日额度
 * 6. **不信 `checked_in`**（当天已签过同样为 true），用站点日志核验是否真的落库
 * 7. 成功则把（可能轮换过的）session 写回账号池
 */
class CheckinOauthLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var root: FrameLayout? = null
    private var statusView: TextView? = null
    private var progress: ProgressBar? = null
    private var profile: NewApiSiteProfile? = null
    private var providerId: String = ""
    private var uid: String = ""

    private var closed = false
    private var step = Step.PREPARE
    private var startedAt = 0L
    /**
     * 开始签到前读到的余额（quota 原值）。
     *
     * 这是**唯一**能证明「这次真的加了额度」的基线：`checked_in` 对当天已签过的账号
     * 同样为 true，日志接口在 AgentRouter 上是否可用也未验证。读不到就记 null，
     * 判据会退化成「无法比较」，如实报而不是猜成功。
     */
    private var quotaBefore: Long? = null
    private var callbackTicks = 0
    /** 授权参数请求只发一次；页面可能多次 onPageFinished。 */
    private var authorizeInFlight = false

    private val owner = Any()
    private var ownsSession = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "checkin-oauth-relogin").apply { isDaemon = true }
    }
    private var verifyFuture: Future<*>? = null
    private val handler = Handler(Looper.getMainLooper())

    private enum class Step { PREPARE, OPEN_LOGIN, FETCH_STATE, AUTHORIZE, CALLBACK, VERIFY, DONE }

    /** 只有这一条周期链：每次推进都重新排一次，不会出现两条并行。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            if (SystemClock.elapsedRealtime() - startedAt >= TOTAL_TIMEOUT_MS) {
                fail("签到超时，请重试")
                return
            }
            when (step) {
                Step.PREPARE, Step.OPEN_LOGIN, Step.FETCH_STATE, Step.AUTHORIZE -> schedule()
                Step.CALLBACK -> {
                    // 回调页要等站点自己兑换 code，给几拍再核验，避免拿到旧会话。
                    if (callbackTicks++ >= CALLBACK_GRACE_TICKS) tryVerify() else schedule()
                }
                Step.VERIFY, Step.DONE -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        val target = NewApiSiteProfile.builtins.firstOrNull { it.providerId == providerId }
        uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        if (target == null) {
            notice("签到参数无效")
            finish()
            return
        }
        if (!LOGIN_OWNER.compareAndSet(null, owner)) {
            notice("已有站点登录正在进行，请稍后再试")
            finish()
            return
        }
        ownsSession = true
        profile = target
        startedAt = SystemClock.elapsedRealtime()

        val frame = FrameLayout(this)
        root = frame
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt(),
            )
        }
        progress = bar
        val label = TextView(this).apply {
            text = "准备中…"
            setPadding(32, 24, 32, 12)
        }
        statusView = label
        val web = WebView(this)
        webView = web
        frame.addView(web, fullScreenParams())
        frame.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() })
        frame.addView(bar)
        setContentView(frame)
        configure(web, bar)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { fail("已取消签到") }
        })

        // 只清站点源，绝不碰 github.com。
        //
        // 这里**不能**用 removeAllCookies：它会把 github.com 的会话一起清掉，
        // 而应用内 WebView 无法完成 GitHub 的密码 / 2FA 登录 —— 用户会卡在 GitHub 登录页，
        // 每天都要重新输一次密码。社区实现（AnnaKarelovsky/AgentRouterautoCheckin）
        // 明确只清站点源就是为了留住 GitHub 会话。
        //
        // 多账号串号的风险由「注入前先清站点源 + 按账号注入各自的 githubCookies」解决：
        // 两个账号各存一份 GitHub 会话（导入时由 CheckinWebLoginActivity 读取）。
        step = Step.PREPARE
        setStatus("准备授权…")
        val ghCookies = savedGithubCookies()
        // 先读完签到前余额再往下走。
        //
        // 不能「异步去读、主线程继续跑」：那样核验时 quotaBefore 可能还是 null，
        // 判据退化成「无法比较」，用户会看到一句含糊的「已重新登录」而不是明确结果。
        // 读余额走的是网络，必须离开主线程，所以用 executor + 回调串起来。
        executor.execute {
            val before = runCatching { savedQuota() }.getOrNull()
            handler.post {
                if (closed) return@post
                quotaBefore = before
                startAuthorization(target, ghCookies, web)
            }
        }
        schedule()
    }

    /** 读完基线余额后真正开始授权流程。 */
    private fun startAuthorization(
        target: NewApiSiteProfile,
        ghCookies: String,
        web: WebView,
    ) {
        clearSiteCookies(target) {
            if (closed) return@clearSiteCookies
            val manager = CookieManager.getInstance()
            if (ghCookies.isNotEmpty()) injectCookies(manager, ghCookies)
            manager.flush()
            step = Step.OPEN_LOGIN
            setStatus("打开站点登录页…")
            web.loadUrl(CheckinOauthPolicy.loginUrl(target))
        }
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView, bar: ProgressBar) {
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
            // 站点 GitHub 按钮走 window.open，必须放行弹窗；否则点了没反应。
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        web.layoutDirection = View.LAYOUT_DIRECTION_LTR
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
            ): Boolean {
                // 站点用 window.open 打开 GitHub 授权。我们不另开窗口，
                // 而是把这次跳转接管到主 WebView，减少一层状态。
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@CheckinOauthLoginActivity)
                configure(popup, bar)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (!request.url.scheme.equals("https", ignoreCase = true)) return true
                        if (url.contains("github.com") || CheckinOauthPolicy.isBackOnSite(url, profile!!)) {
                            webView?.loadUrl(url)
                            return true
                        }
                        return false
                    }
                }
                transport.webView = popup
                message.sendToTarget()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (closed) return
                val target = profile ?: return
                val current = view ?: return
                val page = url.orEmpty()
                when {
                    CheckinOauthPolicy.isCallbackUrl(page, target) -> {
                        // 回调页：站点 SPA 自己兑换 code 并发额度，我们等它跑完。
                        step = Step.CALLBACK
                        callbackTicks = 0
                        setStatus("站点正在完成签到…")
                        schedule()
                    }
                    CheckinLoginPolicy.isSitePage(page, target.domain) &&
                        !page.contains("github.com") -> {
                        // 首次进入或页面重载都重新取参数；authorizeInFlight 防重入。
                        if (step == Step.OPEN_LOGIN || step == Step.FETCH_STATE) {
                            step = Step.FETCH_STATE
                            fetchStateAndAuthorize(current, target)
                        }
                    }
                    page.contains("github.com/login/oauth/authorize") -> {
                        // 授权确认页（含「Reauthorization required」）。
                        // 不点它 GitHub 永远不会 302 回站点 —— 社区脚本栽在这里。
                        step = Step.AUTHORIZE
                        setStatus("正在确认 GitHub 授权…")
                        clickAuthorize(current)
                        schedule()
                    }
                    page.contains("github.com/login") -> {
                        // GitHub 会话已失效：交给用户手动登录一次，之后每天可全自动。
                        // 登录页的输入框由系统键盘处理，不做任何注入。
                        step = Step.AUTHORIZE
                        setStatus("请在下方登录 GitHub（只需一次，之后自动）")
                        schedule()
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                !request.url.scheme.equals("https", ignoreCase = true)
        }
    }

    /**
     * 走站点自己的授权入口：先拿 state，再拼 GitHub 授权 URL。
     *
     * **刻意不在 WebView 里用同步 XHR 取参数**：`evaluateJavascript` 会把 JS 返回值
     * 再做一次 JSON 编码（JS 返回字符串 `{"cid":..}`，Kotlin 侧收到的是带转义的
     * `"{\"cid\":..}"`），直接当 JsonObject 解析必然失败。改成原生 HTTP 后
     * 不依赖页面上下文，也不必等 onPageFinished，且能在 `:core` 里纯 JVM 测。
     *
     * `/api/status` 与 `/api/oauth/state` 都是公开接口（实测 200），不需要 cookie；
     * state 是服务端签名的一次性 token，不绑定会话。
     */
    private fun fetchStateAndAuthorize(web: WebView, target: NewApiSiteProfile) {
        if (authorizeInFlight) return
        authorizeInFlight = true
        setStatus("获取授权参数…")
        // 网络在主线程会抛 NetworkOnMainThreadException，必须挪到后台。
        executor.execute {
            val params = runCatching { CheckinOauthPolicy.fetchAuthorizeParams(target) }.getOrNull()
            handler.post {
                if (closed) return@post
                if (params == null) {
                    fail("拿不到站点授权参数（/api/status 或 /api/oauth/state 请求失败）")
                    return@post
                }
                step = Step.AUTHORIZE
                setStatus("正在通过 GitHub 授权…")
                web.loadUrl(params)
                schedule()
            }
        }
    }

    /**
     * 在 GitHub 授权页点一次「Authorize / Reauthorize」。
     *
     * 只在 `github.com/login/oauth/authorize` 上调用：这是 GitHub 自己的授权确认页，
     * 首次授权会显示「Authorize <app>」，之前授权过但需要再次确认时显示
     * 「Reauthorization required」+ Reauthorize 按钮。两者都表现为一个 form submit。
     *
     * 找不到按钮就什么都不做，让用户自己点——**绝不**在这里做任何强制跳转。
     */
    private fun clickAuthorize(web: WebView) {
        val js = """
            (function(){
              try {
                // 1) 标准的授权提交按钮（name=authorize）
                var b = document.querySelector('button[name="authorize"]')
                     || document.querySelector('input[name="authorize"]')
                     || document.querySelector('button[type="submit"]')
                     || document.querySelector('form[action*="authorize"] button');
                if (b) { b.click(); return 'clicked'; }
                // 2) 兜底：按文案找（多语言界面）
                var all = document.querySelectorAll('button, input[type="submit"], a.btn');
                for (var i = 0; i < all.length; i++) {
                  var t = (all[i].textContent || all[i].value || '').toLowerCase();
                  if (t.indexOf('authorize') >= 0 || t.indexOf('授权') >= 0) {
                    all[i].click();
                    return 'clicked-text';
                  }
                }
                return 'no-button';
              } catch (e) { return 'error'; }
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { result ->
            // 只记日志：结果不影响主流程，真正的完成信号是回到站点回调页。
            Log.d(TAG, "authorize click: $result")
        }
    }

    /**
     * 把当前 WebView 里 github.com 的会话写回账号凭证。
     *
     * 只增改 `githubCookies` 这一个字段，站点 cookies / apiUserId 原样保留
     * （不能整体覆盖：那会把 provider 维护的其他字段抹掉）。
     */
    private fun persistGithubCookies() {
        val engine = (application as? AiGatewayApp)?.engine ?: return
        val account = engine.account(providerId, uid) ?: return
        val fresh = runCatching {
            CookieManager.getInstance().getCookie("https://github.com").orEmpty().trim()
        }.getOrDefault("")
        if (fresh.isEmpty()) return
        val secret = runCatching {
            val obj = com.google.gson.JsonParser.parseString(account.secret).asJsonObject
            obj.addProperty("githubCookies", fresh)
            obj.toString()
        }.getOrNull() ?: return
        // 写盘 + 加密，不能压在主线程；最多等 2 秒，超时就放弃（下次签到再写）。
        val writer = Thread {
            runCatching { engine.updateAccountSecret(providerId, uid, secret) }
                .onFailure { Log.d(TAG, "github cookie 回写失败: ${it.message}") }
        }
        writer.start()
        runCatching { writer.join(2000) }
    }

    /** 读当前账号余额（quota 原值）；读不到返回 null。 */
    private fun savedQuota(): Long? {
        val engine = (application as? AiGatewayApp)?.engine ?: return null
        val provider = engine.registry.get(providerId)
            as? NewApiCheckinProvider ?: return null
        val account = engine.account(providerId, uid) ?: return null
        return provider.creditInfo(account)?.takeIf { it.known }?.balance
    }

    /** 本账号在导入时保存的 github.com 会话（可能为空 = 需要用户手动登录一次）。 */
    private fun savedGithubCookies(): String {
        val engine = (application as? AiGatewayApp)?.engine ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        val provider = engine.registry.get(providerId)
            as? NewApiCheckinProvider ?: return ""
        return provider.githubCookiesOf(account)
    }

    /**
     * 把保存的 `k=v; k=v` 注入到 github.com。
     *
     * GitHub 的会话 cookie（`user_session`）是 HttpOnly + Secure，`document.cookie`
     * 读不到，但 native 的 CookieManager 两端都能读写，所以这条链是通的。
     */
    private fun injectCookies(manager: CookieManager, raw: String) {
        for (part in raw.split(";")) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            val name = item.substring(0, eq).trim()
            val value = item.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            // 只写 host-only（不带 Domain）一条：同名 cookie 写两个域会变成两份，
            // 上游可能因此认不出会话。开机时已经 removeAllCookies 过，不存在旧条目。
            runCatching { manager.setCookie("https://github.com", "$name=$value; Path=/") }
        }
    }

    /**
     * 只清理指定源（站点）的 cookie。
     *
     * Android 没有「按域删除」的 API，只能对每枚已知 cookie 写一条立即过期的同名项。
     * 这里刻意**不去碰 github.com**——那正是每天免登录的关键。
     */
    private fun clearSiteCookies(target: NewApiSiteProfile, done: () -> Unit) {
        val manager = CookieManager.getInstance()
        val origin = target.domain.trimEnd('/')
        val raw = runCatching { manager.getCookie(origin) }.getOrNull().orEmpty()
        val cookies = CheckinCookie.parse(raw)
        if (cookies.isEmpty()) {
            done()
            return
        }
        for (name in cookies.keys) {
            for (path in listOf("/", "/api", "/oauth")) {
                manager.setCookie(origin + path, "$name=; Max-Age=0; Path=$path")
            }
        }
        // setCookie 是异步落盘，给一拍再继续；用 post 而不是 sleep，避免卡住主线程。
        handler.postDelayed({ done() }, 300)
    }

    private fun schedule() {
        handler.removeCallbacks(ticker)
        if (!closed) handler.postDelayed(ticker, POLL_MS)
    }

    /** 回调页停留一段时间后开始核验；站点兑换 code 需要一点时间。 */
    private fun tryVerify() {
        if (step != Step.CALLBACK || verifyFuture != null || closed) return
        val target = profile ?: return
        val web = webView ?: return
        val manager = CookieManager.getInstance()
        val cookies = CheckinCookie.parse(runCatching { manager.getCookie(target.domain.trimEnd('/')) }.getOrNull().orEmpty())
        if (!cookies.containsKey(SESSION)) {
            // 还没拿到会话：再等一拍，别急着判失败。
            if (SystemClock.elapsedRealtime() - startedAt < TOTAL_TIMEOUT_MS) schedule() else fail("站点没有下发会话")
            return
        }
        step = Step.VERIFY
        setStatus("核验签到结果…")
        val engine = (application as? AiGatewayApp)?.engine
        verifyFuture = executor.submit {
            val verdict = runCatching {
                val provider = engine?.registry?.get(providerId)
                    as? NewApiCheckinProvider
                val account = engine?.account(providerId, uid)
                if (provider == null || account == null) {
                    null
                } else {
                    // 先把新会话写回账号池，否则核验用的还是旧 cookie。
                    val fresh = account.copy(secret = secretWith(cookies, account.secret))
                    engine.updateAccountSecret(providerId, uid, fresh.secret)
                    // 判据与纯 HTTP 路径一致：签到前后余额差 + 站点 checked_in。
                    provider.verifyByQuota(fresh, quotaBefore)
                }
            }.getOrElse { null }
            handler.post {
                if (closed) return@post
                verifyFuture = null
                finishWith(verdict)
            }
        }
    }

    /** 把最新 cookie 合进 secret 的 JSON，保留原有其它字段。 */
    private fun secretWith(cookies: Map<String, String>, existing: String): String {
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(existing).asJsonObject
        }.getOrNull() ?: JsonObject()
        obj.addProperty("cookies", CheckinCookie.format(cookies))
        return obj.toString()
    }

    private fun finishWith(verdict: CheckinQuotaDelta.Verdict?) {
        step = Step.DONE
        // 额度刚变，立刻重拉一次，用户关掉这个页面就能看到新数字。
        if (verdict?.kind == CheckinQuotaDelta.Kind.INCREASED) {
            runCatching {
                (application as? AiGatewayApp)?.engine?.refreshCredits(providerId, uid)
            }
        }
        // GitHub 在授权过程中可能轮换会话 cookie；把当前实际值写回，
        // 下次签到才能继续免登录。失败也写——会话本身仍是有效的。
        persistGithubCookies()

        when (verdict?.kind) {
            CheckinQuotaDelta.Kind.INCREASED -> {
                notice("签到成功，额度已到账")
                setResult(RESULT_OK)
            }
            // 站点说今天已签过、余额没动：今天的目标已达成，不是失败。
            CheckinQuotaDelta.Kind.ALREADY_DONE -> {
                notice("今天已经签过了，本次没有新增额度")
                setResult(RESULT_OK)
            }
            // 登录成功但额度没发、站点也没说签过：如实报失败。
            CheckinQuotaDelta.Kind.NOT_CREDITED -> {
                notice("登录完成，但额度没有变化，签到可能没有生效")
                setResult(RESULT_CANCELED)
            }
            CheckinQuotaDelta.Kind.AFTER_ONLY,
            CheckinQuotaDelta.Kind.UNKNOWN,
            -> {
                // 没有签到前基线，无法证明加没加钱：如实说「已重新登录」。
                notice("已重新登录站点，请在列表里刷新余额确认")
                setResult(RESULT_OK)
            }
            null -> {
                notice("核验失败：账号不存在，请重新添加")
                setResult(RESULT_CANCELED)
            }
        }
        finish()
    }

    private fun setStatus(text: String) {
        statusView?.text = text
        Log.i(TAG, text)
    }

    private fun fail(message: String) {
        if (closed) return
        step = Step.DONE
        notice(message)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun finish() {
        stopLogin()
        super.finish()
    }

    private fun stopLogin() {
        if (closed) return
        closed = true
        verifyFuture?.cancel(true)
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        stopLogin()
        webView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            webChromeClient = null
            destroy()
        }
        webView = null
        root = null
        if (ownsSession) {
            // 注意：这里**不清** github.com 的 cookie，下次签到还要靠它免登录。
            LOGIN_OWNER.compareAndSet(owner, null)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CheckinOauthLogin"
        private const val POLL_MS = 800L
        /** 回调页给站点 4 拍（约 3 秒）兑换 code 与发额度，再开始核验。 */
        private const val CALLBACK_GRACE_TICKS = 4
        private const val TOTAL_TIMEOUT_MS = 5 * 60_000L
        private const val SESSION = "session"
        private val LOGIN_OWNER = AtomicReference<Any?>(null)

        const val EXTRA_PROVIDER_ID = "providerId"
        const val EXTRA_UID = "uid"

        fun intent(context: Context, providerId: String, uid: String): Intent =
            Intent(context, CheckinOauthLoginActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_ID, providerId)
                putExtra(EXTRA_UID, uid)
            }
    }
}
