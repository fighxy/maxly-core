package com.maxly.core.calls

/**
 * A call ringing for this account, as listed by `calls` of the `LOGIN` reply and of
 * `GET_INBOUND_CALLS` 103: `{calls: [{conversationId, callerId, chatId, type, turnServer, sdpOffer}]}`
 * (MAX Android 26.35.0; that client parses both but uses neither, see `docs/opcodes.md`).
 *
 * There is no `vcp`, so the call cannot be answered: only `NOTIF_CALL_START` 137 carries the ws2
 * token. An app shows it as "X is calling" and calls back with a new call (78).
 *
 * @property chatId the chat of a group call; `null` for a 1:1 call.
 */
data class InboundCall(
    val conversationId: String,
    val callerId: Long,
    val chatId: Long?,
    val isVideo: Boolean,
) {
    companion object {
        /** The `calls` list of a `LOGIN` or 103 reply map; entries without both ids are skipped. */
        fun listFrom(reply: Any?): List<InboundCall> {
            val calls = (reply as? Map<*, *>)?.get("calls") as? List<*> ?: return emptyList()
            return calls.mapNotNull { from(it) }.distinctBy { it.conversationId }
        }

        fun from(value: Any?): InboundCall? {
            val map = value as? Map<*, *> ?: return null
            val conversationId = (map["conversationId"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val callerId = (map["callerId"] as? Number)?.toLong() ?: (map["callerId"] as? String)?.toLongOrNull() ?: return null
            val chatId = ((map["chatId"] as? Number)?.toLong() ?: (map["chatId"] as? String)?.toLongOrNull())?.takeIf { it != 0L }
            return InboundCall(conversationId, callerId, chatId, isVideo = map["type"] == "VIDEO")
        }
    }
}
