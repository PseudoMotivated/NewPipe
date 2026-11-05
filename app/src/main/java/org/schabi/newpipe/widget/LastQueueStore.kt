/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Persists the last multi-item play queue so the podcast widget can resume it as background
 * audio playback without opening the app (see [PodcastWidgetContract]).
 *
 * The queue is stored as JSON in the app's files dir. All entry points tolerate corrupt or
 * missing data and never throw.
 */
object LastQueueStore {
    private const val TAG = "LastQueueStore"
    private const val QUEUE_FILE_NAME = "podcast_widget_queue.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"
    private const val PREFS_NAME = "podcast_widget"
    private const val KEY_PLAYING = "playing"

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    // In-memory cache of the last successful write, so repeated saves of an unchanged queue
    // (e.g. on every playback event) skip disk I/O entirely.
    @Volatile private var lastWrittenQueue: SavedQueue? = null

    @Volatile private var lastWrittenContent: String? = null

    /**
     * Records [queue] as the last multi-item queue. No-op when the queue holds fewer than
     * [PodcastWidgetContract.MIN_QUEUE_SIZE] items. Cheap enough to call on the main thread:
     * unchanged queues skip the write via the in-memory cache.
     */
    @JvmStatic
    fun save(context: Context, queue: PlayQueue) {
        try {
            if (queue.size() < PodcastWidgetContract.MIN_QUEUE_SIZE) {
                return
            }
            val fresh = fromPlayQueueItems(
                queue.getStreams(),
                queue.getIndex(),
                System.currentTimeMillis()
            )
            // Reuse the cached copy when nothing changed so the encoded content (including the
            // timestamp) stays stable and the comparison below can skip the write.
            val cached = lastWrittenQueue
            val saved = if (cached != null && cached.items == fresh.items && cached.index == fresh.index) {
                cached
            } else {
                fresh
            }
            val content = encode(saved)
            synchronized(lock) {
                if (content == lastWrittenContent) {
                    return
                }
                if (writeAtomically(queueFile(context), content)) {
                    lastWrittenQueue = saved
                    lastWrittenContent = content
                }
            }
        } catch (t: Throwable) {
            logError("Failed to save queue", t)
        }
    }

    /**
     * @return the stored queue with its index clamped into range, or null when nothing usable
     * is stored (missing, unreadable, corrupt or empty).
     */
    @JvmStatic
    fun load(context: Context): SavedQueue? {
        return try {
            val file = queueFile(context)
            if (!file.isFile) {
                return null
            }
            val saved = decode(file.readText()) ?: return null
            if (saved.items.isEmpty()) {
                return null
            }
            saved.copy(index = saved.index.coerceIn(0, saved.items.size - 1))
        } catch (t: Throwable) {
            logError("Failed to load queue", t)
            null
        }
    }

    /**
     * Updates only the index of the stored queue, leaving items and timestamp untouched.
     * No-op when no queue is stored.
     */
    @JvmStatic
    fun saveIndex(context: Context, index: Int) {
        try {
            val stored = load(context) ?: return
            val clamped = index.coerceIn(0, stored.items.size - 1)
            if (clamped == stored.index) {
                return
            }
            val updated = stored.copy(index = clamped)
            val content = encode(updated)
            synchronized(lock) {
                if (writeAtomically(queueFile(context), content)) {
                    lastWrittenQueue = updated
                    lastWrittenContent = content
                }
            }
        } catch (t: Throwable) {
            logError("Failed to save queue index", t)
        }
    }

    /**
     * Rebuilds a playable queue from [saved], starting at [index] (clamped into range).
     * Unknown stream types fall back to [StreamType.VIDEO_STREAM].
     */
    @JvmStatic
    fun toPlayQueue(saved: SavedQueue, index: Int): PlayQueue {
        val clampedIndex = if (saved.items.isEmpty()) 0 else index.coerceIn(0, saved.items.size - 1)
        val streamItems = saved.items.map { item ->
            val streamType = try {
                StreamType.valueOf(item.streamType)
            } catch (_: IllegalArgumentException) {
                StreamType.VIDEO_STREAM
            }
            StreamInfoItem(item.serviceId, item.url, item.title, streamType).apply {
                uploaderName = item.uploader
                uploaderUrl = item.uploaderUrl
                duration = item.duration
                thumbnails = if (item.thumbnailUrl != null) {
                    listOf(
                        Image(
                            item.thumbnailUrl,
                            Image.HEIGHT_UNKNOWN,
                            Image.WIDTH_UNKNOWN,
                            Image.ResolutionLevel.UNKNOWN
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
        return SinglePlayQueue(streamItems, clampedIndex)
    }

    @JvmStatic
    fun setPlaying(context: Context, playing: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PLAYING, playing)
                .apply()
        } catch (t: Throwable) {
            logError("Failed to save playing state", t)
        }
    }

    @JvmStatic
    fun isPlaying(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYING, false)
        } catch (t: Throwable) {
            logError("Failed to load playing state", t)
            false
        }
    }

    internal fun encode(saved: SavedQueue): String = json.encodeToString(saved)

    internal fun decode(content: String): SavedQueue? {
        return try {
            json.decodeFromString<SavedQueue>(content)
        } catch (_: Exception) {
            null
        }
    }

    internal fun fromPlayQueueItems(items: List<PlayQueueItem>, index: Int, now: Long): SavedQueue {
        val savedItems = items.map { item ->
            SavedQueueItem(
                serviceId = item.serviceId,
                url = item.url,
                title = item.title,
                uploader = item.uploader,
                uploaderUrl = item.uploaderUrl,
                duration = item.duration,
                thumbnailUrl = ImageStrategy.imageListToDbUrl(item.thumbnails ?: emptyList()),
                streamType = item.streamType.name
            )
        }
        val clampedIndex = if (savedItems.isEmpty()) 0 else index.coerceIn(0, savedItems.size - 1)
        return SavedQueue(items = savedItems, index = clampedIndex, savedAtMillis = now)
    }

    private fun queueFile(context: Context): File = File(context.filesDir, QUEUE_FILE_NAME)

    private fun writeAtomically(target: File, content: String): Boolean {
        val tmp = File(target.parent, target.name + TEMP_FILE_SUFFIX)
        return try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                logError("Failed to rename temp queue file", null)
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            tmp.delete()
            logError("Failed to write queue file", t)
            false
        }
    }

    private fun logError(message: String, t: Throwable?) {
        try {
            Log.e(TAG, message, t)
        } catch (_: Throwable) {
            // Ignored: logging must never break the caller (e.g. on JVM unit tests where
            // android.util.Log is a stub).
        }
    }
}
