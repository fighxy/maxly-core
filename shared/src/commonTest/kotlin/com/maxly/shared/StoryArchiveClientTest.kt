@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.maxly.shared

import com.maxly.core.media.HttpResponse
import com.maxly.core.media.MediaHttp
import com.maxly.core.protocol.Opcode
import com.maxly.core.protocol.decodePayloadPacket
import com.maxly.core.transport.FakeRawConnection
import com.maxly.core.transport.ScriptedConnectionFactory
import com.maxly.core.transport.ServerErrorException
import com.maxly.core.transport.TransportConfig
import com.maxly.core.transport.errorReply
import com.maxly.core.transport.ok
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * «Мои истории»: archive 219 only with `stories-history`; otherwise (and after the server refused
 * 219 as unknown) the current stories from 210 — 219 there drops the connection.
 */
class StoryArchiveClientTest {
    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)
    private val noHttp = MediaHttp { _, _, _, _, _ -> HttpResponse(500, ByteArray(0)) }

    private fun loginReply(archive: Boolean) = mapOf(
        "profile" to mapOf("contact" to mapOf("id" to 9, "names" to listOf(mapOf("firstName" to "Me")))),
        "chats" to emptyList<Any>(),
        "time" to 1700L,
        "config" to mapOf("hash" to "cfg", "server" to if (archive) mapOf("stories-history" to true) else emptyMap<String, Any?>()),
    )

    private fun story(id: Long, time: Long) = mapOf(
        "id" to id, "owner" to mapOf("ownerId" to 9L, "type" to 0), "time" to time,
        "media" to mapOf("_type" to "PHOTO", "baseUrl" to "https://i/$id"),
    )

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Map<*, *>? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue, "expected ${opcode.name}")
        feed(ok(header.seq, opcode.value, reply))
        return payload as? Map<*, *>
    }

    private suspend fun TestScope.loggedIn(factory: ScriptedConnectionFactory, archive: Boolean): MaxClient {
        val c = MaxClient(MaxClientConfig(host = "api.test", transport = quiet), InMemoryKeyValueStore(), factory, noHttp, backgroundScope)
        val login = async { c.loginWithToken("login-1") }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1L))
        runCurrent()
        conn.answer(Opcode.LOGIN, loginReply(archive))
        login.await()
        runCurrent()
        return c
    }

    @Test
    fun withoutTheArchiveFlagOwnStoriesComeFrom210() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory, archive = false)
        val conn = factory.lastConnection!!
        val page = async { c.ownStoryArchive() }
        runCurrent()
        val sent = conn.answer(Opcode.STORIES_GET_BY_OWNER, mapOf("peerStories" to listOf(mapOf("owner" to mapOf("ownerId" to 9L, "type" to 0), "stories" to listOf(story(1, 10), story(2, 20))))))
        assertEquals("[{ownerId=9, type=0}]", sent?.get("owners").toString())
        assertEquals(listOf(2L, 1L), page.await().stories.map { it.id })
        assertNull(page.await().marker)
        // There is no next page: nothing is sent.
        assertEquals(emptyList(), c.ownStoryArchive(5L).stories)
        assertNull(conn.takeWritten())
    }

    @Test
    fun withTheFlagTheArchiveIsAskedAndAnUnknownOpcodeStopsIt() = runTest {
        val factory = ScriptedConnectionFactory()
        val c = loggedIn(factory, archive = true)
        val conn = factory.lastConnection!!
        val refused = async { runCatching { c.ownStoryArchive() } }
        runCurrent()
        val (header, payload) = decodePayloadPacket(conn.takeWritten()!!)
        assertEquals(Opcode.STORIES_HISTORY_GET_BY_OWNER.value, header.opcodeValue)
        assertEquals("{ownerId=9, type=0}", (payload as Map<*, *>)["owner"].toString())
        conn.feed(errorReply(header.seq, header.opcodeValue, mapOf("error" to "proto.opcode", "message" to "Неизвестный opcode")))
        runCurrent()
        assertFailsWith<ServerErrorException> { refused.await().getOrThrow() }
        // Afterwards the current stories (210), not 219 again.
        val page = async { c.ownStoryArchive() }
        runCurrent()
        conn.answer(Opcode.STORIES_GET_BY_OWNER, mapOf("peerStories" to emptyList<Any>()))
        assertEquals(emptyList(), page.await().stories)
    }
}
