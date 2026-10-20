package com.maxly.core.api

import com.maxly.core.auth.RequestSink
import com.maxly.core.protocol.Opcode

/**
 * Users and contacts over a [RequestSink], following PyMax `src/pymax/api/users/service.py`
 * (`UserService`) and `payloads.py`. PyMax's user cache is not reproduced (the caller can keep
 * results in `com.maxly.core.state.MaxStore.putUsers`).
 *
 * Contact search by name has no payload in the references and is not exposed; of the contact
 * list opcodes (35-40) `CONTACT_PRESENCE` 35 ([getPresence]), the black list page of
 * `CONTACT_LIST` 36 and the photo pages of `CONTACT_PHOTOS` 39 ([contactPhotos]) are. `CONTACT_UPDATE` 34 covers add, rename (`UPDATE`), remove, block and
 * unblock.
 */
class UsersApi(private val sink: RequestSink) {
    /** Users by id (`CONTACT_INFO` 32, `{contactIds}`); reply `contacts`. Unknown ids are absent. */
    suspend fun getUsers(userIds: List<Long>): List<MaxUser> {
        require(userIds.isNotEmpty()) { "userIds must not be empty" }
        return userList(Opcode.CONTACT_INFO, linkedMapOf("contactIds" to userIds))
    }

    /**
     * Presence of users (`CONTACT_PRESENCE` 35, `{contactIds}` → `{presence: {userId: {seen,
     * status}}}`; MAX web client and KometTeam/Komet `PresenceFetch`). Ids go out in batches of
     * at most [PRESENCE_BATCH] (Komet's limit), one request after another; duplicates are dropped.
     * An asked id missing from the reply is [PresenceStatus.LONG_AGO] without a time, as the web
     * client reads it ([Presences.fromContactPresence]). `seen` is Unix seconds.
     */
    suspend fun getPresence(userIds: List<Long>): Map<Long, PresenceInfo> {
        val ids = userIds.distinct()
        require(ids.isNotEmpty()) { "userIds must not be empty" }
        val out = LinkedHashMap<Long, PresenceInfo>()
        for (chunk in ids.chunked(PRESENCE_BATCH)) {
            val reply = rawMap(sink.request(Opcode.CONTACT_PRESENCE, linkedMapOf("contactIds" to chunk)))
            out.putAll(Presences.fromContactPresence(chunk, reply))
        }
        return out
    }

    /**
     * The raw `CONTACT_PRESENCE` 35 reply for [userIds] (one request, at most [PRESENCE_BATCH]
     * ids): `{presence: {"<id>": {seen?, status?}}}`, nothing filled in for an id the server
     * left out (unlike [getPresence]).
     */
    suspend fun getPresenceReply(userIds: List<Long>): Map<*, *> {
        val ids = userIds.distinct()
        require(ids.isNotEmpty() && ids.size <= PRESENCE_BATCH) { "1..$PRESENCE_BATCH user ids" }
        return rawMap(sink.request(Opcode.CONTACT_PRESENCE, linkedMapOf("contactIds" to ids)))
    }

    /** One user via [getUsers], or `null` if the server did not return it (PyMax `get_user`). */
    suspend fun getUser(userId: Long): MaxUser? = getUsers(listOf(userId)).firstOrNull { it.id == userId }

    /** Finds a user by phone (`CONTACT_INFO_BY_PHONE` 46, `{phone}`); reply `contact`. */
    suspend fun findByPhone(phone: String): MaxUser =
        contact(Opcode.CONTACT_INFO_BY_PHONE, sink.request(Opcode.CONTACT_INFO_BY_PHONE, linkedMapOf("phone" to phone)).payload)

    /**
     * Adds a contact (`CONTACT_UPDATE` 34, `{contactId, action: "ADD"}`); reply `contact`.
     * A non-blank [firstName] is sent as well (Komet `addContact`). An empty name is left out,
     * so [addContact] without it keeps the previous payload.
     */
    suspend fun addContact(userId: Long, firstName: String? = null): MaxUser {
        val payload = linkedMapOf<String, Any?>("contactId" to userId, "action" to "ADD")
        val name = firstName?.trim().orEmpty()
        if (name.isNotEmpty()) payload["firstName"] = name
        return contact(Opcode.CONTACT_UPDATE, sink.request(Opcode.CONTACT_UPDATE, payload).payload)
    }

    /**
     * Renames a contact for this account (`CONTACT_UPDATE` 34, `{contactId, action: "UPDATE",
     * firstName, lastName}`, as the MAX web client and KometTeam/Komet `updateContact` send it);
     * reply `contact`. The new name is the user's `CUSTOM` entry of `names`, seen only by this
     * account. Both names are trimmed; a blank [lastName] goes out as `null` (web client). An
     * empty [firstName] is allowed and sent as `""`, as the web client does: with a last name the
     * server shows the person's own first name, with both empty the original names come back.
     * A name over [CONTACT_NAME_MAX] characters fails before sending (the server answers
     * `error.contact.name.maxlength`). The returned user carries the trimmed name as its `CUSTOM`
     * entry (none when both are blank), also when the reply lacks the contact (then only the id
     * is known). The web client adds a user who is not a contact yet
     * (`ADD`, [addContact]) before renaming.
     */
    suspend fun renameContact(userId: Long, firstName: String, lastName: String? = null): MaxUser {
        val first = firstName.trim()
        val last = lastName?.trim()?.takeIf { it.isNotEmpty() }
        require(first.length <= CONTACT_NAME_MAX && (last?.length ?: 0) <= CONTACT_NAME_MAX) { "name longer than $CONTACT_NAME_MAX" }
        val payload = linkedMapOf<String, Any?>("contactId" to userId, "action" to "UPDATE", "firstName" to first, "lastName" to last)
        val reply = sink.request(Opcode.CONTACT_UPDATE, payload).payload as? Map<*, *>
            ?: throw MalformedReplyException(Opcode.CONTACT_UPDATE, "payload is not a map", null)
        val contact = MaxUser.from(reply["contact"])?.takeIf { it.id == userId }
        val cleared = first.isEmpty() && last == null
        // The server's own name wins; without one (or on a clear) the trimmed values sent are applied locally.
        if (contact != null && !cleared && ContactNames.customName(contact) != null) return contact
        return ContactNames.withCustom(contact ?: MaxUser.from(mapOf("id" to userId))!!, first, last)
    }

    /**
     * Removes a contact (`CONTACT_UPDATE` 34, `{contactId, action: "REMOVE"}`). Returns the
     * reply's `contact` when there is one (the web client reads it); undo is [addContact].
     */
    suspend fun removeContact(userId: Long): MaxUser? {
        val map = replyMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, "REMOVE")), Opcode.CONTACT_UPDATE)
        return MaxUser.from(map["contact"])
    }

    /**
     * Adds a contact by phone (`CONTACT_ADD_BY_PHONE` 41, `{phone, firstName?, lastName?}`, MAX
     * web client); reply `{contact, new}`. Blank names are left out. [ContactByPhone.isNew] is the
     * reply's `new` (the contact was not in the list before).
     */
    suspend fun addContactByPhone(phone: String, firstName: String? = null, lastName: String? = null): ContactByPhone {
        val number = phone.trim()
        require(number.isNotEmpty()) { "phone must not be blank" }
        val payload = linkedMapOf<String, Any?>("phone" to number)
        firstName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["firstName"] = it }
        lastName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["lastName"] = it }
        val reply = sink.request(Opcode.CONTACT_ADD_BY_PHONE, payload).payload
        val user = contact(Opcode.CONTACT_ADD_BY_PHONE, reply)
        return ContactByPhone(user, (reply as Map<*, *>)["new"] == true)
    }

    /**
     * Imports phone-book entries (`SYNC` 21, PyMax `ImportContactsPayload`:
     * `{contactList: {<phone>: {firstName}}}`; PyMax sends only the first name). Reply `contacts`:
     * the entries that are Max users.
     */
    suspend fun importContacts(contacts: List<PhoneContact>): List<MaxUser> {
        val list = LinkedHashMap<String, Any?>()
        for (c in contacts) list[c.phone] = linkedMapOf("firstName" to c.firstName)
        return userList(Opcode.SYNC, linkedMapOf("contactList" to list))
    }

    /**
     * One page of blocked users (`CONTACT_LIST` 36, `{status: "BLOCKED", count, from}`, as Komet
     * pages the black list); reply `contacts`. An empty page is the end.
     */
    suspend fun blockedContacts(from: Int = 0, count: Int = 100): List<MaxUser> {
        require(from >= 0 && count > 0) { "bad page: from=$from count=$count" }
        return userList(Opcode.CONTACT_LIST, linkedMapOf("status" to "BLOCKED", "count" to count, "from" to from))
    }

    /** Blocks or unblocks [userId] (`CONTACT_UPDATE` 34, `{contactId, action: "BLOCK" | "UNBLOCK"}`). */
    suspend fun setBlocked(userId: Long, blocked: Boolean) {
        rawMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, if (blocked) "BLOCK" else "UNBLOCK")))
    }

    /**
     * The whole contact list (opcode 8, `CONTACTS_GET` in Komet, `{contactsSync: 0}`); reply
     * `contacts` (PyMax's `LOGIN2` name `contactInfos` is read as well). Komet sends it when the
     * `LOGIN` reply had no contacts.
     */
    suspend fun syncContacts(): List<MaxUser> {
        val map = replyMap(sink.request(Opcode.CONTACTS_GET, linkedMapOf("contactsSync" to 0)), Opcode.CONTACTS_GET)
        val items = map["contacts"] ?: map["contactInfos"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(Opcode.CONTACTS_GET, "contacts is not a list", map)
        return list.mapNotNull { MaxUser.from(it) }
    }

    /**
     * A page of [contactId]'s profile photos (`CONTACT_PHOTOS` 39, `{contactId, count, from?}` →
     * `{ids, urls, total}`, as the Android app pages it: `count` 50, `from` left out on the first
     * page). [from] is the number of photos already loaded. `urls[i]` is the photo of `ids[i]`;
     * when the two lists differ in length the URLs come without ids ([ContactPhoto.photoId]
     * `null`), as the app reads them. [ContactPhotos.total] is the server's count of all photos.
     */
    suspend fun contactPhotos(contactId: Long, from: Int = 0, count: Int = CONTACT_PHOTOS_PAGE): ContactPhotos {
        require(from >= 0 && count > 0) { "bad page: from=$from count=$count" }
        val payload = linkedMapOf<String, Any?>("contactId" to contactId, "count" to count)
        if (from > 0) payload["from"] = from
        return ContactPhotos.from(replyMap(sink.request(Opcode.CONTACT_PHOTOS, payload), Opcode.CONTACT_PHOTOS))
    }

    /** Active sessions of the account (`SESSIONS_INFO` 96, `{}`); reply `sessions`. */
    suspend fun getSessions(): List<SessionInfo> {
        val map = replyMap(sink.request(Opcode.SESSIONS_INFO, emptyMap<String, Any?>()), Opcode.SESSIONS_INFO)
        val items = map["sessions"] as? List<*> ?: return emptyList()
        return items.map { SessionInfo.from(it) ?: throw MalformedReplyException(Opcode.SESSIONS_INFO, "session is not a map", map) }
    }

    private suspend fun userList(opcode: Opcode, payload: Map<String, Any?>): List<MaxUser> {
        val map = replyMap(sink.request(opcode, payload), opcode)
        val items = map["contacts"] ?: return emptyList()
        val list = items as? List<*> ?: throw MalformedReplyException(opcode, "contacts is not a list", map)
        return list.map { MaxUser.from(it) ?: throw MalformedReplyException(opcode, "invalid user in contacts", map) }
    }

    private fun contact(opcode: Opcode, payload: Any?): MaxUser {
        val map = payload as? Map<*, *> ?: throw MalformedReplyException(opcode, "payload is not a map", payload)
        return MaxUser.from(map["contact"]) ?: throw MalformedReplyException(opcode, "no valid contact", map)
    }

    private fun contactAction(userId: Long, action: String): Map<String, Any?> = linkedMapOf("contactId" to userId, "action" to action)

    /**
     * Hides or shows the stories of person [userId] for this account (`CONTACT_UPDATE` 34,
     * `{contactId, action: "HIDE_STORIES" | "SHOW_STORIES"}`, as the MAX web client sends it).
     * Returns the reply's `contact` when there is one.
     */
    suspend fun setStoriesHidden(userId: Long, hidden: Boolean): MaxUser? {
        val map = replyMap(sink.request(Opcode.CONTACT_UPDATE, contactAction(userId, if (hidden) "HIDE_STORIES" else "SHOW_STORIES")), Opcode.CONTACT_UPDATE)
        return MaxUser.from(map["contact"])
    }

    companion object {
        /** Longest first or last name of a contact the server accepts (MAX web client form). */
        const val CONTACT_NAME_MAX = 64

        /** Page size of [contactPhotos], the Android app's `count`. */
        const val CONTACT_PHOTOS_PAGE = 50

        /** Most ids per `CONTACT_PRESENCE` 35 request ([getPresence]; KometTeam/Komet `PresenceFetch`). */
        const val PRESENCE_BATCH = 100

        /** Id of the dialog between two users (PyMax `get_chat_id`: `a xor b`). */
        fun dialogChatId(firstUserId: Long, secondUserId: Long): Long = firstUserId xor secondUserId
    }
}

/**
 * A phone-book entry: for [UsersApi.importContacts] (PyMax `ContactInfo`; [lastName] is not sent)
 * and for the on-device address book (`com.maxly.core.state.StateReducer.setAddressBook`, never
 * sent), where [fullName] is the name.
 */
data class PhoneContact(val phone: String, val firstName: String, val lastName: String? = null) {
    /** `firstName lastName`, trimmed; `null` when both are blank. */
    val fullName: String?
        get() = listOfNotNull(firstName.trim(), lastName?.trim()).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
}

/**
 * One photo of a [ContactPhotos] page: [url] as the server sent it (the app shows it as is) and
 * its [photoId], `null` when the reply's `ids` did not line up with `urls`.
 */
data class ContactPhoto(val photoId: Long?, val url: String)

/** Reply of [UsersApi.contactPhotos]: the page's [photos] and the server's [total] (0 when absent). */
data class ContactPhotos(val photos: List<ContactPhoto>, val total: Int, val raw: Map<*, *>) {
    companion object {
        /** Parses a `CONTACT_PHOTOS` 39 reply; missing lists give an empty page. */
        fun from(map: Map<*, *>): ContactPhotos {
            val urls = map["urls"]?.let {
                it as? List<*> ?: throw MalformedReplyException(Opcode.CONTACT_PHOTOS, "urls is not a list", map)
            }.orEmpty().map {
                it as? String ?: throw MalformedReplyException(Opcode.CONTACT_PHOTOS, "url is not a string", map)
            }
            val ids = (map["ids"] as? List<*>).orEmpty().map { it.asLong() }
            val paired = ids.size == urls.size && ids.none { it == null }
            val photos = urls.mapIndexedNotNull { i, url ->
                url.takeIf { it.isNotBlank() }?.let { ContactPhoto(if (paired) ids[i] else null, it) }
            }
            return ContactPhotos(photos, map["total"].asLong()?.toInt() ?: 0, map)
        }
    }
}

/** Reply of [UsersApi.addContactByPhone]: the contact and whether it was [isNew] to the list. */
data class ContactByPhone(val user: MaxUser, val isNew: Boolean)

/**
 * Phone numbers for matching the device address book against users, by the rules the Maxly
 * iOS and Android clients share (fixtures `test-fixtures/names/`):
 *
 * 1. spaces (any whitespace, also no-break), dashes (`-` and every Unicode dash: non-breaking
 *    hyphen, en and em dash...), parentheses, dots and slashes are dropped; a leading `+` is kept;
 * 2. a leading `00` counts as `+`;
 * 3. without `+`: 11 digits starting with `8` become `+7…` (8 913 123-45-67 → +79131234567),
 *    11 digits starting with `7` get the `+`;
 * 4. without `+`: exactly 10 digits are Russian, `+7` is put in front;
 * 5. any other number keeps (or gets) its `+`: the result always starts with `+`;
 * 6. fewer than 7 or more than 15 digits, or any other character, is no number (`null`), so
 *    short service numbers such as 900 never match.
 *
 * The server's `phone` of a user (digits as a number, e.g. `79131234567`) normalizes the same way.
 */
object PhoneNumbers {
    /** A character the rules drop (rule 1). */
    private fun Char.isSeparator(): Boolean =
        isWhitespace() || this == '\u00A0' || this == '\u202F' || this == '\u2007' ||
            this == '-' || category == CharCategory.DASH_PUNCTUATION || this == '\u2212' ||
            this == '(' || this == ')' || this == '.' || this == '/'

    fun normalize(value: Any?): String? {
        val raw = when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.toString()
            is String -> value
            else -> null
        } ?: return null
        var t = raw.filterNot { it.isSeparator() }
        var plus = false
        if (t.startsWith("+")) {
            plus = true
            t = t.substring(1)
        } else if (t.startsWith("00")) {
            plus = true
            t = t.substring(2)
        }
        if (t.isEmpty() || t.any { it !in '0'..'9' } || t.length < 7 || t.length > 15) return null
        if (!plus) {
            t = when {
                t.length == 11 && t[0] == '8' -> "7" + t.substring(1)
                t.length == 10 -> "7$t"
                else -> t
            }
        }
        return "+$t"
    }
}

/**
 * An active session of the account (PyMax `Session`, every field optional). The server Komet talks
 * to sends `client` (app and platform), `info` (device), `location` and `time` (last activity);
 * [lastSeen] picks whichever time is present.
 */
data class SessionInfo(
    val id: String?,
    val deviceId: String?,
    val current: Boolean,
    val userAgent: String?,
    val appVersion: String?,
    val deviceName: String?,
    val deviceType: String?,
    val platform: String?,
    val ip: String?,
    val location: String?,
    val created: Long?,
    val updated: Long?,
    val lastActivity: Long?,
    val raw: Map<*, *>,
    val client: String? = null,
    val info: String? = null,
    val time: Long? = null,
) {
    /** Last activity: `time`, else `lastActivity`, else `updated`; `null` when none is sent. */
    val lastSeen: Long? get() = time ?: lastActivity ?: updated

    companion object {
        fun from(value: Any?): SessionInfo? {
            val m = value as? Map<*, *> ?: return null
            return SessionInfo(
                id = m["id"]?.let { it as? String ?: it.asLong()?.toString() },
                deviceId = m["deviceId"] as? String,
                current = m["current"] == true,
                userAgent = m["userAgent"] as? String,
                appVersion = m["appVersion"] as? String,
                deviceName = m["deviceName"] as? String,
                deviceType = m["deviceType"] as? String,
                platform = m["platform"] as? String,
                ip = m["ip"] as? String,
                location = m["location"] as? String,
                created = m["created"].asLong(),
                updated = m["updated"].asLong(),
                lastActivity = m["lastActivity"].asLong(),
                raw = m,
                client = m["client"] as? String,
                info = m["info"] as? String,
                time = m["time"].asLong(),
            )
        }
    }
}
