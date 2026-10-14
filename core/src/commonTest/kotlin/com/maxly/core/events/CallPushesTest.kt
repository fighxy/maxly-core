package com.maxly.core.events

import com.maxly.core.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `NOTIF_CALL_ACCEPTED` 120 and `NOTIF_CALL_END` 121 carry the call's `conversationId`. */
class CallPushesTest {
    @Test
    fun opcodesKeepTheirCodes() {
        assertEquals(120, Opcode.NOTIF_CALL_ACCEPTED.value)
        assertEquals(121, Opcode.NOTIF_CALL_END.value)
    }

    @Test
    fun acceptedElsewhereIsTyped() {
        val e = assertIs<MaxEvent.CallAcceptedElsewhere>(EventParser.parse(120, 0, mapOf("conversationId" to "conv-1")))
        assertEquals("conv-1", e.conversationId)
        assertEquals(120, e.opcode)
    }

    @Test
    fun endedIsTyped() {
        val e = assertIs<MaxEvent.CallEnded>(EventParser.parse(121, 0, mapOf("conversationId" to "conv-2", "extra" to 1)))
        assertEquals("conv-2", e.conversationId)
    }

    @Test
    fun withoutConversationIdTheyStayUnknown() {
        assertIs<MaxEvent.Unknown>(EventParser.parse(120, 0, mapOf("conversationId" to "")))
        assertIs<MaxEvent.Unknown>(EventParser.parse(121, 0, mapOf("other" to "x")))
        // a reply with the same opcode is not a push
        assertIs<MaxEvent.Unknown>(EventParser.parse(121, 1, mapOf("conversationId" to "c")))
    }
}
