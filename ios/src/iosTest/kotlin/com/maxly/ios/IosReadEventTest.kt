package com.maxly.ios

import com.maxly.core.api.Chat
import com.maxly.core.events.MaxEvent
import com.maxly.core.protocol.Opcode
import com.maxly.core.state.MaxState
import com.maxly.core.state.StateReducer
import kotlin.test.Test
import kotlin.test.assertEquals

/** `read` events carry the chat's unread counter after the core applied the account's own mark. */
class IosReadEventTest {
    private val me = 1L
    private val chatId = 9L

    private fun message(id: Long, time: Long, sender: Long = 2L) =
        mapOf("id" to id, "chatId" to chatId, "sender" to sender, "time" to time, "type" to "USER", "text" to "m$id")

    private val state: MaxState = run {
        val chat = Chat.from(
            mapOf(
                "id" to chatId, "type" to "DIALOG", "status" to "ACTIVE", "owner" to me,
                "lastEventTime" to 300L, "newMessages" to 3, "lastMessage" to message(3, 300),
            ),
        )!!
        StateReducer.putChats(MaxState(me = me), listOf(chat))
    }

    private fun read(userId: Long, mark: Long, setAsUnread: Boolean = false): IosEvent {
        val event = MaxEvent.MessageRead(chatId, userId, mark, setAsUnread, Opcode.NOTIF_MARK.value, null)
        return flatten(event, StateReducer.reduce(state, event, 0L, 100)).single()
    }

    @Test
    fun ownMarkUpToTheLastMessageClearsTheCounter() {
        val event = read(me, 300)
        assertEquals("read", event.kind)
        assertEquals(0, event.chatUnread)
    }

    @Test
    fun ownMarkBelowTheLastMessageKeepsTheServerCounterWithoutACache() {
        // nothing cached after the mark: the core cannot recount and keeps the counter
        assertEquals(3, read(me, 100).chatUnread)
    }

    @Test
    fun peerMarkAndMarkAsUnreadCarryNoCounter() {
        assertEquals(-1, read(2L, 300).chatUnread)
        assertEquals(-1, read(me, 100, setAsUnread = true).chatUnread)
    }
}
