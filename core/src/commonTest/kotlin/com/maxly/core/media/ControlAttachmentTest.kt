package com.maxly.core.media

import com.maxly.core.api.MaxMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertEquals("Сообщение закреплено", text(ctl("pin"), 101L))
        assertEquals("Закрепление снято", text(ctl("unpin"), 1L))
        assertNull(text(ctl("system"), 101L))
        assertEquals("Сбор в 10", text(ctl("pin").copy(shortMessage = "Сбор в 10"), 101L))
    }

    @Test
    fun pinnedMessage() {
        val c = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to mapOf("id" to "77", "text" to "Сбор в 10"))))
        assertEquals(77L, c.pinnedMessageId)
        assertEquals("Сбор в 10", c.pinnedText)
        assertEquals("Закреплено: Сбор в 10", controlFallbackText(c))
        assertEquals("Закреплено: Сбор в 10", controlFallbackText(c, selfId = 1L, actorId = 1L))
        assertEquals("Сервер", controlFallbackText(c.copy(shortMessage = "Сервер")))
        assertEquals("Сервер", controlFallbackText(c.copy(message = "Сервер")))
        assertEquals("Закреплено: Сбор в 10", controlFallbackText(c.copy(event = "PIN", shortMessage = " ")))

        val idOnly = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to mapOf("id" to 78L, "text" to 5))))
        assertEquals(78L, idOnly.pinnedMessageId)
        assertNull(idOnly.pinnedText)
        assertEquals("Сообщение закреплено", controlFallbackText(idOnly))
        assertEquals("Закреплено: из базы", controlFallbackText(idOnly, pinnedText = " из базы "))
        assertEquals("Закреплено: своё", controlFallbackText(c.copy(pinnedText = "своё"), pinnedText = "из базы"))
        assertEquals("Закреплено: из базы", controlFallbackText(c.copy(pinnedText = "  "), pinnedText = "из базы"))
        assertEquals("Сообщение закреплено", controlFallbackText(c.copy(pinnedText = "\n \n")))

        val bare = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "pin")))
        assertNull(bare.pinnedMessageId)
        assertEquals("Сообщение закреплено", controlFallbackText(bare))
        val bad = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to "77")))
        assertNull(bad.pinnedMessageId)
        assertNull(bad.pinnedText)

        val unpin = assertIs<Attachment.Control>(Attachment.from(mapOf("_type" to "CONTROL", "event" to "unpin")))
        assertEquals(ControlEvents.UNPIN, unpin.event)
        assertEquals("Закрепление снято", controlFallbackText(unpin, 1L, 1L))
        assertEquals("Снято", controlFallbackText(unpin.copy(shortMessage = "Снято")))
    }

    @Test
    fun pinPreviewShortens() {
        assertNull(pinPreview(null))
        assertNull(pinPreview(" \n\t"))
        assertEquals("Первая", pinPreview("\n  Первая  \nвторая"))
        val exact = "а".repeat(PIN_PREVIEW_MAX)
        assertEquals(exact, pinPreview(exact))
        val long = pinPreview("б".repeat(80))!!
        assertEquals(PIN_PREVIEW_MAX, long.length)
        assertEquals("б".repeat(PIN_PREVIEW_MAX - 1) + "…", long)
        assertEquals("слово…", pinPreview("слово" + " ".repeat(60) + "x"))
        val emoji = pinPreview("x".repeat(PIN_PREVIEW_MAX - 2) + "😀😀")!!
        assertEquals("x".repeat(PIN_PREVIEW_MAX - 2) + "…", emoji)
        assertEquals("Закреплено: " + "в".repeat(PIN_PREVIEW_MAX - 1) + "…", controlFallbackText(ctl("pin").copy(pinnedText = "в".repeat(200))))
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

    /**
     * One case of the shared fixtures, verbatim: [sender] / [senderName] / [text] / [attaches] of
     * the server message, [viewer] the account, [names] the names the client knows, [coreText] the
     * expected [controlFallbackText] (if given) and [expect] the line the client shows.
     */
    private class Fixture(
        val name: String,
        val sender: Long,
        val senderName: String,
        val text: String,
        val attaches: List<Any?>,
        val viewer: Long,
        val names: Map<Long, String>,
        val coreText: String?,
        val expect: String,
    )

    /**
     * fighxy/Maxly test-fixtures/service-messages at 33964e8 (events.json, fallbacks.json), all
     * cases, generated from the JSON; keep it in sync with the files, never edit a case by hand.
     */
    private val fixtures = listOf(
        Fixture("events/hello", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "hello")), 1L, emptyMap(), null, "Анна теперь в MAX"),
        Fixture("events/hello-user-id", 101L, "", "", listOf(mapOf("_type" to "CONTROL", "event" to "hello", "userId" to 303L)), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Вера теперь в MAX"),
        Fixture("events/new-dialog", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "DIALOG")), 1L, emptyMap(), null, "Анна теперь в MAX"),
        Fixture("events/new-chat", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to "Дача", "userIds" to listOf(202L, 303L))), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Анна создал(а) чат «Дача»"),
        Fixture("events/new-chat-mine", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to "Дача", "userIds" to listOf(202L))), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Вы создали чат «Дача»"),
        Fixture("events/new-chat-untitled", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT")), 1L, emptyMap(), null, "Анна создал(а) чат"),
        Fixture("events/new-channel", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHANNEL", "title" to "Новости")), 1L, emptyMap(), null, "Анна создал(а) канал «Новости»"),
        Fixture("events/add-two", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(202L, 303L), "showHistory" to true)), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Анна добавил(а) Борис и Вера"),
        Fixture("events/add-viewer", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(1L))), 1L, emptyMap(), null, "Анна добавил(а) вас"),
        Fixture("events/add-self", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(101L))), 1L, emptyMap(), null, "Анна вступил(а) в чат"),
        Fixture("events/add-mine", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(202L))), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Вы добавили Борис"),
        Fixture("events/remove", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "remove", "userIds" to listOf(202L))), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Анна удалил(а) Борис"),
        Fixture("events/remove-self", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "remove", "userIds" to listOf(101L))), 1L, emptyMap(), null, "Анна покинул(а) чат"),
        Fixture("events/remove-viewer", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "remove", "userId" to 1L)), 1L, emptyMap(), null, "Анна удалил(а) вас"),
        Fixture("events/leave", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "leave")), 1L, emptyMap(), null, "Анна покинул(а) чат"),
        Fixture("events/leave-mine", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "leave")), 1L, emptyMap(), null, "Вы покинули чат"),
        Fixture("events/join-by-link", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "joinByLink")), 1L, emptyMap(), null, "Анна вступил(а) в чат по ссылке"),
        Fixture("events/title", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "title", "title" to "Дача 2027")), 1L, emptyMap(), null, "Анна изменил(а) название на «Дача 2027»"),
        Fixture("events/title-mine", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "title", "title" to "Дача 2027")), 1L, emptyMap(), null, "Вы изменили название на «Дача 2027»"),
        Fixture("events/icon", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "icon", "url" to "https://i.example/chat.jpg")), 1L, emptyMap(), null, "Анна изменил(а) фото чата"),
        Fixture("events/bot-started", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "botStarted", "startPayload" to "ref42")), 1L, emptyMap(), null, "Вы запустили бота"),
        Fixture("events/system", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "system", "shortMessage" to "Номер телефона подтверждён")), 1L, emptyMap(), null, "Номер телефона подтверждён"),
        Fixture("events/pin-is-not-an-event", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "pin", "pinnedMessage" to mapOf("id" to 77L, "text" to "Сбор в 10"))), 1L, emptyMap(), "Закреплено: Сбор в 10", "Закреплено: Сбор в 10"),
        Fixture("events/unpin-is-not-an-event", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "unpin")), 1L, emptyMap(), "Закрепление снято", "Закрепление снято"),
        Fixture("events/new-chat-core-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to "Дача")), 1L, emptyMap(), "Анна создал(а) чат «Дача»", "Анна создал(а) чат «Дача»"),
        Fixture("events/bot-started-core-text", 1L, "Иван", "", listOf(mapOf("_type" to "CONTROL", "event" to "botStarted")), 1L, emptyMap(), "Вы запустили бота", "Вы запустили бота"),
        Fixture("events/new-dialog-core-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "DIALOG")), 1L, emptyMap(), "Анна теперь в MAX", "Анна теперь в MAX"),
        Fixture("events/add-server-core-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(102L), "shortMessage" to "Сервер: участник добавлен")), 1L, emptyMap(), "Сервер: участник добавлен", "Сервер: участник добавлен"),
        Fixture("events/hello-server-core-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "hello", "shortMessage" to "Сервер: контакт в сети")), 1L, emptyMap(), "Сервер: контакт в сети", "Сервер: контакт в сети"),
        Fixture("events/unknown-server-core-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "futureEvent", "message" to "Сервер: новое событие")), 1L, emptyMap(), "Сервер: новое событие", "Сервер: новое событие"),
        Fixture("fallbacks/unknown-event", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "present")), 1L, emptyMap(), null, "Служебное сообщение"),
        Fixture("fallbacks/unknown-event-with-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "widget", "message" to "Новый виджет")), 1L, emptyMap(), null, "Новый виджет"),
        Fixture("fallbacks/no-event", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL")), 1L, emptyMap(), null, "Служебное сообщение"),
        Fixture("fallbacks/system-without-text", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "system")), 1L, emptyMap(), null, "Служебное сообщение"),
        Fixture("fallbacks/system-message-text", 101L, "Анна", "Вход с нового устройства", listOf(mapOf("_type" to "CONTROL", "event" to "system")), 1L, emptyMap(), null, "Вход с нового устройства"),
        Fixture("fallbacks/hello-without-name", 101L, "", "", listOf(mapOf("_type" to "CONTROL", "event" to "hello")), 1L, emptyMap(), null, "Контакт теперь в MAX"),
        Fixture("fallbacks/leave-without-name", 101L, "", "", listOf(mapOf("_type" to "CONTROL", "event" to "leave")), 1L, emptyMap(), null, "Участник покинул(а) чат"),
        Fixture("fallbacks/add-unknown-names", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(404L, 405L))), 1L, emptyMap(), null, "Анна добавил(а) 2 участника"),
        Fixture("fallbacks/add-five-unknown", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(401L, 402L, 403L, 404L, 405L))), 1L, emptyMap(), null, "Анна добавил(а) 5 участников"),
        Fixture("fallbacks/add-one-unknown", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(404L))), 1L, emptyMap(), null, "Анна добавил(а) участника"),
        Fixture("fallbacks/add-known-and-unknown", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf(202L, 404L, 405L))), 1L, mapOf(202L to "Борис", 303L to "Вера"), null, "Анна добавил(а) Борис и ещё 2"),
        Fixture("fallbacks/add-three-known", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "add", "userIds" to listOf("202", "303", "505"))), 1L, mapOf(202L to "Борис", 303L to "Вера", 505L to "Глеб"), null, "Анна добавил(а) Борис, Вера и Глеб"),
        Fixture("fallbacks/remove-without-users", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "remove")), 1L, emptyMap(), null, "Анна удалил(а) участника"),
        Fixture("fallbacks/title-without-title", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "title", "title" to "  ")), 1L, emptyMap(), null, "Анна изменил(а) название чата"),
        Fixture("fallbacks/event-case", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "JOINBYLINK")), 1L, emptyMap(), null, "Анна вступил(а) в чат по ссылке"),
        Fixture("fallbacks/type-key", 101L, "Анна", "", listOf(mapOf("type" to "control", "event" to "leave")), 1L, emptyMap(), null, "Анна покинул(а) чат"),
        Fixture("fallbacks/zero-user-id", 101L, "Анна", "", listOf(mapOf("_type" to "CONTROL", "event" to "hello", "userId" to 0L)), 1L, emptyMap(), null, "Анна теперь в MAX"),
        Fixture("fallbacks/photo-and-control", 101L, "Анна", "", listOf(mapOf("_type" to "PHOTO", "photoId" to 5L, "baseUrl" to "https://i.example/5"), mapOf("_type" to "CONTROL", "event" to "icon")), 1L, emptyMap(), null, "Анна изменил(а) фото чата"),
    )

    /** The first `CONTROL` attachment, parsed by the core as is (`_type` or `type`, any case). */
    private fun controlOf(attaches: List<Any?>): Attachment.Control? =
        attaches.map(Attachment::from).firstNotNullOfOrNull { it as? Attachment.Control }

    /**
     * Plays a case as the clients do: account [Fixture.viewer], actor [Fixture.sender], names from
     * [Fixture.names] plus the sender's [Fixture.senderName] (blank ones unknown). The line is the
     * core's text, else the message text, else «Служебное сообщение» (README rule 5).
     */
    private fun play(c: Fixture): Pair<String?, String> {
        val control = assertNotNull(controlOf(c.attaches), c.name)
        val known = c.names + (c.sender to c.senderName)
        val core = controlFallbackText(control, c.viewer, c.sender) { id -> known[id]?.takeIf { it.isNotBlank() } }
        val line = core?.trim()?.takeIf { it.isNotEmpty() } ?: c.text.trim().takeIf { it.isNotEmpty() } ?: "Служебное сообщение"
        return core to line
    }

    @Test
    fun sharedFixtures() {
        assertEquals(48, fixtures.size)
        assertEquals(fixtures.size, fixtures.map { it.name }.toSet().size)
        for (c in fixtures) {
            val (core, line) = play(c)
            c.coreText?.let { assertEquals(it, core, "${c.name}: coreText") }
            assertEquals(c.expect, line, "${c.name}: text")
        }
    }

    /** Variants of shared cases without the context the fixtures now give (no names, not own). */
    @Test
    fun namelessVariants() {
        fun core(raw: Map<String, Any?>, sender: Long) = controlFallbackText(assertNotNull(controlOf(listOf(raw))), 1L, sender)
        assertEquals("Создан чат «Дача»", core(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "CHAT", "title" to "Дача"), 101L))
        assertEquals("Бот запущен", core(mapOf("_type" to "CONTROL", "event" to "botStarted"), 101L))
        assertEquals("Бот запущен", core(mapOf("_type" to "CONTROL", "event" to "botStarted"), 0L))
        assertEquals("Контакт теперь в MAX", core(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "DIALOG"), 101L))
        assertEquals("Контакт теперь в MAX", core(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "dialog", "title" to "Анна"), 0L))
        assertEquals("Вы теперь в MAX", core(mapOf("_type" to "CONTROL", "event" to "new", "chatType" to "DIALOG"), 1L))
        assertEquals("Вы теперь в MAX", controlFallbackText(ctl("new", chatType = "DIALOG"), 1L, 1L) { "Иван" })
        assertEquals("Анна теперь в MAX", text(ctl("new", chatType = "DIALOG"), 101L))
        assertEquals("Сервер", controlFallbackText(ctl("new", chatType = "DIALOG").copy(shortMessage = "Сервер"), 1L, 101L))
    }

    @Test
    fun controlTypeSpellings() {
        for (raw in listOf(
            mapOf("_type" to "CONTROL", "event" to "leave"),
            mapOf("_type" to "control", "event" to "leave"),
            mapOf("type" to "control", "event" to "leave"),
            mapOf("type" to "CONTROL", "event" to "leave"),
            mapOf("type" to "Control", "event" to "leave"),
        )) {
            val c = assertIs<Attachment.Control>(Attachment.from(raw), raw.toString())
            assertEquals("leave", c.event)
            assertEquals(raw, c.raw)
        }
        assertTrue(MaxMessage.from(mapOf("id" to 1L, "time" to 1L, "type" to "USER", "attaches" to listOf(mapOf("type" to "control", "event" to "leave"))))!!.isService)
        // An entry with `_type` keeps it, whatever its `type` says; other types stay case-sensitive.
        assertIs<Attachment.Photo>(Attachment.from(mapOf("_type" to "PHOTO", "photoId" to 5L, "type" to "control")))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("_type" to "SHARE", "type" to "control")))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("_type" to 3, "type" to "control")))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("type" to "photo", "photoId" to 5L)))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("_type" to "photo", "photoId" to 5L)))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("type" to "controls")))
        assertIs<Attachment.Unknown>(Attachment.from(mapOf("type" to 1)))
        assertFalse(MaxMessage.from(mapOf("id" to 1L, "time" to 1L, "type" to "USER", "attaches" to listOf(mapOf("_type" to "SHARE", "type" to "control"))))!!.isService)
    }
}
