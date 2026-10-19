package com.maxly.core.api

import com.maxly.core.media.ImageSizes
import com.maxly.core.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full-size avatars: `baseRawUrl` / `baseRawIconUrl`, the viewer URL list and `CONTACT_PHOTOS` 39. */
class AvatarUrlsTest {
    @Test
    fun viewerUrlsGoFromTheLargestRawSizeDown() {
        val urls = ImageSizes.viewerUrls("https://i/raw?e=1", "https://i/base?e=1")
        assertEquals(
            listOf(
                "https://i/raw?e=1&fn=w_1440",
                "https://i/raw?e=1&fn=sqr_720",
                "https://i/base?e=1&fn=w_1440",
                "https://i/base?e=1&fn=sqr_720",
                "https://i/raw?e=1&fn=w_1080",
            ),
            urls.take(5),
        )
        assertEquals(20, urls.size)
        assertEquals("https://i/base?e=1&fn=sqr_64", urls.last())
        assertTrue(urls.none { !it.contains("&fn=") })
    }

    @Test
    fun viewerUrlsSkipMissingAndRepeatedUrls() {
        assertEquals(emptyList(), ImageSizes.viewerUrls(null, " "))
        assertEquals(10, ImageSizes.viewerUrls(null, "https://i/b").size)
        assertEquals("https://i/b&fn=w_1440", ImageSizes.viewerUrls("", "https://i/b").first())
        assertEquals(10, ImageSizes.viewerUrls("https://i/a", "https://i/a").size)
    }

    @Test
    fun withoutFnDropsOnlyTheSize() {
        assertEquals("https://i/a", ImageSizes.withoutFn("https://i/a"))
        assertEquals("https://i/a", ImageSizes.withoutFn("https://i/a&fn=sqr_64"))
        assertEquals("https://i/a", ImageSizes.withoutFn("https://i/a?fn=sqr_64"))
        assertEquals("https://i/a?e=1", ImageSizes.withoutFn("https://i/a?fn=w_480&e=1"))
        assertEquals("https://i/a?e=1&s=2", ImageSizes.withoutFn("https://i/a?e=1&fn=w_480&s=2&fn=sqr_64"))
        assertEquals("https://i/a?e=1#x", ImageSizes.withoutFn("https://i/a?e=1&fn=w_480#x"))
        assertEquals("https://i/a?fnx=1", ImageSizes.withoutFn("https://i/a?fnx=1"))
        assertEquals("https://i/r", ImageSizes.originalUrl("https://i/r&fn=w_1440", "https://i/b"))
        assertEquals("https://i/b", ImageSizes.originalUrl(" ", "https://i/b?fn=sqr_720"))
        assertNull(ImageSizes.originalUrl(null, ""))
    }

    @Test
    fun userCarriesTheRawAvatar() {
        val user = MaxUser.from(mapOf("id" to 5L, "baseUrl" to "https://i/b", "baseRawUrl" to "https://i/r", "photoId" to 77L))!!
        assertEquals("https://i/r", user.baseRawUrl)
        assertEquals(77L, user.photoId)
        assertEquals("https://i/r", user.avatarFullUrl)
        assertEquals("https://i/b", user.avatarThumbUrl)
        assertEquals(ImageSizes.viewerUrls("https://i/r", "https://i/b"), user.avatarViewerUrls)

        val old = MaxUser.from(mapOf("id" to 6L, "baseUrl" to "https://i/b", "baseRawUrl" to ""))!!
        assertNull(old.baseRawUrl)
        assertEquals("https://i/b", old.avatarFullUrl)

        // a thumbnail URL that already asks for a size: the original drops it
        val sized = MaxUser.from(mapOf("id" to 8L, "baseUrl" to "https://i/b?e=1&fn=sqr_288&s=x"))!!
        assertEquals("https://i/b?e=1&s=x", sized.avatarFullUrl)
        assertEquals("https://i/b?e=1&fn=sqr_288&s=x", sized.avatarThumbUrl)
        assertEquals("https://i/b?e=1&s=x&fn=w_1440", sized.avatarViewerUrls.first())

        val none = MaxUser.from(mapOf("id" to 7L))!!
        assertNull(none.avatarFullUrl)
        assertNull(none.avatarThumbUrl)
        assertEquals(emptyList(), none.avatarViewerUrls)
    }

    @Test
    fun chatCarriesTheRawIcon() {
        val chat = Chat.from(mapOf("id" to 9L, "type" to "CHAT", "baseIconUrl" to "https://i/c", "baseRawIconUrl" to "https://i/cr"))!!
        assertEquals("https://i/c", chat.baseIconUrl)
        assertEquals("https://i/cr", chat.baseRawIconUrl)
        assertEquals("https://i/cr", chat.iconFullUrl)
        assertEquals("https://i/c", chat.iconThumbUrl)
        assertEquals("https://i/cr&fn=w_1440", chat.iconViewerUrls.first())
        val plain = Chat.from(mapOf("id" to 11L, "type" to "CHANNEL", "baseIconUrl" to "https://i/p?fn=sqr_96&e=2"))!!
        assertEquals("https://i/p?e=2", plain.iconFullUrl)
        val dialog = Chat.from(mapOf("id" to 10L, "type" to "DIALOG", "baseIconUrl" to " "))!!
        assertNull(dialog.baseIconUrl)
        assertNull(dialog.baseRawIconUrl)
        assertNull(dialog.iconFullUrl)
    }

    @Test
    fun contactPhotosPagesAsTheAppDoes() = runTest {
        val sink = ScriptSink(
            mapOf("ids" to listOf(3L, 2L), "urls" to listOf("https://i/3", "https://i/2"), "total" to 5),
            mapOf("ids" to listOf(1L), "urls" to listOf("https://i/1", "https://i/0"), "total" to 5),
            emptyMap<String, Any?>(),
        )
        val api = UsersApi(sink)
        val first = api.contactPhotos(42)
        assertEquals(listOf(ContactPhoto(3L, "https://i/3"), ContactPhoto(2L, "https://i/2")), first.photos)
        assertEquals(5, first.total)
        val payload = sink.sent[0].second as Map<*, *>
        assertEquals(listOf("contactId", "count"), payload.keys.toList())
        assertEquals(mapOf<String, Any?>("contactId" to 42L, "count" to 50), payload)

        // ids that do not line up with urls: the urls stay, without ids
        val second = api.contactPhotos(42, from = 2)
        assertEquals(listOf(ContactPhoto(null, "https://i/1"), ContactPhoto(null, "https://i/0")), second.photos)
        assertEquals(mapOf<String, Any?>("contactId" to 42L, "count" to 50, "from" to 2), sink.sent[1].second)

        val empty = api.contactPhotos(42, from = 4, count = 10)
        assertEquals(emptyList(), empty.photos)
        assertEquals(0, empty.total)
        assertEquals(List(3) { Opcode.CONTACT_PHOTOS }, sink.opcodes)

        assertFailsWith<IllegalArgumentException> { api.contactPhotos(42, from = -1) }
        assertFailsWith<IllegalArgumentException> { api.contactPhotos(42, count = 0) }
        assertFailsWith<MalformedReplyException> { UsersApi(ScriptSink(mapOf("urls" to "x"))).contactPhotos(1) }
    }
}
