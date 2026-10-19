package com.maxly.ios

import com.maxly.core.api.MaxMessage
import com.maxly.core.state.MaxState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosControlPinTest {
    private fun msg(id: Long, text: String, attaches: List<Map<String, Any?>> = emptyList()) = MaxMessage.from(
        mapOf("id" to id, "chatId" to 9L, "sender" to 101L, "text" to text, "time" to id, "type" to "USER", "attaches" to attaches),
    )!!

    @Test
    fun pinCarriesIdTextAndFallback() {
        val pin = msg(2, "", listOf(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to mapOf("id" to 77L, "text" to "Сбор в 10"))))
        val c = controlOf(pin, MaxState(me = 1))!!
        assertEquals("77", c.pinnedMessageId)
        assertEquals("Сбор в 10", c.pinnedText)
        assertEquals("Закреплено: Сбор в 10", c.fallbackText)
    }

    @Test
    fun idOnlyPinUsesStoreText() {
        val pin = msg(2, "", listOf(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to mapOf("id" to 1L))))
        assertEquals("Сообщение закреплено", controlOf(pin, MaxState(me = 1))!!.fallbackText)
        val state = MaxState(me = 1, messages = mapOf(9L to listOf(msg(1, "Встреча\nв 10"))))
        val c = controlOf(pin, state)!!
        assertEquals("1", c.pinnedMessageId)
        assertNull(c.pinnedText)
        assertEquals("Закреплено: Встреча", c.fallbackText)
    }

    @Test
    fun unpin() {
        val c = controlOf(msg(3, "", listOf(mapOf("_type" to "CONTROL", "event" to "unpin"))), MaxState())!!
        assertNull(c.pinnedMessageId)
        assertEquals("Закрепление снято", c.fallbackText)
    }
}
