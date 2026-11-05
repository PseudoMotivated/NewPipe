/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
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

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    // Single shared daemon thread for all widget disk work. Tasks run serially in posting
    // order, so a render posted after a save observes the saved data.
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "podcast-widget-io").apply { isDaemon = true }
    }

    // In-memory cache of the stored queue, set by save and by the first successful file read.
    @Volatile private var cachedQueue: SavedQueue? = null

    @Volatile private var lastWrittenContent: String? = null

    // In-memory only: a dead process or reboot naturally reports "paused".
    @Volatile private var playing = false

    /**
     * Runs [task] on the shared single-thread background executor. Exceptions are logged and
     * never propagated, neither to the caller nor to the executor thread.
     */
    @JvmStatic
    fun runOnIo(task: Runnable) {
        try {
            ioExecutor.execute {
                try {
                    task.run()
                } catch (t: Throwable) {
                    logError("Background task failed", t)
                }
            }
        } catch (t: Throwable) {
            logError("Failed to post background task", t)
        }
    }

    /**
     * Records [queue] as the last multi-item queue. No-op when the queue holds fewer than
     * [PodcastWidgetContract.MIN_QUEUE_SIZE] items. On the caller thread this only snapshots
     * the queue; conversion, encoding and the disk write run on [runOnIo]. Never throws.
     */
    @JvmStatic
    fun save(context: Context, queue: PlayQueue) {
        try {
            if (queue.size() < PodcastWidgetContract.MIN_QUEUE_SIZE) {
                return
            }
            val snapshot = ArrayList(queue.getStreams())
            val index = queue.getIndex()
            val appContext = context.applicationContext ?: context
            runOnIo {
                val fresh = fromPlayQueueItems(
                    snapshot,
                    index,
                    System.currentTimeMillis()
                )
                // Reuse the cached copy when nothing changed so the encoded content (including
                // the timestamp) stays stable and the comparison below can skip the write.
                val cached = cachedQueue
                val saved = if (cached != null && cached.items == fresh.items &&
                    cached.index == fresh.index
                ) {
                    cached
                } else {
                    fresh
                }
                val content = encode(saved)
                synchronized(lock) {
                    if (content == lastWrittenContent) {
                        cachedQueue = saved
                    } else if (writeAtomically(queueFile(appContext), content)) {
                        cachedQueue = saved
                        lastWrittenContent = content
                    }
                }
            }
        } catch (t: Throwable) {
            logError("Failed to save queue", t)
        }
    }

    /**
     * @return the stored queue with its index clamped into range, or null when nothing usable
     * is stored (missing, unreadable, corrupt or empty). Returns the in-memory cache when
     * present; otherwise reads and decodes the file once and caches the result. May be called
     * from any thread.
     */
    @JvmStatic
    fun load(context: Context): SavedQueue? {
        cachedQueue?.let { return it }
        return try {
            synchronized(lock) {
                cachedQueue?.let { return it }
                val file = queueFile(context)
                if (!file.isFile) {
                    return null
                }
                val saved = decode(file.readText()) ?: return null
                val clamped = saved.copy(index = saved.index.coerceIn(0, saved.items.size - 1))
                cachedQueue = clamped
                lastWrittenContent = encode(clamped)
                clamped
            }
        } catch (t: Throwable) {
            logError("Failed to load queue", t)
            null
        }
    }

    /**
     * @return true iff [saved] and [queue] hold the same number of items and, in order, every
     * item has the same service id and URL.
     */
    @JvmStatic
    fun matchesQueue(saved: SavedQueue, queue: PlayQueue): Boolean {
        if (saved.items.size != queue.size()) {
            return false
        }
        val streams = queue.getStreams()
        if (saved.items.size != streams.size) {
            return false
        }
        return saved.items.indices.all { i ->
            saved.items[i].serviceId == streams[i].serviceId &&
                saved.items[i].url == streams[i].url
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
        this.playing = playing
    }

    @JvmStatic
    fun isPlaying(context: Context): Boolean = playing

    internal fun encode(saved: SavedQueue): String = json.encodeToString(saved)

    internal fun decode(content: String): SavedQueue? {
        return try {
            json.decodeFromString<SavedQueue>(content).takeIf { it.items.isNotEmpty() }
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
