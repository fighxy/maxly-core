package com.maxly.core.media

import com.maxly.core.api.MaxMessage
import com.maxly.core.api.PollAnswer
import com.maxly.core.api.PollFlag
import com.maxly.core.api.PollState
import com.maxly.core.api.asLong

/**
 * An attachment to send in `MSG_SEND` `message.attaches`, produced by an upload (see
 * [MediaApi]). [toPayload] follows PyMax `src/pymax/api/uploads/payloads.py` (`CamelModel`
 * with `_type`, `None` fields left out; `VideoAttachPayload.serialize_attachment` for video /
 * voice / video-note).
 *
 * Audio / voice messages are [Voice] (`_type: AUDIO`); there is no separate audio-upload path in
 * the references — PyMax `upload_voice` uses `VIDEO_UPLOAD` with `type=2, uploaderType=1`.
 * Stickers: incoming only ([Attachment.Sticker]); `STICKER_UPLOAD` 81 and an outgoing sticker
 * attach payload are not implemented in PyMax or kolibri.
 */
sealed interface OutgoingAttachment {
    fun toPayload(): Map<String, Any?>

    /** `{_type: "PHOTO", photoToken}` (PyMax `AttachPhotoPayload`). */
    data class Photo(val photoToken: String) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "PHOTO", "photoToken" to photoToken)
    }

    /** `{_type: "FILE", fileId}` (PyMax `AttachFilePayload`). */
    data class File(val fileId: Long) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "FILE", "fileId" to fileId)
    }

    /** `{_type: "VIDEO", videoId, token, videoType: 0}` (PyMax `VideoAttachPayload`, regular video). */
    data class Video(val videoId: Long, val token: String) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> =
            linkedMapOf("_type" to "VIDEO", "videoId" to videoId, "token" to token, "videoType" to 0)
    }

    /**
     * Voice / audio message: `{_type: "AUDIO", token, duration, wave}` (PyMax `VoiceAttachPayload`;
     * its serializer drops `videoId` / `videoType` for AUDIO). [uploadId] is the `videoId` of the
     * upload slot — not sent, used to match the `NOTIF_ATTACH {audioId}` signal. PyMax sends
     * `wave` as 80 zero bytes ([SILENT_WAVE]); [durationMs] in milliseconds.
     */
    data class Voice(val uploadId: Long, val token: String, val durationMs: Long, val wave: ByteArray = SILENT_WAVE) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> =
            linkedMapOf("_type" to "AUDIO", "token" to token, "duration" to durationMs, "wave" to wave)

        override fun equals(other: Any?): Boolean =
            other is Voice && uploadId == other.uploadId && token == other.token && durationMs == other.durationMs && wave.contentEquals(other.wave)

        override fun hashCode(): Int = (uploadId.hashCode() * 31 + token.hashCode()) * 31 + durationMs.hashCode()

        companion object {
            /** PyMax `upload_voice`: `wave=b"\x00" * 80`. */
            val SILENT_WAVE: ByteArray get() = ByteArray(80)
        }
    }

    /**
     * Round video note (видеосообщение): PyMax `VideoNoteAttachPayload` (`video_type = 1`).
     * With a [token] the serializer drops `videoId` and keeps `{_type: VIDEO, token, videoType: 1,
     * thumbhash?, duration?}`; [uploadId] is the slot `videoId` (excluded from the payload), used
     * to match `NOTIF_ATTACH {videoId}` on `attachment.not.ready`. [thumbhash] comes from the CDN
     * JSON after the POST (PyMax base64-decodes with `=` padding).
     */
    data class VideoNote(
        val uploadId: Long,
        val token: String,
        val durationMs: Long? = null,
        val thumbhash: ByteArray? = null,
    ) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            put("_type", "VIDEO")
            put("token", token)
            put("videoType", 1)
            if (thumbhash != null) put("thumbhash", thumbhash)
            if (durationMs != null) put("duration", durationMs)
        }

        override fun equals(other: Any?): Boolean =
            other is VideoNote && uploadId == other.uploadId && token == other.token &&
                durationMs == other.durationMs &&
                ((thumbhash == null && other.thumbhash == null) ||
                    (thumbhash != null && other.thumbhash != null && thumbhash.contentEquals(other.thumbhash)))

        override fun hashCode(): Int =
            ((uploadId.hashCode() * 31 + token.hashCode()) * 31 + (durationMs?.hashCode() ?: 0)) * 31 +
                (thumbhash?.contentHashCode() ?: 0)
    }

    /**
     * A contact card of a MAX user: `{_type: "CONTACT", contactId}` (Komet `sendContactMessage`;
     * PyMax has no outgoing contact). [contactId] is the user id; no upload is needed. The server
     * fills in the name, phone and photo of the received copy.
     */
    data class Contact(val contactId: Long) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "CONTACT", "contactId" to contactId)
    }

    /** A sticker of the server catalog (KometTeam/Komet `sendStickerMessage`): `{_type: STICKER, stickerId}`. */
    data class Sticker(val stickerId: Long) : OutgoingAttachment {
        override fun toPayload(): Map<String, Any?> = linkedMapOf("_type" to "STICKER", "stickerId" to stickerId)
    }

    /**
     * A poll (PyMax `Poll`): `{title, answers: [{text, answerId?}], settings: <PollFlag bits>,
     * _type: "POLL"}`; no upload needed.
     */
    data class Poll(val title: String, val answers: List<PollAnswer>, val flags: Set<PollFlag> = emptySet()) : OutgoingAttachment {
        init {
            require(title.isNotEmpty()) { "poll title must not be empty" }
            require(answers.size >= 2) { "a poll needs at least two answers" }
        }

        override fun toPayload(): Map<String, Any?> = linkedMapOf(
            "title" to title,
            "answers" to answers.map { a ->
                linkedMapOf<String, Any?>("text" to a.text).apply { if (a.answerId != null) put("answerId", a.answerId) }
            },
            "settings" to PollFlag.mask(flags),
            "_type" to "POLL",
        )
    }
}

/**
 * An attachment of a received message (`attaches[]`, keyed by `_type`; PyMax
 * `src/pymax/types/domain/attachments/`). Only the ids are required here; every model keeps [raw].
 */
sealed interface Attachment {
    val raw: Map<*, *>

    /** PyMax `PhotoAttachment`: `photoId`, `photoToken`, `baseUrl`, `width`, `height`. */
    data class Photo(val photoId: Long, val photoToken: String?, val baseUrl: String?, val width: Int?, val height: Int?, override val raw: Map<*, *>) : Attachment

    /** PyMax `VideoAttachment`: `videoId`, `token`, `width`, `height`, `duration`, `videoType`, `thumbnail`. */
    data class Video(
        val videoId: Long,
        val token: String?,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val videoType: Int?,
        val thumbnail: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** PyMax `FileAttachment`: `fileId`, `name`, `size`, `token`. */
    data class File(val fileId: Long, val name: String?, val size: Long?, val token: String?, override val raw: Map<*, *>) : Attachment

    /** PyMax `AudioAttachment` (all optional there): `audioId`, `duration`, `url`, `token`, `wave`, `transcriptionStatus`. */
    data class Audio(
        val audioId: Long?,
        val durationMs: Long?,
        val url: String?,
        val token: String?,
        val transcriptionStatus: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /**
     * PyMax `StickerAttachment`: `stickerId`, `url`, `width`, `height`, `stickerType`, `audio`,
     * `time`, optional `setId` / `tags` / `lottieUrl` / `authorType`. Sending stickers and
     * `STICKER_UPLOAD` 81 are not sourced in the references.
     */
    data class Sticker(
        val stickerId: Long,
        val url: String?,
        val width: Int?,
        val height: Int?,
        val stickerType: String?,
        val audio: Boolean?,
        val time: Long?,
        val setId: Long?,
        val tags: List<String>?,
        val lottieUrl: String?,
        val authorType: String?,
        override val raw: Map<*, *>,
    ) : Attachment

    /** PyMax `PollAttachment`: `pollId`, `version`, `state`, `title`, `answers`, `settings` bits. */
    data class Poll(
        val pollId: Long,
        val version: Int?,
        val title: String?,
        val answers: List<PollAnswer>,
        val flags: Set<PollFlag>,
        val state: PollState?,
        override val raw: Map<*, *>,
    ) : Attachment

    /**
     * A service message (`_type: "CONTROL"`): chat created, member added or left, title changed,
     * bot started and so on. Every field is optional and read defensively; the rest stays in
     * [raw]. Known events: [ControlEvents]; see [controlFallbackText].
     *
     * [message] and [shortMessage] are the server's ready-made texts (`message`, `shortMessage`),
     * if sent. They follow [raw] with `null` defaults so existing positional calls still compile.
     */
    data class Control(
        val event: String?,
        val userId: Long?,
        val userIds: List<Long>,
        val title: String?,
        val url: String?,
        val chatType: String?,
        override val raw: Map<*, *>,
        val message: String? = null,
        val shortMessage: String? = null,
        /** New chat photo token (`photoToken`, [ControlEvents.ICON]). */
        val photoToken: String? = null,
        /** Added members see earlier history (`showHistory`, [ControlEvents.ADD]). */
        val showHistory: Boolean? = null,
        /** Deep-link payload of [ControlEvents.BOT_STARTED] (`startPayload`). */
        val startPayload: String? = null,
        /** Id of the pinned message (`pinnedMessage.id`, [ControlEvents.PIN]); clients open it on tap. */
        val pinnedMessageId: Long? = null,
        /** Text of the pinned message as sent (`pinnedMessage.text`, [ControlEvents.PIN]). */
        val pinnedText: String? = null,
    ) : Attachment

    /** Any other `_type` (CONTACT, CALL, SHARE, INLINE_KEYBOARD, ...) or a malformed entry. */
    data class Unknown(val type: String?, override val raw: Map<*, *>) : Attachment

    companion object {
        /**
         * `true` for a `CONTROL` entry: `_type` `CONTROL` in any case, or, with no `_type`, `type`
         * `control` in any case (some payloads spell it so). Other `_type`s are case-sensitive, and
         * an entry with a `_type` is never reclassified by its `type`.
         */
        internal fun isControlType(m: Map<*, *>): Boolean {
            val t = m["_type"]
            return if (t != null) (t as? String).equals("CONTROL", ignoreCase = true)
            else (m["type"] as? String).equals("control", ignoreCase = true)
        }

        fun from(value: Any?): Attachment {
            val m = value as? Map<*, *> ?: return Unknown(null, emptyMap<Any?, Any?>())
            val type = m["_type"] as? String
            fun int(key: String) = m[key].asLong()?.toInt()
            return when (if (isControlType(m)) "CONTROL" else type) {
                "PHOTO" -> m["photoId"].asLong()?.let { Photo(it, m["photoToken"] as? String, m["baseUrl"] as? String, int("width"), int("height"), m) }
                "VIDEO" -> m["videoId"].asLong()?.let {
                    Video(it, m["token"] as? String, int("width"), int("height"), m["duration"].asLong(), int("videoType"), m["thumbnail"] as? String, m)
                }
                "FILE" -> m["fileId"].asLong()?.let { File(it, m["name"] as? String, m["size"].asLong(), m["token"] as? String, m) }
                "AUDIO" -> Audio(m["audioId"].asLong(), m["duration"].asLong(), m["url"] as? String, m["token"] as? String, m["transcriptionStatus"] as? String, m)
                "STICKER" -> m["stickerId"].asLong()?.let { id ->
                    val tags = (m["tags"] as? List<*>)?.mapNotNull { it as? String }
                    Sticker(
                        id, m["url"] as? String, int("width"), int("height"), m["stickerType"] as? String,
                        m["audio"] as? Boolean, m["time"].asLong(), m["setId"].asLong(), tags,
                        m["lottieUrl"] as? String, m["authorType"] as? String, m,
                    )
                }
                "POLL" -> m["pollId"].asLong()?.let { id ->
                    val answers = (m["answers"] as? List<*>).orEmpty().mapNotNull { a ->
                        val am = a as? Map<*, *> ?: return@mapNotNull null
                        PollAnswer(am["text"] as? String ?: return@mapNotNull null, am["answerId"].asLong())
                    }
                    Poll(id, int("version"), m["title"] as? String, answers, PollFlag.of(int("settings") ?: 0), PollState.from(m["state"]), m)
                }
                "CONTROL" -> Control(
                    m["event"] as? String,
                    m["userId"].asLong(),
                    (m["userIds"] as? List<*>).orEmpty().mapNotNull { it.asLong() },
                    m["title"] as? String,
                    m["url"] as? String,
                    m["chatType"] as? String,
                    m,
                    m["message"] as? String,
                    m["shortMessage"] as? String,
                    m["photoToken"] as? String,
                    m["showHistory"] as? Boolean,
                    m["startPayload"] as? String,
                    (m["pinnedMessage"] as? Map<*, *>)?.get("id").asLong(),
                    (m["pinnedMessage"] as? Map<*, *>)?.get("text") as? String,
                )
                else -> null
            } ?: Unknown(type, m)
        }
    }
}

/** [MaxMessage.attaches] parsed as [Attachment]s. */
val MaxMessage.attachments: List<Attachment> get() = attaches.map(Attachment::from)

/** `true` when the message is a service message (any attachment is [Attachment.Control]). */
val MaxMessage.isService: Boolean get() = attachments.any { it is Attachment.Control }

/** `CONTROL` event names. Servers may vary the case; [controlFallbackText] ignores it. */
object ControlEvents {
    /** Chat, channel or dialog created ([com.maxly.core.api.ChatsApi.createGroup]). */
    const val NEW: String = "new"

    /** Members added (`userIds`, `showHistory`). */
    const val ADD: String = "add"

    /** Members removed (`userIds` / `userId`). */
    const val REMOVE: String = "remove"

    /** The sender left the chat. */
    const val LEAVE: String = "leave"

    /** Chat renamed (`title`). */
    const val TITLE: String = "title"

    /** Chat photo changed (`url`, `photoToken`, `crop`). */
    const val ICON: String = "icon"

    /** A contact joined MAX (`userId`, else the sender). */
    const val HELLO: String = "hello"

    /** Server notice; only the server's text describes it. */
    const val SYSTEM: String = "system"

    /** The sender joined by an invite link. */
    const val JOIN_BY_LINK: String = "joinByLink"

    /** Message pinned (`pinnedMessage: {id, text}`); clients show it in the pinned bar. */
    const val PIN: String = "pin"

    /** Pinned message unpinned (no `pinnedMessage`). */
    const val UNPIN: String = "unpin"

    /** Bot chat started ([com.maxly.core.api.MessagesApi.sendBotStart], `startPayload`). */
    const val BOT_STARTED: String = "botStarted"
}

/**
 * A neutral Russian line for a service message. The server's [Attachment.Control.shortMessage],
 * else [Attachment.Control.message] (blank ones skipped) comes first; otherwise a known event gets
 * its own text, «Вы» forms when [actorId] (the message sender) is [selfId], names from [nameOf]
 * («Участник» / impersonal forms when unknown). [ControlEvents.PIN] gives «Закреплено: <text>»
 * from [Attachment.Control.pinnedText], else [pinnedText] (e.g. the local copy of the message
 * [Attachment.Control.pinnedMessageId]), shortened by [pinPreview]; «Сообщение закреплено» without
 * text. [ControlEvents.UNPIN] gives «Закрепление снято». No author in either. [ControlEvents.SYSTEM]
 * and unknown events without server text give `null` (clients then show «Служебное сообщение»).
 * Pure; never throws.
 */
fun controlFallbackText(
    control: Attachment.Control,
    selfId: Long? = null,
    actorId: Long? = null,
    pinnedText: String? = null,
    nameOf: (Long) -> String? = { null },
): String? {
    control.shortMessage?.takeIf { it.isNotBlank() }?.let { return it }
    control.message?.takeIf { it.isNotBlank() }?.let { return it }
    val actor = actorId?.takeIf { it != 0L }
    val mine = actor != null && actor == selfId
    fun name(id: Long): String? = runCatching { nameOf(id) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val actorName = actor?.let(::name)
    // Subject phrase: «Вы» + plural verb, a name + neutral verb, or null when unknown.
    fun subj(verbPast: String, verbYou: String): String? = when {
        mine -> "Вы $verbYou"
        actorName != null -> "$actorName $verbPast"
        else -> null
    }
    val title = control.title?.trim()?.takeIf { it.isNotEmpty() }
    val targets = control.userIds.filter { it != 0L }.ifEmpty { listOfNotNull(control.userId?.takeIf { it != 0L }) }
    val event = control.event?.lowercase()
    return when (event) {
        ControlEvents.NEW -> {
            val channel = control.chatType.equals("CHANNEL", ignoreCase = true)
            if (control.chatType.equals("DIALOG", ignoreCase = true)) {
                return when {
                    mine -> "Вы теперь в MAX"
                    actorName != null -> "$actorName теперь в MAX"
                    else -> "Контакт теперь в MAX"
                }
            }
            val what = if (channel) "канал" else "чат"
            val obj = title?.let { "$what «$it»" } ?: what
            subj("создал(а)", "создали")?.let { "$it $obj" }
                ?: title?.let { if (channel) "Создан канал «$it»" else "Создан чат «$it»" }
                ?: if (channel) "Канал создан" else "Чат создан"
        }
        ControlEvents.ADD.lowercase(), ControlEvents.REMOVE.lowercase() -> {
            val add = event == ControlEvents.ADD
            if (actor != null && targets == listOf(actor)) {
                return if (add) subj("вступил(а) в чат", "вступили в чат") ?: "Участник вступил(а) в чат"
                else subj("покинул(а) чат", "покинули чат") ?: "Участник покинул(а) чат"
            }
            val s = if (add) subj("добавил(а)", "добавили") else subj("удалил(а)", "удалили")
            if (s == null) {
                if (add) "Добавлены участники" else "Удалены участники"
            } else {
                "$s ${membersPhrase(targets, selfId, ::name)}"
            }
        }
        ControlEvents.LEAVE -> subj("покинул(а) чат", "покинули чат") ?: "Участник покинул(а) чат"
        ControlEvents.TITLE -> {
            val s = subj("изменил(а) название", "изменили название")
            when {
                s != null -> title?.let { "$s на «$it»" } ?: "$s чата"
                title != null -> "Название чата изменено на «$title»"
                else -> "Название чата изменено"
            }
        }
        ControlEvents.ICON -> subj("изменил(а) фото чата", "изменили фото чата") ?: "Фото чата изменено"
        ControlEvents.JOIN_BY_LINK.lowercase() ->
            subj("вступил(а) в чат по ссылке", "вступили в чат по ссылке") ?: "Участник вступил(а) в чат по ссылке"
        ControlEvents.HELLO -> {
            val who = control.userId?.takeIf { it != 0L } ?: actor
            when {
                who != null && who == selfId -> "Вы теперь в MAX"
                else -> who?.let(::name)?.let { "$it теперь в MAX" } ?: "Контакт теперь в MAX"
            }
        }
        ControlEvents.BOT_STARTED.lowercase() -> if (mine) "Вы запустили бота" else "Бот запущен"
        ControlEvents.PIN -> (pinPreview(control.pinnedText) ?: pinPreview(pinnedText))?.let { "Закреплено: $it" } ?: "Сообщение закреплено"
        ControlEvents.UNPIN -> "Закрепление снято"
        else -> null
    }
}

/** Longest [pinPreview] in characters, the «…» included. */
const val PIN_PREVIEW_MAX: Int = 50

/**
 * A one-line preview of a pinned message: the first non-blank line of [text], trimmed, cut to
 * [PIN_PREVIEW_MAX] characters with «…» (never inside a surrogate pair). `null` for blank text.
 */
fun pinPreview(text: String?): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    if (line.length <= PIN_PREVIEW_MAX) return line
    var end = PIN_PREVIEW_MAX - 1
    if (line[end - 1].isHighSurrogate()) end--
    return line.substring(0, end).trimEnd() + "…"
}

/** «вас», «Борис», «Борис и Вера», «Борис, Вера и Глеб», «Борис и ещё 2», «участника», «2 участника». */
private fun membersPhrase(ids: List<Long>, selfId: Long?, name: (Long) -> String?): String {
    val known = ArrayList<String>()
    var unknown = 0
    for (id in ids) {
        val n = if (id == selfId) "вас" else name(id)
        if (n != null) known += n else unknown++
    }
    return when {
        known.isEmpty() -> if (unknown <= 1) "участника" else "$unknown ${participantsWord(unknown)}"
        unknown > 0 -> "${known.joinToString(", ")} и ещё $unknown"
        known.size == 1 -> known[0]
        else -> known.dropLast(1).joinToString(", ") + " и " + known.last()
    }
}

private fun participantsWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return if (m10 in 1..4 && m100 !in 11..14) "участника" else "участников"
}
