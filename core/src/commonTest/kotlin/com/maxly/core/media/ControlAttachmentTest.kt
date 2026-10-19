package com.maxly.core.media

import com.maxly.core.api.MaxMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlAttachmentTest {
    @Test
    fun parsesKnownEvent() {
        val raw = mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to "Team", "userIds" to listOf(1, 2L, "x"), "extra" to 5)
        val c = assertIs<Attachment.Control>(Attachment.from(raw))
        assertEquals("new", c.event)
        assertEquals(listOf(1L, 2L), c.userIds)
        assertEquals("Team", c.title)
        assertEquals("CHAT", c.chatType)
        assertNull(c.userId)
        assertEquals(5, c.raw["extra"])
        assertEquals("Создан чат «Team»", controlFallbackText(c))
    }

    @Test
    fun parsesUnknownEvent() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "somethingElse", "userId" to 7)))
        assertEquals(7L, c.userId)
        assertNull(controlFallbackText(c))
    }

    @Test
    fun malformedEntryDoesNotThrow() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to 3, "userIds" to "bad", "title" to listOf(1), "userId" to "z")))
        assertNull(c.event)
        assertTrue(c.userIds.isEmpty())
        assertNull(c.title)
        assertNull(c.userId)
        assertNull(controlFallbackText(c))
    }

    @Test
    fun fallbackTexts() {
        val bot = Attachment.Control("botStarted", null, emptyList(), null, null, null, emptyMap<Any?, Any?>())
        assertEquals("Бот запущен", controlFallbackText(bot))
        assertEquals("Чат создан", controlFallbackText(bot.copy(event = "new")))
    }

    @Test
    fun isService() {
        fun msg(attaches: List<Any?>) = MaxMessage.from(mapOf("id" to 1L, "time" to 2L, "type" to "USER", "text" to "", "attaches" to attaches))!!
        assertTrue(msg(listOf(mapOf("_type" to "CONTROL", "event" to "botStarted"))).isService)
        assertFalse(msg(listOf(mapOf("_type" to "PHOTO", "photoId" to 1))).isService)
        assertFalse(msg(emptyList()).isService)
    }
}
