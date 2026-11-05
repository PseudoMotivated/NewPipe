/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.Serializable
import org.schabi.newpipe.player.PlayerService

/**
 * Shared contract between the player (which records the last multi-item queue) and the
 * home-screen "podcast" widget (which shows it and starts audio playback from it).
 */
object PodcastWidgetContract {
    /** Intent extra on a [PlayerService] start intent; value is one of the ACTION_* below. */
    const val EXTRA_WIDGET_ACTION = "podcast_widget_action"

    /** Intent extra: queue index to play, used with [ACTION_PLAY_INDEX]. */
    const val EXTRA_WIDGET_INDEX = "podcast_widget_index"

    const val ACTION_PLAY_PAUSE = "play_pause"
    const val ACTION_NEXT = "next"
    const val ACTION_PREVIOUS = "previous"
    const val ACTION_PLAY_INDEX = "play_index"

    /** Queues with fewer items than this are not recorded (a single video is not a playlist). */
    const val MIN_QUEUE_SIZE = 2

    /**
     * Builds the intent every widget control sends. It always targets [PlayerService] as a
     * foreground service start, so it works whether or not the player is currently alive.
     */
    @JvmStatic
    fun serviceIntent(context: Context, action: String, index: Int = -1): Intent = Intent(context, PlayerService::class.java)
        .setAction("org.schabi.newpipe.widget.$action.$index") // unique per PendingIntent
        .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true)
        .putExtra(EXTRA_WIDGET_ACTION, action)
        .putExtra(EXTRA_WIDGET_INDEX, index)

    @JvmStatic
    fun pendingIntent(context: Context, action: String, index: Int = -1): PendingIntent {
        val intent = serviceIntent(context, action, index)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = (action.hashCode() * 31) + index
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }
}

/** One recorded queue entry; every field has a default so other fork revisions still decode it. */
@Serializable
data class SavedQueueItem(
    val serviceId: Int = 0,
    val url: String = "",
    val title: String = "",
    val uploader: String = "",
    val uploaderUrl: String? = null,
    /** Seconds; <= 0 when unknown. */
    val duration: Long = -1,
    /** Best thumbnail URL, or null. */
    val thumbnailUrl: String? = null,
    /** Name of org.schabi.newpipe.extractor.stream.StreamType. */
    val streamType: String = "VIDEO_STREAM"
)

/** The last multi-item queue the user played, with the current position in it. */
@Serializable
data class SavedQueue(
    val items: List<SavedQueueItem> = emptyList(),
    val index: Int = 0,
    val savedAtMillis: Long = 0
)
