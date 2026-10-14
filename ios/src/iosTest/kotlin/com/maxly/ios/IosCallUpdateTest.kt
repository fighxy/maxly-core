package com.maxly.ios

import com.maxly.core.events.MaxEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosCallUpdateTest {
    @Test
    fun callPushesBecomeCallUpdates() {
        val accepted = callUpdateSnapshot(MaxEvent.CallAcceptedElsewhere("c1", 120, null))!!
        assertEquals(IosCallUpdate.ACCEPTED_ELSEWHERE, accepted.kind)
        assertEquals("c1", accepted.conversationId)
        val ended = callUpdateSnapshot(MaxEvent.CallEnded("c2", 121, null))!!
        assertEquals("ended", ended.kind)
        assertEquals("c2", ended.conversationId)
        assertNull(callUpdateSnapshot(MaxEvent.Unknown(1, 0, null)))
    }
}
