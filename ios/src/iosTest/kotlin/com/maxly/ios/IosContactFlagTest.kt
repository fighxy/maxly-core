package com.maxly.ios

import com.maxly.core.api.MaxUser
import com.maxly.core.state.MaxState
import com.maxly.core.state.StateReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `isContact` / `customName` on profile and contact snapshots, read from the current state. */
class IosContactFlagTest {
    private val friend = MaxUser.from(
        mapOf(
            "id" to 2L,
            "names" to listOf(
                mapOf("type" to "ONEME", "name" to "Petr"),
                mapOf("type" to "CUSTOM", "firstName" to "Petya", "lastName" to "Work"),
            ),
        ),
    )!!
    private val stranger = MaxUser.from(mapOf("id" to 3L, "names" to listOf(mapOf("type" to "ONEME", "name" to "Anna"))))!!
    private val state = StateReducer.putUsers(StateReducer.putContacts(MaxState(me = 1L), listOf(friend)), listOf(stranger))

    @Test
    fun contactIsFlaggedWithItsCustomName() {
        assertTrue(isContactOf(2L, state))
        assertEquals("Petya Work", customNameOf(2L, friend, state))
        val c = contactSnapshot(friend, state)
        assertTrue(c.isContact)
        assertEquals("Petya Work", c.customName)
    }

    @Test
    fun nonContactIsNotFlagged() {
        assertFalse(isContactOf(3L, state))
        assertEquals("", customNameOf(3L, stranger, state))
        val c = contactSnapshot(stranger, state)
        assertFalse(c.isContact)
        assertEquals("", c.customName)
        assertFalse(IosProfile(kind = "user", chatId = "5").isContact)
        assertEquals("", IosProfile(kind = "user", chatId = "5").customName)
    }

    @Test
    fun removedContactIsNoLongerFlagged() {
        val after = StateReducer.removeContact(state, 2L)
        assertFalse(isContactOf(2L, after))
        assertEquals("", customNameOf(2L, friend, after))
        val c = contactSnapshot(after.users.getValue(2L), after)
        assertFalse(c.isContact)
        assertEquals("", c.customName)
    }
}
