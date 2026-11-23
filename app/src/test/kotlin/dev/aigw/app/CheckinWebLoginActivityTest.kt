package dev.aigw.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebView
import com.google.gson.JsonParser
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.store.KeyValueStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 真 Activity + Android API shadows；JS 回调由测试控制，不冒充真实 Chromium/OAuth。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CheckinWebLoginActivityTest {
    private val domain = "https://agentrouter.org"
    private val controllers = mutableListOf<ActivityController<CheckinWebLoginActivity>>()
    private lateinit var engine: GatewayEngine
    private lateinit var provider: ImportProvider

    private class Store : KeyValueStore {
        private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun keys(prefix: String): List<String> = values.keys.filter { it.startsWith(prefix) }
    }

    private class ImportProvider : Provider {
        override val id = "checkin:agentrouter"
        override val displayName = "fixture"
        override val authKind = AuthKind.NONE
        val calls = AtomicInteger()
        var failFirst = false
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(501, "")
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun importCredentials(raw: String): ProviderAccount {
            val call = calls.incrementAndGet()
            if (failFirst && call == 1) error("temporary fixture failure")
            val obj = JsonParser.parseString(raw).asJsonObject
            val uid = obj.get("apiUserId").asString
            return ProviderAccount(id, uid, "fixture-$uid", raw)
        }
    }

    @Before fun setUp() {
        engine = GatewayEngine(Store())
        provider = ImportProvider()
        engine.registry.register(provider)
        CookieManager.getInstance().removeAllCookies(null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() {
        controllers.asReversed().forEach { controller ->
            controller.get().finish()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        controllers.clear()
    }

    private fun start(): ActivityController<CheckinWebLoginActivity> {
        val app = RuntimeEnvironment.getApplication()
        val intent = CheckinWebLoginActivity.intent(app, provider.id, "$domain/login", "agentrouter.org")
        val controller = Robolectric.buildActivity(CheckinWebLoginActivity::class.java, intent)
            .create().start().resume().visible()
        controllers.add(controller)
        // 不运行 AndroidKeyStore 初始化；只替换 Application 引擎字段，实际 GatewayEngine/池照常跑。
        val gatewayApp = AiGatewayApp()
        ReflectionHelpers.setField(gatewayApp, "engine", engine)
        ReflectionHelpers.setField(controller.get(), "mApplication", gatewayApp)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun web(activity: CheckinWebLoginActivity): WebView =
        ReflectionHelpers.getField(activity, "webView")

    private fun ready(activity: CheckinWebLoginActivity): WebView {
        val view = web(activity)
        val shadow = shadowOf(view)
        assertNotNull("真实登录页前必须先创建同源清理页", shadow.lastLoadDataWithBaseURL)
        assertNull("清理完成前不加载真正登录页", shadow.lastLoadedUrl)
        shadow.webViewClient.onPageFinished(view, "$domain/")
        assertTrue(shadow.lastEvaluatedJavascript.contains("localStorage.clear()"))
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue("\"cleared\"")
        assertEquals("$domain/login", shadow.lastLoadedUrl)
        return view
    }

    private fun evaluateUid(view: WebView, uid: String?) {
        view.loadUrl("$domain/console/token")
        shadowOf(view).webViewClient.onPageFinished(view, "$domain/console/token")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val shadow = shadowOf(view)
        assertTrue(shadow.lastEvaluatedJavascript.contains("localStorage.getItem('user')"))
        val firstCallback = shadowOf(view).lastEvaluatedJavascriptCallback
        firstCallback.onReceiveValue(uid?.let { "\"$it\"" } ?: "null")
    }

    private fun settleImport(activity: CheckinWebLoginActivity) {
        val task: Future<*> = ReflectionHelpers.getField(activity, "importFuture")
        task.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `旧Cookie清空并等存储确认之后才打开登录页`() {
        CookieManager.getInstance().setCookie(domain, "session=old-fixture")
        val activity = start().get()
        assertFalse(CookieManager.getInstance().getCookie(domain).orEmpty().contains("old-fixture"))
        ready(activity)
        assertEquals(0, provider.calls.get())
    }

    @Test fun `只有session没有本次网页身份不导入账号`() {
        val activity = start().get()
        val view = ready(activity)
        CookieManager.getInstance().setCookie(domain, "session=state-only-fixture")
        evaluateUid(view, null)
        assertEquals(0, provider.calls.get())
        assertTrue(engine.accounts(provider.id).isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test fun `校验一次失败后同一轮询链仍能重试成功`() {
        provider.failFirst = true
        val activity = start().get()
        val view = ready(activity)
        CookieManager.getInstance().setCookie(domain, "session=new-fixture")
        evaluateUid(view, "42")
        val firstCallback = shadowOf(view).lastEvaluatedJavascriptCallback
        settleImport(activity)
        assertEquals(1, provider.calls.get())
        assertFalse(activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val retried = shadowOf(view).lastEvaluatedJavascriptCallback
        assertNotSame("必须由真实下一轮JS查询重试，不能反复调用旧回调", firstCallback, retried)
        retried.onReceiveValue("\"42\"")
        settleImport(activity)
        assertEquals(2, provider.calls.get())
        assertEquals("42", engine.accounts(provider.id).single().uid)
        assertTrue(activity.isFinishing)
    }

    @Test fun `退出后滞留JS回调不能启动导入`() {
        val activity = start().get()
        val view = ready(activity)
        CookieManager.getInstance().setCookie(domain, "session=new-fixture")
        view.loadUrl("$domain/console/token")
        shadowOf(view).webViewClient.onPageFinished(view, "$domain/console/token")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val late = shadowOf(view).lastEvaluatedJavascriptCallback
        activity.finish()
        late.onReceiveValue("\"42\"")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0, provider.calls.get())
        assertTrue(engine.accounts(provider.id).isEmpty())
    }

    @Test fun `两次独立登录建立不同UID且不改既有API凭据`() {
        engine.pool.upsert(ProviderAccount("custom:untouched", "key", "api", "fixture-key"))
        val first = start()
        val firstWeb = ready(first.get())
        CookieManager.getInstance().setCookie(domain, "session=account-a-fixture")
        evaluateUid(firstWeb, "42")
        settleImport(first.get())
        first.pause().stop().destroy()
        controllers.remove(first)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CookieManager.getInstance().getCookie(domain).orEmpty().contains("account-a-fixture"))
        val second = start()
        val secondWeb = ready(second.get())
        CookieManager.getInstance().setCookie(domain, "session=account-b-fixture")
        evaluateUid(secondWeb, "99")
        settleImport(second.get())
        assertEquals(setOf("42", "99"), engine.accounts(provider.id).map { it.uid }.toSet())
        assertEquals("fixture-key", engine.pool.account("custom:untouched", "key")!!.secret)
    }

    @Test fun `并行第二个登录被拒且不清掉第一个会话`() {
        val first = start().get()
        ready(first)
        CookieManager.getInstance().setCookie(domain, "session=first-fixture")
        val second = start().get()
        assertTrue(second.isFinishing)
        assertTrue(CookieManager.getInstance().getCookie(domain).orEmpty().contains("first-fixture"))
        assertFalse(first.isFinishing)
    }

    @Test fun `GitHub弹窗实际接入transport并可关闭`() {
        val activity = start().get()
        val view = ready(activity)
        val transport = ReflectionHelpers.callConstructor(
            WebView.WebViewTransport::class.java, ClassParameter.from(WebView::class.java, view),
        )
        val message = Message.obtain(Handler(Looper.getMainLooper()))
        message.obj = transport
        assertTrue(shadowOf(view).webChromeClient.onCreateWindow(view, false, false, message))
        val popup = requireNotNull(transport.webView)
        assertTrue(popup.settings.supportMultipleWindows())
        shadowOf(view).webChromeClient.onCloseWindow(popup)
        assertNull(ReflectionHelpers.getField<WebView?>(activity, "popupWebView"))
    }
}
