package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountImportGateTest {
    @Test fun `取消先到则不调用写入且不可重开`() {
        val gate = AccountImportGate()
        var writes = 0
        gate.cancel()
        assertFalse(gate.commit { writes++ })
        assertFalse(gate.isOpen())
        assertEquals(0, writes)
    }

    @Test fun `成功提交只有一次取消不回滚`() {
        val gate = AccountImportGate()
        var writes = 0
        assertTrue(gate.commit { writes++ })
        gate.cancel()
        assertFalse(gate.commit { writes++ })
        assertEquals(1, writes)
    }

    @Test fun `写入抛错仍可在退出前重试`() {
        val gate = AccountImportGate()
        runCatching { gate.commit { error("disk error") } }
        assertTrue(gate.isOpen())
        assertTrue(gate.commit { })
    }

    @Test fun `网络验证未完成时退出真实引擎不落账号池`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = object : Provider {
            override val id = "checkin:cancel-fixture"
            override val displayName = id
            override val authKind = AuthKind.NONE
            override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
            override fun resolveModel(requested: String) = requested
            override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(501, "")
            override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
            override fun importCredentials(raw: String): ProviderAccount {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                return ProviderAccount(id, "42", "Fixture", "{}")
            }
        }
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(provider)
        val gate = AccountImportGate()
        val result = AtomicReference<LoginOutcome>()
        val thread = Thread { result.set(engine.importAccountJson(provider.id, "fixture", gate)) }
        thread.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            gate.cancel()
            release.countDown()
            thread.join(3_000)
            assertFalse(thread.isAlive)
            assertFalse(result.get().ok)
            assertTrue(engine.accounts(provider.id).isEmpty())
        } finally {
            release.countDown()
            thread.interrupt()
            thread.join(3_000)
        }
    }
}
