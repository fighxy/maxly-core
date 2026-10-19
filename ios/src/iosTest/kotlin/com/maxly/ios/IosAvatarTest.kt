package com.maxly.ios

import com.maxly.core.api.Chat
import com.maxly.core.api.ContactPhotos
import com.maxly.core.api.MaxUser
import com.maxly.core.state.MaxState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Full-size avatars in the bridge: chats, dialogs, profiles, contacts, the own profile and `CONTACT_PHOTOS` pages. */
class IosAvatarTest {
    private val peer = MaxUser.from(mapOf("id" to 2L, "baseUrl" to "https://i/u", "baseRawUrl" to "https://i/ur", "photoId" to 77L))!!

    @Test
    fun dialogTakesThePeersRawAvatar() {
        val dialog = Chat.from(mapOf("id" to 3L, "type" to "DIALOG", "participants" to mapOf("1" to 0L, "2" to 0L)))!!
        val row = chatSnapshot(dialog, MaxState(me = 1L, users = mapOf(2L to peer)))
        assertEquals("https://i/u", row.avatarUrl)
        assertEquals("https://i/ur", row.avatarFullUrl)
        assertEquals(peer.avatarViewerUrls, row.avatarViewerUrls)
    }

    @Test
    fun groupTakesItsRawIcon() {
        val group = Chat.from(mapOf("id" to 4L, "type" to "CHAT", "title" to "G", "baseIconUrl" to "https://i/g", "baseRawIconUrl" to "https://i/gr"))!!
        assertEquals("https://i/gr", chatSnapshot(group, MaxState(me = 1L)).avatarFullUrl)
        val card = chatProfile(group)
        assertEquals("https://i/g", card.avatarUrl)
        assertEquals("https://i/gr", card.avatarFullUrl)
        assertEquals("https://i/gr&fn=w_1440", card.avatarViewerUrls.first())
        assertEquals("", card.photoId)
        val bare = Chat.from(mapOf("id" to 5L, "type" to "CHAT"))!!
        assertEquals("", chatSnapshot(bare, MaxState(me = 1L)).avatarFullUrl)
        assertEquals(emptyList(), chatProfile(bare).avatarViewerUrls)
    }

    @Test
    fun contactCarriesPhotoIdAndFullUrl() {
        val c = contactSnapshot(peer, MaxState(me = 1L, users = mapOf(2L to peer)))
        assertEquals("77", c.photoId)
        assertEquals("https://i/ur", c.avatarFullUrl)
        assertEquals("https://i/u", c.avatarUrl)
        assertEquals("https://i/u", IosImageSize.withoutFn("https://i/u&fn=sqr_64"))
        assertEquals("https://i/u", IosImageSize.originalUrl("", "https://i/u?fn=sqr_64"))
        assertEquals(listOf("https://i/ur&fn=w_1440", "https://i/ur&fn=sqr_720"), IosImageSize.viewerUrls("https://i/ur", "").take(2))
    }

    @Test
    fun contactPhotosKeepIdsAndUrls() {
        val page = contactPhotosSnapshot(ContactPhotos.from(mapOf("ids" to listOf(9L), "urls" to listOf("https://i/9"), "total" to 3)))
        assertEquals(3, page.total)
        assertEquals("9", page.photos.single().photoId)
        assertEquals("https://i/9", page.photos.single().url)
        assertEquals("", contactPhotosSnapshot(ContactPhotos.from(mapOf("urls" to listOf("https://i/x")))).photos.single().photoId)
    }

    @Test
    fun myProfileCarriesFullUrlViewerUrlsAndPhotoId() {
        val me = MaxUser.from(mapOf("id" to 1L, "baseUrl" to "https://i/m?fn=sqr_64", "baseRawUrl" to "https://i/mr?a=1&fn=sqr_288", "photoId" to 55L))!!
        val p = myProfileSnapshot(me, null)
        assertEquals("https://i/m?fn=sqr_64", p.avatarUrl)
        assertEquals("https://i/mr?a=1", p.avatarFullUrl)
        assertEquals(me.avatarFullUrl, p.avatarFullUrl)
        assertEquals(me.avatarViewerUrls, p.avatarViewerUrls)
        assertEquals(contactSnapshot(me, MaxState(me = 1L, users = mapOf(1L to me))).avatarViewerUrls, p.avatarViewerUrls)
        assertEquals("https://i/mr?a=1&fn=w_1440", p.avatarViewerUrls.first())
        assertEquals("55", p.photoId)
    }

    @Test
    fun myProfileWithoutAvatarIsEmpty() {
        val p = myProfileSnapshot(MaxUser.from(mapOf("id" to 1L))!!, null)
        assertEquals("", p.avatarUrl)
        assertEquals("", p.avatarFullUrl)
        assertEquals(emptyList(), p.avatarViewerUrls)
        assertEquals("", p.photoId)
    }
}
