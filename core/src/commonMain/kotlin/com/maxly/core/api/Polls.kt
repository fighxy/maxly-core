package com.maxly.core.api

/** Poll setting bits (PyMax `PollFlags`). */
enum class PollFlag(val bit: Int) {
    ANONYMOUS(1), MULTISELECT(2), REVOTE(4), CLOSED(8), QUIZ(16), CAN_FORWARD(32);

    companion object {
        fun mask(flags: Set<PollFlag>): Int = flags.fold(0) { acc, f -> acc or f.bit }

        fun of(mask: Int): Set<PollFlag> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
    }
}

/** A poll answer (PyMax `PollAnswer`: `text`, optional `answerId`). */
data class PollAnswer(val text: String, val answerId: Long? = null)

/** One vote (PyMax `PollVote`). */
data class PollVote(val userId: Long, val timestamp: Long)

/**
 * Result for one answer (PyMax `PollResult`). [options] is the server's per-answer bitmask; bit
 * [OPTION_MINE] marks the answer the current user chose (the Android app shows the results and a
 * check on that answer when it is set).
 */
data class PollResult(val answerId: Long, val voteCount: Int, val votes: List<PollVote>, val rate: Int, val options: Int) {
    /** The current user voted for this answer ([options] bit [OPTION_MINE]). */
    val isMine: Boolean get() = options and OPTION_MINE != 0

    companion object {
        /** [options] bit: the answer was chosen by the current user. */
        const val OPTION_MINE: Int = 1
    }
}

/** Current poll state (PyMax `PollState`: `total`, `result?`, `voterPreviewIds`). */
data class PollState(val total: Int, val results: List<PollResult>, val voterPreviewIds: List<Long>, val raw: Map<*, *>) {
    /** Answers the current user voted for ([PollResult.isMine]); empty when the user has not voted. */
    val myAnswerIds: List<Long> get() = results.filter { it.isMine }.map { it.answerId }

    /** The current user has voted in this poll. */
    val hasMyVote: Boolean get() = results.any { it.isMine }

    /**
     * This state with [answerIds] marked as the user's ([PollResult.OPTION_MINE]), for a vote that
     * just succeeded when the reply did not mark any answer itself. Answers without a result entry
     * get one with zero counts.
     */
    fun withMyVote(answerIds: Collection<Long>): PollState {
        if (hasMyVote || answerIds.isEmpty()) return this
        val known = results.map { it.answerId }.toSet()
        val marked = results.map { if (it.answerId in answerIds) it.copy(options = it.options or PollResult.OPTION_MINE) else it }
        val added = answerIds.filter { it !in known }.distinct().map { PollResult(it, 0, emptyList(), 0, PollResult.OPTION_MINE) }
        return copy(results = marked + added)
    }

    companion object {
        fun from(value: Any?): PollState? {
            val m = value as? Map<*, *> ?: return null
            val results = (m["result"] as? List<*>).orEmpty().mapNotNull { r ->
                val rm = r as? Map<*, *> ?: return@mapNotNull null
                PollResult(
                    answerId = rm["answerId"].asLong() ?: return@mapNotNull null,
                    voteCount = rm["voteCount"].asLong()?.toInt() ?: 0,
                    votes = (rm["votes"] as? List<*>).orEmpty().mapNotNull { v ->
                        val vm = v as? Map<*, *> ?: return@mapNotNull null
                        PollVote(vm["userId"].asLong() ?: return@mapNotNull null, vm["timestamp"].asLong() ?: 0)
                    },
                    rate = rm["rate"].asLong()?.toInt() ?: 0,
                    options = rm["options"].asLong()?.toInt() ?: 0,
                )
            }
            return PollState(m["total"].asLong()?.toInt() ?: 0, results, (m["voterPreviewIds"] as? List<*>).orEmpty().mapNotNull { it.asLong() }, m)
        }
    }
}

/** Comment counter update of a channel post (PyMax `CommentsInfoUpdate`). */
data class CommentsInfo(val postId: Long, val totalCount: Int?, val raw: Map<*, *>) {
    companion object {
        fun from(value: Any?): CommentsInfo? {
            val m = value as? Map<*, *> ?: return null
            val info = m["commentsInfo"] as? Map<*, *>
            return CommentsInfo(m["postId"].asLong() ?: return null, info?.let { it["totalCount"].asLong()?.toInt() ?: 0 }, m)
        }
    }
}

/**
 * Delayed sending (PyMax `DelayedAttributes`): the server posts the message at [timeToFire]
 * (epoch milliseconds); [notifySender] as PyMax (it passes the `notify` flag).
 */
data class DelayedSend(val timeToFire: Long, val notifySender: Boolean = true) {
    fun toPayload(): Map<String, Any?> = linkedMapOf("timeToFire" to timeToFire, "notifySender" to notifySender)
}

/**
 * One poll from `GET_POLL_UPDATES` 306. The reply item has no `_type`; keys are the app's
 * `pollId`, `title`, `answers` (`text`, `answerId`), `settings`, `version`, `state`.
 */
internal fun pollUpdate(value: Any?): com.maxly.core.media.Attachment.Poll? {
    val m = value as? Map<*, *> ?: return null
    val id = m["pollId"].asLong() ?: return null
    val answers = (m["answers"] as? List<*>).orEmpty().mapNotNull { a ->
        val am = a as? Map<*, *> ?: return@mapNotNull null
        PollAnswer(am["text"] as? String ?: return@mapNotNull null, am["answerId"].asLong())
    }
    return com.maxly.core.media.Attachment.Poll(
        pollId = id,
        version = m["version"].asLong()?.toInt(),
        title = m["title"] as? String,
        answers = answers,
        flags = PollFlag.of(m["settings"].asLong()?.toInt() ?: 0),
        state = PollState.from(m["state"]),
        raw = m,
    )
}
