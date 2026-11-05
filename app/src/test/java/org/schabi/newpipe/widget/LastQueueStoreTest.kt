/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.SinglePlayQueue

class LastQueueStoreTest {
    private fun savedItem(
        serviceId: Int = 0,
        url: String = "https://example.com/watch?v=abc",
        title: String = "Some title",
        uploader: String = "Some uploader",
        uploaderUrl: String? = "https://example.com/channel/xyz",
        duration: Long = 3723,
        thumbnailUrl: String? = "https://example.com/thumb.jpg",
        streamType: String = StreamType.VIDEO_STREAM.name
    ) = SavedQueueItem(
        serviceId = serviceId,
        url = url,
        title = title,
        uploader = uploader,
        uploaderUrl = uploaderUrl,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        streamType = streamType
    )

    private fun streamItem(
        serviceId: Int = 1,
        url: String = "https://example.com/watch?v=abc",
        title: String = "Some title",
        streamType: StreamType = StreamType.VIDEO_STREAM,
        uploader: String = "Some uploader",
        uploaderUrl: String? = "https://example.com/channel/xyz",
        duration: Long = 3723,
        thumbnailUrl: String? = "https://example.com/thumb.jpg"
    ): StreamInfoItem {
        val item = StreamInfoItem(serviceId, url, title, streamType)
        item.uploaderName = uploader
        item.uploaderUrl = uploaderUrl
        item.duration = duration
        item.thumbnails = if (thumbnailUrl != null) {
            listOf(Image(thumbnailUrl, 90, 120, Image.ResolutionLevel.LOW))
        } else {
            emptyList()
        }
        return item
    }

    @Test fun `encode decode round trip`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(),
                savedItem(
                    url = "https://example.com/watch?v=def",
                    uploaderUrl = null,
                    duration = 0,
                    thumbnailUrl = null,
                    streamType = StreamType.AUDIO_STREAM.name
                )
            ),
            index = 1,
            savedAtMillis = 1720000000000L
        )

        assertEquals(saved, LastQueueStore.decode(LastQueueStore.encode(saved)))
    }

    @Test fun `decode garbage returns null`() {
        listOf("", "not json", "{", "[]", "42", "{}", "{\"items\":[] }").forEach { garbage ->
            assertNull("expected null for <$garbage>", LastQueueStore.decode(garbage))
        }
    }

    @Test fun `decode tolerates unknown keys`() {
        val content = """
            {"unknownTopLevel":true,"items":[
              {"serviceId":2,"url":"https://example.com/v","title":"t","uploader":"u",
               "duration":10,"streamType":"AUDIO_STREAM","futureField":{"nested":1}}
            ],"index":0,"savedAtMillis":5,"anotherUnknown":[1,2]}
        """.trimIndent()

        val decoded = LastQueueStore.decode(content)
        assertEquals(
            SavedQueue(
                items = listOf(
                    SavedQueueItem(
                        serviceId = 2,
                        url = "https://example.com/v",
                        title = "t",
                        uploader = "u",
                        duration = 10,
                        streamType = "AUDIO_STREAM"
                    )
                ),
                index = 0,
                savedAtMillis = 5
            ),
            decoded
        )
    }

    @Test fun `decode fills in defaults for missing fields`() {
        val decoded = LastQueueStore.decode("""{"items":[{"url":"u"}]}""")

        assertEquals(
            SavedQueue(items = listOf(SavedQueueItem(url = "u"))),
            decoded
        )
        val item = decoded!!.items[0]
        assertEquals(0, item.serviceId)
        assertEquals("", item.title)
        assertEquals("", item.uploader)
        assertNull(item.uploaderUrl)
        assertEquals(-1L, item.duration)
        assertNull(item.thumbnailUrl)
        assertEquals(StreamType.VIDEO_STREAM.name, item.streamType)
        assertEquals(0, decoded.index)
        assertEquals(0L, decoded.savedAtMillis)
    }

    @Test fun `matchesQueue is true for same service ids and urls in order`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(serviceId = 1, url = "https://example.com/a"),
                savedItem(serviceId = 2, url = "https://example.com/b")
            ),
            index = 0,
            savedAtMillis = 0L
        )
        val queue = SinglePlayQueue(
            listOf(
                streamItem(serviceId = 1, url = "https://example.com/a"),
                streamItem(serviceId = 2, url = "https://example.com/b")
            ),
            0
        )

        assertTrue(LastQueueStore.matchesQueue(saved, queue))
    }

    @Test fun `matchesQueue is false for different size`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(serviceId = 1, url = "https://example.com/a"),
                savedItem(serviceId = 2, url = "https://example.com/b")
            ),
            index = 0,
            savedAtMillis = 0L
        )
        val queue = SinglePlayQueue(
            listOf(streamItem(serviceId = 1, url = "https://example.com/a")),
            0
        )

        assertFalse(LastQueueStore.matchesQueue(saved, queue))
    }

    @Test fun `matchesQueue is false for different url order`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(serviceId = 1, url = "https://example.com/a"),
                savedItem(serviceId = 1, url = "https://example.com/b")
            ),
            index = 0,
            savedAtMillis = 0L
        )
        val queue = SinglePlayQueue(
            listOf(
                streamItem(serviceId = 1, url = "https://example.com/b"),
                streamItem(serviceId = 1, url = "https://example.com/a")
            ),
            0
        )

        assertFalse(LastQueueStore.matchesQueue(saved, queue))
    }

    @Test fun `matchesQueue is false for different serviceId`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(serviceId = 1, url = "https://example.com/a"),
                savedItem(serviceId = 2, url = "https://example.com/b")
            ),
            index = 0,
            savedAtMillis = 0L
        )
        val queue = SinglePlayQueue(
            listOf(
                streamItem(serviceId = 1, url = "https://example.com/a"),
                streamItem(serviceId = 99, url = "https://example.com/b")
            ),
            0
        )

        assertFalse(LastQueueStore.matchesQueue(saved, queue))
    }

    @Test fun `fromPlayQueueItems maps fields`() {
        val streams = SinglePlayQueue(
            listOf(
                streamItem(),
                streamItem(
                    url = "https://example.com/watch?v=def",
                    title = "Second",
                    streamType = StreamType.AUDIO_STREAM,
                    uploader = "Other uploader",
                    duration = 60
                )
            ),
            0
        ).getStreams()

        val saved = LastQueueStore.fromPlayQueueItems(streams, 1, 12345L)

        assertEquals(2, saved.items.size)
        assertEquals(1, saved.index)
        assertEquals(12345L, saved.savedAtMillis)
        assertEquals(
            SavedQueueItem(
                serviceId = 1,
                url = "https://example.com/watch?v=abc",
                title = "Some title",
                uploader = "Some uploader",
                uploaderUrl = "https://example.com/channel/xyz",
                duration = 3723,
                thumbnailUrl = "https://example.com/thumb.jpg",
                streamType = "VIDEO_STREAM"
            ),
            saved.items[0]
        )
        assertEquals("Second", saved.items[1].title)
        assertEquals("AUDIO_STREAM", saved.items[1].streamType)
    }

    @Test fun `fromPlayQueueItems clamps index`() {
        val streams = SinglePlayQueue(listOf(streamItem(), streamItem()), 0).getStreams()

        assertEquals(0, LastQueueStore.fromPlayQueueItems(streams, -1, 0L).index)
        assertEquals(0, LastQueueStore.fromPlayQueueItems(streams, 0, 0L).index)
        assertEquals(1, LastQueueStore.fromPlayQueueItems(streams, 1, 0L).index)
        assertEquals(1, LastQueueStore.fromPlayQueueItems(streams, 99, 0L).index)
        assertEquals(0, LastQueueStore.fromPlayQueueItems(emptyList(), 5, 0L).index)
    }

    @Test fun `fromPlayQueueItems maps missing thumbnail to null`() {
        val streams = SinglePlayQueue(listOf(streamItem(thumbnailUrl = null)), 0).getStreams()

        assertNull(LastQueueStore.fromPlayQueueItems(streams, 0, 0L).items[0].thumbnailUrl)
    }

    @Test fun `toPlayQueue restores size index and urls`() {
        val saved = SavedQueue(
            items = listOf(
                savedItem(url = "https://example.com/a"),
                savedItem(
                    url = "https://example.com/b",
                    title = "Second",
                    streamType = StreamType.AUDIO_STREAM.name,
                    duration = 60
                ),
                savedItem(url = "https://example.com/c", thumbnailUrl = null)
            ),
            index = 0,
            savedAtMillis = 0L
        )

        val queue = LastQueueStore.toPlayQueue(saved, 2)

        assertEquals(3, queue.size())
        assertEquals(2, queue.getIndex())
        assertEquals(
            listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            queue.getStreams().map { it.url }
        )
        val second = queue.getStreams()[1]
        assertEquals("Second", second.title)
        assertEquals("Some uploader", second.uploader)
        assertEquals("https://example.com/channel/xyz", second.uploaderUrl)
        assertEquals(60, second.duration)
        assertEquals(StreamType.AUDIO_STREAM, second.streamType)
        assertEquals(
            listOf("https://example.com/thumb.jpg"),
            second.thumbnails.map { it.url }
        )
        assertTrue(queue.getStreams()[2].thumbnails.isEmpty())
    }

    @Test fun `toPlayQueue clamps index`() {
        val saved = SavedQueue(items = listOf(savedItem(), savedItem()), index = 0, savedAtMillis = 0L)

        assertEquals(0, LastQueueStore.toPlayQueue(saved, -3).getIndex())
        assertEquals(1, LastQueueStore.toPlayQueue(saved, 1).getIndex())
        assertEquals(1, LastQueueStore.toPlayQueue(saved, 99).getIndex())
    }

    @Test fun `toPlayQueue falls back to video stream for unknown types`() {
        val saved = SavedQueue(items = listOf(savedItem(streamType = "SOME_FUTURE_TYPE")), index = 0, savedAtMillis = 0L)

        val queue = LastQueueStore.toPlayQueue(saved, 0)

        assertEquals(StreamType.VIDEO_STREAM, queue.getStreams()[0].streamType)
    }

    @Test fun `toPlayQueue wraps thumbnail with unknown dimensions`() {
        val saved = SavedQueue(items = listOf(savedItem()), index = 0, savedAtMillis = 0L)

        val thumbnails = LastQueueStore.toPlayQueue(saved, 0).getStreams()[0].thumbnails

        assertEquals(1, thumbnails.size)
        assertEquals("https://example.com/thumb.jpg", thumbnails[0].url)
        assertEquals(Image.HEIGHT_UNKNOWN, thumbnails[0].height)
        assertEquals(Image.WIDTH_UNKNOWN, thumbnails[0].width)
        assertEquals(Image.ResolutionLevel.UNKNOWN, thumbnails[0].estimatedResolutionLevel)
    }
}
