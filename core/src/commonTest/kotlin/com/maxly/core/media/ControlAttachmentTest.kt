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

    @Test
    fun parsesServerTexts() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "add", "message" to "Иван добавил Петра", "shortMessage" to "Пётр добавлен")))
        assertEquals("Иван добавил Петра", c.message)
        assertEquals("Пётр добавлен", c.shortMessage)
        assertEquals("Пётр добавлен", controlFallbackText(c))
        assertEquals("Иван добавил Петра", controlFallbackText(c.copy(shortMessage = null)))
    }

    @Test
    fun serverTextsOfWrongTypeAreNull() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "add", "message" to 5, "shortMessage" to listOf("x"))))
        assertNull(c.message)
        assertNull(c.shortMessage)
        assertEquals("Добавлены участники", controlFallbackText(c))
    }

    @Test
    fun blankServerTextsAreIgnored() {
        val c = Attachment.Control("leave", null, emptyList(), null, null, null, emptyMap<Any?, Any?>(), message = "Ушёл", shortMessage = "  ")
        assertEquals("Ушёл", controlFallbackText(c))
        assertEquals("Участник покинул(а) чат", controlFallbackText(c.copy(message = "")))
    }

    @Test
    fun serverTextComesFirstThenOwnText() {
        val c = Attachment.Control("botStarted", null, emptyList(), "Team", null, null, emptyMap<Any?, Any?>(), message = "m", shortMessage = "s")
        assertEquals("s", controlFallbackText(c))
        assertEquals("m", controlFallbackText(c.copy(shortMessage = " ")))
        val own = c.copy(message = null, shortMessage = null)
        assertEquals("Бот запущен", controlFallbackText(own))
        assertEquals("Создан чат «Team»", controlFallbackText(own.copy(event = "new")))
        assertEquals("Чат создан", controlFallbackText(own.copy(event = "new", title = " ")))
    }

    @Test
    fun messageWithoutTypeIsKept() {
        val m = MaxMessage.from(mapOf("id" to 1L, "time" to 2L, "text" to "", "attaches" to listOf(mapOf("_type" to "CONTROL", "event" to "add", "shortMessage" to "s"))))
        assertEquals("", m?.type)
        assertTrue(m!!.isService)
        assertEquals("", MaxMessage.from(mapOf("message" to mapOf("id" to 3L, "time" to 4L)), fallbackChatId = 9L)?.type)
        assertEquals("USER", MaxMessage.from(mapOf("id" to 1L, "time" to 2L, "type" to "USER"))?.type)
        assertNull(MaxMessage.from(mapOf("time" to 2L, "type" to "USER")))
        assertNull(MaxMessage.from(mapOf("id" to 1L, "type" to "USER")))
        assertNull(MaxMessage.from(mapOf("time" to 2L)))
    }

    private fun ctl(event: String, userIds: List<Long> = emptyList(), title: String? = null, userId: Long? = null, chatType: String? = null) =
        Attachment.Control(event, userId, userIds, title, null, chatType, emptyMap<Any?, Any?>())

    private val names = mapOf(1L to "Иван", 101L to "Анна", 202L to "Борис", 303L to "Вера")
    private fun text(c: Attachment.Control, actor: Long?, known: Map<Long, String> = names) =
        controlFallbackText(c, selfId = 1L, actorId = actor, nameOf = { known[it] })

    @Test
    fun parsesNewFields() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "add", "photoToken" to "pt", "showHistory" to true, "startPayload" to "ref")))
        assertEquals("pt", c.photoToken)
        assertEquals(true, c.showHistory)
        assertEquals("ref", c.startPayload)
        val bad = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "photoToken" to 1, "showHistory" to "yes")))
        assertNull(bad.photoToken)
        assertNull(bad.showHistory)
        assertNull(bad.startPayload)
    }

    @Test
    fun eventTextsNamedYouAndUnknown() {
        val anon = emptyMap<Long, String>()
        fun check(c: Attachment.Control, named: String, you: String, noName: String) {
            assertEquals(named, text(c, 101L), "named ${c.event}")
            assertEquals(you, text(c, 1L), "you ${c.event}")
            assertEquals(noName, text(c, 101L, anon), "noName ${c.event}")
        }
        check(ctl("new", title = "Дача", chatType = "CHAT"), "Анна создал(а) чат «Дача»", "Вы создали чат «Дача»", "Создан чат «Дача»")
        check(ctl("new", chatType = "CHANNEL", title = "Новости"), "Анна создал(а) канал «Новости»", "Вы создали канал «Новости»", "Создан канал «Новости»")
        check(ctl("add", listOf(202L, 303L)), "Анна добавил(а) Борис и Вера", "Вы добавили Борис и Вера", "Добавлены участники")
        check(ctl("remove", listOf(202L)), "Анна удалил(а) Борис", "Вы удалили Борис", "Удалены участники")
        check(ctl("leave"), "Анна покинул(а) чат", "Вы покинули чат", "Участник покинул(а) чат")
        check(ctl("title", title = "Т"), "Анна изменил(а) название на «Т»", "Вы изменили название на «Т»", "Название чата изменено на «Т»")
        check(ctl("icon"), "Анна изменил(а) фото чата", "Вы изменили фото чата", "Фото чата изменено")
        check(ctl("joinByLink"), "Анна вступил(а) в чат по ссылке", "Вы вступили в чат по ссылке", "Участник вступил(а) в чат по ссылке")
        check(ctl("hello"), "Анна теперь в MAX", "Вы теперь в MAX", "Контакт теперь в MAX")
        check(ctl("botStarted"), "Бот запущен", "Вы запустили бота", "Бот запущен")
        assertNull(text(ctl("pin"), 101L))
        assertNull(text(ctl("system"), 101L))
        assertEquals("Сбор в 10", text(ctl("pin").copy(shortMessage = "Сбор в 10"), 101L))
    }

    @Test
    fun nameOfIsUsedAndMayFail() {
        val asked = mutableListOf<Long>()
        val t = controlFallbackText(ctl("add", listOf(202L, 1L, 404L)), 1L, 101L) { asked += it; names[it] }
        assertEquals("Анна добавил(а) Борис, вас и ещё 1", t)
        assertTrue(101L in asked && 202L in asked)
        assertEquals("Участник покинул(а) чат", controlFallbackText(ctl("leave"), 1L, 101L) { error("boom") })
        assertEquals("Анна добавил(а) 21 участника", text(ctl("add", (1000L..1020L).toList()), 101L))
        assertEquals("Анна добавил(а) 12 участников", text(ctl("add", (1000L..1011L).toList()), 101L))
    }

    private class Case(val name: String, val raw: Map<String, Any?>, val sender: Long, val viewer: Long, val names: Map<Long, String>, val expect: String?)

    /** Shared fixtures fighxy/Maxly test-fixtures/service-messages (events.json, fallbacks.json). `*-core-text` cases carry no names (and no sender for botStarted), as their coreText predates names. */
    private val fixtures = listOf(
        Case("events/hello", mapOf("event" to "hello", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна теперь в MAX"),
        Case("events/hello-user-id", mapOf("event" to "hello", "userId" to 303L, "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера"), "Вера теперь в MAX"),
        Case("events/new-dialog", mapOf("event" to "new", "chatType" to "DIALOG", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна теперь в MAX"),
        Case("events/new-chat", mapOf("event" to "new", "chatType" to "CHAT", "title" to "Дача", "userIds" to listOf(202L, 303L), "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера", 101L to "Анна"), "Анна создал(а) чат «Дача»"),
        Case("events/new-chat-mine", mapOf("event" to "new", "chatType" to "CHAT", "title" to "Дача", "userIds" to listOf(202L), "_type" to "CONTROL"), 1L, 1L, mapOf(202L to "Борис", 303L to "Вера", 1L to "Иван"), "Вы создали чат «Дача»"),
        Case("events/new-chat-untitled", mapOf("event" to "new", "chatType" to "CHAT", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна создал(а) чат"),
        Case("events/new-channel", mapOf("event" to "new", "chatType" to "CHANNEL", "title" to "Новости", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна создал(а) канал «Новости»"),
        Case("events/add-two", mapOf("event" to "add", "userIds" to listOf(202L, 303L), "showHistory" to true, "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера", 101L to "Анна"), "Анна добавил(а) Борис и Вера"),
        Case("events/add-viewer", mapOf("event" to "add", "userIds" to listOf(1L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна добавил(а) вас"),
        Case("events/add-self", mapOf("event" to "add", "userIds" to listOf(101L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна вступил(а) в чат"),
        Case("events/add-mine", mapOf("event" to "add", "userIds" to listOf(202L), "_type" to "CONTROL"), 1L, 1L, mapOf(202L to "Борис", 303L to "Вера", 1L to "Иван"), "Вы добавили Борис"),
        Case("events/remove", mapOf("event" to "remove", "userIds" to listOf(202L), "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера", 101L to "Анна"), "Анна удалил(а) Борис"),
        Case("events/remove-self", mapOf("event" to "remove", "userIds" to listOf(101L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна покинул(а) чат"),
        Case("events/remove-viewer", mapOf("event" to "remove", "userId" to 1L, "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна удалил(а) вас"),
        Case("events/leave", mapOf("event" to "leave", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна покинул(а) чат"),
        Case("events/leave-mine", mapOf("event" to "leave", "_type" to "CONTROL"), 1L, 1L, mapOf(1L to "Иван"), "Вы покинули чат"),
        Case("events/join-by-link", mapOf("event" to "joinByLink", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна вступил(а) в чат по ссылке"),
        Case("events/title", mapOf("event" to "title", "title" to "Дача 2027", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна изменил(а) название на «Дача 2027»"),
        Case("events/title-mine", mapOf("event" to "title", "title" to "Дача 2027", "_type" to "CONTROL"), 1L, 1L, mapOf(1L to "Иван"), "Вы изменили название на «Дача 2027»"),
        Case("events/icon", mapOf("event" to "icon", "url" to "https://i.example/chat.jpg", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна изменил(а) фото чата"),
        Case("events/bot-started", mapOf("event" to "botStarted", "startPayload" to "ref42", "_type" to "CONTROL"), 1L, 1L, mapOf(1L to "Иван"), "Вы запустили бота"),
        Case("events/system", mapOf("event" to "system", "shortMessage" to "Номер телефона подтверждён", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Номер телефона подтверждён"),
        Case("events/pin-is-not-an-event", mapOf("event" to "pin", "pinnedMessage" to mapOf("id" to 77L, "text" to "Сбор в 10"), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), null),
        Case("events/unpin-is-not-an-event", mapOf("event" to "unpin", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), null),
        Case("events/new-chat-core-text", mapOf("event" to "new", "chatType" to "CHAT", "title" to "Дача", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Создан чат «Дача»"),
        Case("events/bot-started-core-text", mapOf("event" to "botStarted", "_type" to "CONTROL"), 0L, 1L, emptyMap(), "Бот запущен"),
        Case("events/add-server-core-text", mapOf("event" to "add", "userIds" to listOf(102L), "shortMessage" to "Сервер: участник добавлен", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Сервер: участник добавлен"),
        Case("events/hello-server-core-text", mapOf("event" to "hello", "shortMessage" to "Сервер: контакт в сети", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Сервер: контакт в сети"),
        Case("events/unknown-server-core-text", mapOf("event" to "futureEvent", "message" to "Сервер: новое событие", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Сервер: новое событие"),
        Case("fallbacks/unknown-event", mapOf("event" to "present", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), null),
        Case("fallbacks/unknown-event-with-text", mapOf("event" to "widget", "message" to "Новый виджет", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Новый виджет"),
        Case("fallbacks/no-event", mapOf("_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), null),
        Case("fallbacks/system-without-text", mapOf("event" to "system", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), null),
        Case("fallbacks/hello-without-name", mapOf("event" to "hello", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Контакт теперь в MAX"),
        Case("fallbacks/leave-without-name", mapOf("event" to "leave", "_type" to "CONTROL"), 101L, 1L, emptyMap(), "Участник покинул(а) чат"),
        Case("fallbacks/add-unknown-names", mapOf("event" to "add", "userIds" to listOf(404L, 405L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна добавил(а) 2 участника"),
        Case("fallbacks/add-five-unknown", mapOf("event" to "add", "userIds" to listOf(401L, 402L, 403L, 404L, 405L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна добавил(а) 5 участников"),
        Case("fallbacks/add-one-unknown", mapOf("event" to "add", "userIds" to listOf(404L), "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна добавил(а) участника"),
        Case("fallbacks/add-known-and-unknown", mapOf("event" to "add", "userIds" to listOf(202L, 404L, 405L), "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера", 101L to "Анна"), "Анна добавил(а) Борис и ещё 2"),
        Case("fallbacks/add-three-known", mapOf("event" to "add", "userIds" to listOf("202", "303", "505"), "_type" to "CONTROL"), 101L, 1L, mapOf(202L to "Борис", 303L to "Вера", 505L to "Глеб", 101L to "Анна"), "Анна добавил(а) Борис, Вера и Глеб"),
        Case("fallbacks/remove-without-users", mapOf("event" to "remove", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна удалил(а) участника"),
        Case("fallbacks/title-without-title", mapOf("event" to "title", "title" to "  ", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна изменил(а) название чата"),
        Case("fallbacks/event-case", mapOf("event" to "JOINBYLINK", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна вступил(а) в чат по ссылке"),
        Case("fallbacks/type-key", mapOf("event" to "leave", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна покинул(а) чат"),
        Case("fallbacks/zero-user-id", mapOf("event" to "hello", "userId" to 0L, "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна теперь в MAX"),
        Case("fallbacks/photo-and-control", mapOf("event" to "icon", "_type" to "CONTROL"), 101L, 1L, mapOf(101L to "Анна"), "Анна изменил(а) фото чата"),
    )

    @Test
    fun sharedFixtures() {
        for (c in fixtures) {
            val control = assertIs<Attachment.Control>(Attachment.from(c.raw), c.name)
            assertEquals(c.expect, controlFallbackText(control, c.viewer, c.sender) { c.names[it] }, c.name)
        }
    }
}
