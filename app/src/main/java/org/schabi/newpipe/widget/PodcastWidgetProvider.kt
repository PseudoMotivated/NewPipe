/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Home-screen "podcast" bar widget. It shows the last multi-item play queue recorded in
 * [LastQueueStore] and resumes it as background audio playback without opening the app. All
 * control buttons are foreground-service starts of [PlayerService] (see [PodcastWidgetContract]).
 */
class PodcastWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsyncSafely()
        try {
            updateStaticAndRefreshThumbnail(
                context.applicationContext,
                appWidgetManager,
                appWidgetIds,
                waitForThumbnail = true
            )
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "onUpdate failed", e)
            }
        } finally {
            finishPendingResultSafely(pendingResult)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsyncSafely()
        try {
            updateStaticAndRefreshThumbnail(
                context.applicationContext,
                appWidgetManager,
                intArrayOf(appWidgetId),
                waitForThumbnail = true
            )
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "onAppWidgetOptionsChanged failed", e)
            }
        } finally {
            finishPendingResultSafely(pendingResult)
        }
    }

    private fun goAsyncSafely(): BroadcastReceiver.PendingResult? {
        return try {
            goAsync()
        } catch (e: RuntimeException) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "goAsync failed", e)
            }
            null
        }
    }

    private fun finishPendingResultSafely(pendingResult: BroadcastReceiver.PendingResult?) {
        if (pendingResult == null) {
            return
        }
        try {
            pendingResult.finish()
        } catch (e: RuntimeException) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "PendingResult.finish failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PodcastWidgetProvider"

        /**
         * Widgets at least this tall (appWidgetMinHeight, dp) show the queue list below the bar.
         */
        private const val EXPANDED_MIN_HEIGHT_DP = 110

        /**
         * Thumbnails are scaled so neither side exceeds this, to stay under the RemoteViews
         * binder transaction limit.
         */
        private const val MAX_THUMBNAIL_SIZE_PX = 256

        /**
         * Broadcast callbacks wait at most this long for the thumbnail, so a slow network can
         * never trigger a broadcast ANR; the load keeps running in the background and still
         * applies when it finishes.
         */
        private const val THUMBNAIL_BROADCAST_WAIT_SECONDS = 8L

        private const val OPEN_APP_REQUEST_CODE = 0x706F6431
        private const val PLAY_INDEX_TEMPLATE_REQUEST_CODE = 0x706F6432

        private val thumbnailExecutor = Executors.newSingleThreadExecutor()

        /**
         * Re-renders every placed widget instance. Cheap and never throws; the player calls this
         * on queue and playback-state changes.
         */
        @JvmStatic
        fun requestUpdate(context: Context) {
            try {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(
                    ComponentName(appContext, PodcastWidgetProvider::class.java)
                )
                if (ids.isEmpty()) {
                    return
                }
                updateStaticAndRefreshThumbnail(appContext, manager, ids, waitForThumbnail = false)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "requestUpdate failed", e)
                }
            }
        }

        private fun updateStaticAndRefreshThumbnail(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            waitForThumbnail: Boolean
        ) {
            try {
                manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.podcast_widget_list)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "notifyAppWidgetViewDataChanged failed", e)
                }
            }

            val queue = try {
                LastQueueStore.load(context)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "LastQueueStore.load failed", e)
                }
                null
            }
            val playing = try {
                LastQueueStore.isPlaying(context)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "LastQueueStore.isPlaying failed", e)
                }
                false
            }
            for (appWidgetId in appWidgetIds) {
                try {
                    manager.updateAppWidget(
                        appWidgetId,
                        buildViews(context, appWidgetId, queue, playing, isTall(manager, appWidgetId), null)
                    )
                } catch (e: Exception) {
                    if (MainActivity.DEBUG) {
                        Log.d(TAG, "updateAppWidget failed for id $appWidgetId", e)
                    }
                }
            }

            val items = queue?.items.orEmpty()
            val expectedThumbnailUrl = items.getOrNull(queue?.index ?: -1)?.thumbnailUrl
            if (expectedThumbnailUrl.isNullOrEmpty()) {
                return
            }
            val task = Runnable {
                try {
                    loadAndApplyThumbnail(context, manager, appWidgetIds, expectedThumbnailUrl)
                } catch (e: Exception) {
                    if (MainActivity.DEBUG) {
                        Log.d(TAG, "thumbnail refresh failed", e)
                    }
                }
            }
            try {
                if (waitForThumbnail) {
                    val future = thumbnailExecutor.submit(task)
                    try {
                        future.get(THUMBNAIL_BROADCAST_WAIT_SECONDS, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        if (MainActivity.DEBUG) {
                            Log.d(TAG, "thumbnail load timed out, continuing in background", e)
                        }
                    }
                } else {
                    thumbnailExecutor.execute(task)
                }
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "thumbnail submit failed", e)
                }
            }
        }

        private fun loadAndApplyThumbnail(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            expectedThumbnailUrl: String
        ) {
            val bitmap = loadThumbnailBitmap(context, expectedThumbnailUrl) ?: return
            // Skip stale results: when the queue moved on, a newer update supersedes this one.
            val queue = try {
                LastQueueStore.load(context)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "LastQueueStore.load failed", e)
                }
                null
            }
            val items = queue?.items.orEmpty()
            if (items.getOrNull(queue?.index ?: -1)?.thumbnailUrl != expectedThumbnailUrl) {
                return
            }
            val playing = try {
                LastQueueStore.isPlaying(context)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "LastQueueStore.isPlaying failed", e)
                }
                false
            }
            for (appWidgetId in appWidgetIds) {
                try {
                    manager.updateAppWidget(
                        appWidgetId,
                        buildViews(context, appWidgetId, queue, playing, isTall(manager, appWidgetId), bitmap)
                    )
                } catch (e: Exception) {
                    if (MainActivity.DEBUG) {
                        Log.d(TAG, "updateAppWidget failed for id $appWidgetId", e)
                    }
                }
            }
        }

        private fun isTall(manager: AppWidgetManager, appWidgetId: Int): Boolean {
            return try {
                manager.getAppWidgetOptions(appWidgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= EXPANDED_MIN_HEIGHT_DP
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "getAppWidgetOptions failed for id $appWidgetId", e)
                }
                false
            }
        }

        private fun buildViews(
            context: Context,
            appWidgetId: Int,
            queue: SavedQueue?,
            playing: Boolean,
            tall: Boolean,
            thumbnail: Bitmap?
        ): RemoteViews {
            val items = queue?.items.orEmpty()
            val hasQueue = items.isNotEmpty()
            val index = if (hasQueue) queue!!.index.coerceIn(items.indices) else 0
            val views = RemoteViews(
                context.packageName,
                if (tall && hasQueue) R.layout.podcast_widget_expanded else R.layout.podcast_widget_bar
            )

            val openApp = openAppPendingIntent(context)
            views.setOnClickPendingIntent(R.id.podcast_widget_thumbnail, openApp)
            views.setOnClickPendingIntent(R.id.podcast_widget_text_area, openApp)

            if (!hasQueue) {
                views.setTextViewText(
                    R.id.podcast_widget_title,
                    context.getString(R.string.podcast_widget_empty)
                )
                views.setViewVisibility(R.id.podcast_widget_subtitle, View.GONE)
                views.setViewVisibility(R.id.podcast_widget_prev, View.GONE)
                views.setViewVisibility(R.id.podcast_widget_play_pause, View.GONE)
                views.setViewVisibility(R.id.podcast_widget_next, View.GONE)
                views.setImageViewResource(
                    R.id.podcast_widget_thumbnail,
                    R.drawable.placeholder_thumbnail_video
                )
            } else {
                val current = items[index]
                views.setTextViewText(R.id.podcast_widget_title, current.title)
                views.setViewVisibility(R.id.podcast_widget_subtitle, View.VISIBLE)
                val position = context.getString(R.string.podcast_widget_position, index + 1, items.size)
                views.setTextViewText(
                    R.id.podcast_widget_subtitle,
                    if (current.uploader.isBlank()) position else "$position · ${current.uploader}"
                )
                views.setViewVisibility(R.id.podcast_widget_prev, View.VISIBLE)
                views.setViewVisibility(R.id.podcast_widget_play_pause, View.VISIBLE)
                views.setViewVisibility(R.id.podcast_widget_next, View.VISIBLE)
                if (thumbnail != null) {
                    views.setImageViewBitmap(R.id.podcast_widget_thumbnail, thumbnail)
                } else {
                    views.setImageViewResource(
                        R.id.podcast_widget_thumbnail,
                        R.drawable.placeholder_thumbnail_video
                    )
                }
                views.setImageViewResource(
                    R.id.podcast_widget_play_pause,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )
                views.setContentDescription(
                    R.id.podcast_widget_play_pause,
                    context.getString(
                        if (playing) R.string.podcast_widget_pause else R.string.podcast_widget_play
                    )
                )
                views.setContentDescription(
                    R.id.podcast_widget_prev,
                    context.getString(R.string.podcast_widget_previous)
                )
                views.setContentDescription(
                    R.id.podcast_widget_next,
                    context.getString(R.string.podcast_widget_next)
                )
                views.setOnClickPendingIntent(
                    R.id.podcast_widget_prev,
                    PodcastWidgetContract.pendingIntent(context, PodcastWidgetContract.ACTION_PREVIOUS)
                )
                views.setOnClickPendingIntent(
                    R.id.podcast_widget_play_pause,
                    PodcastWidgetContract.pendingIntent(context, PodcastWidgetContract.ACTION_PLAY_PAUSE)
                )
                views.setOnClickPendingIntent(
                    R.id.podcast_widget_next,
                    PodcastWidgetContract.pendingIntent(context, PodcastWidgetContract.ACTION_NEXT)
                )
            }

            if (tall && hasQueue) {
                val adapterIntent = Intent(context, PodcastWidgetListService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Make the intent unique per widget, otherwise all lists share one factory.
                adapterIntent.data = Uri.parse("podcastwidget://widget/$appWidgetId")
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.podcast_widget_list, adapterIntent)
                views.setPendingIntentTemplate(
                    R.id.podcast_widget_list,
                    playIndexTemplatePendingIntent(context)
                )
            }
            return views
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Template for queue-list row taps. Built like [PodcastWidgetContract.pendingIntent] but
         * MUTABLE, so each row's fill-in intent can supply [PodcastWidgetContract.EXTRA_WIDGET_INDEX]
         * ([PendingIntent.FLAG_MUTABLE] is required for fill-ins on API 31+).
         *
         * The template deliberately omits EXTRA_WIDGET_INDEX: Intent.fillIn() merges extras with
         * putAll, so the row's index wins either way, and omitting it also works if fillIn ever
         * only filled missing extras. Action, component and the foreground extra always come from
         * the template, so rows keep the PlayerService target.
         */
        private fun playIndexTemplatePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PlayerService::class.java)
                .setAction("org.schabi.newpipe.widget.play_index.template")
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true)
                .putExtra(PodcastWidgetContract.EXTRA_WIDGET_ACTION, PodcastWidgetContract.ACTION_PLAY_INDEX)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, PLAY_INDEX_TEMPLATE_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, PLAY_INDEX_TEMPLATE_REQUEST_CODE, intent, flags)
            }
        }

        private fun loadThumbnailBitmap(context: Context, url: String): Bitmap? {
            return try {
                val raw = CoilHelper.loadBitmapBlocking(
                    context,
                    url,
                    R.drawable.placeholder_thumbnail_video
                ) ?: return null
                if (raw.width <= MAX_THUMBNAIL_SIZE_PX && raw.height <= MAX_THUMBNAIL_SIZE_PX) {
                    raw
                } else {
                    val scale = minOf(
                        MAX_THUMBNAIL_SIZE_PX / raw.width.toFloat(),
                        MAX_THUMBNAIL_SIZE_PX / raw.height.toFloat()
                    )
                    val width = (raw.width * scale).toInt().coerceAtLeast(1)
                    val height = (raw.height * scale).toInt().coerceAtLeast(1)
                    try {
                        Bitmap.createScaledBitmap(raw, width, height, true)
                    } catch (e: Exception) {
                        if (MainActivity.DEBUG) {
                            Log.d(TAG, "thumbnail scale failed", e)
                        }
                        raw
                    }
                }
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "thumbnail load failed", e)
                }
                null
            }
        }
    }
}
