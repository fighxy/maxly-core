package com.maxly.core.transport

import com.maxly.core.api.AccountConfig
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration

/** `failover-hosts`: when the primary host cannot be reached, the hosts are tried in order. */
class FailoverHostsTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    /** Fails [open] for every host in [down]; records every attempt. */
    private class FlakyFactory(private val down: Set<String>) : ConnectionFactory {
        val attempts = mutableListOf<Pair<String, Int>>()
        override suspend fun open(host: String, port: Int, tls: TlsOptions, proxy: ProxyConfig?): RawConnection {
            attempts += host to port
            if (host in down) throw ConnectionClosedException("unreachable $host")
            return FakeRawConnection()
        }
    }

    private fun TestScope.transport(factory: ConnectionFactory) = MaxTransport(quiet, factory, scope = backgroundScope)

    @Test
    fun primaryIsTriedFirstAndAloneWhenItWorks() = runTest {
        val f = FlakyFactory(emptySet())
        val t = transport(f)
        t.setFailoverHosts(listOf("f1.test", "f2.test"))
        t.connect()
        assertEquals(listOf("api.test" to 443), f.attempts)
    }

    @Test
    fun failoverHostsAreTriedInOrderWhenThePrimaryFails() = runTest {
        val f = FlakyFactory(setOf("api.test", "f1.test"))
        val t = transport(f)
        t.setFailoverHosts(listOf("f1.test", "f2.test:8443", "f3.test"))
        t.connect()
        assertEquals(listOf("api.test" to 443, "f1.test" to 443, "f2.test" to 8443), f.attempts)
        assertEquals(ConnectionState.Connected, t.state.value)
    }

    @Test
    fun whenEveryHostFailsThePrimaryErrorIsThrown() = runTest {
        val f = FlakyFactory(setOf("api.test", "f1.test"))
        val t = transport(f)
        t.setFailoverHosts(listOf("f1.test"))
        val e = assertFailsWith<ConnectionClosedException> { t.connect() }
        assertEquals("unreachable api.test", e.message)
        assertEquals(ConnectionState.Disconnected, t.state.value)
    }

    @Test
    fun withoutFailoverHostsOnlyThePrimaryIsTried() = runTest {
        val f = FlakyFactory(setOf("api.test"))
        assertFailsWith<ConnectionClosedException> { transport(f).connect() }
        assertEquals(listOf("api.test" to 443), f.attempts)
    }

    @Test
    fun entriesAreParsed() {
        assertEquals(FailoverHost("h.test", 443), FailoverHost.parse(" h.test ", 443))
        assertEquals(FailoverHost("h.test", 444), FailoverHost.parse("h.test:444", 443))
        assertEquals(FailoverHost("h.test:x", 443), FailoverHost.parse("h.test:x", 443))
        assertNull(FailoverHost.parse("  ", 443))
    }

    @Test
    fun serverConfigCarriesFailoverHosts() {
        val c = AccountConfig(server = mapOf("failover-hosts" to listOf("a.test", "", "b.test:443")))
        assertEquals(listOf("a.test", "b.test:443"), c.failoverHosts)
        assertEquals(emptyList(), AccountConfig().failoverHosts)
    }
}
