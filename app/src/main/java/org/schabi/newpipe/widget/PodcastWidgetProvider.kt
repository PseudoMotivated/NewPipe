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
        postRender(context, pendingResult, appWidgetIds.copyOf())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsyncSafely()
        postRender(context, pendingResult, intArrayOf(appWidgetId))
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

        private const val OPEN_APP_REQUEST_CODE = 0x706F6431
        private const val PLAY_INDEX_TEMPLATE_REQUEST_CODE = 0x706F6432

        private val thumbnailExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "podcast-widget-thumbnail").apply { isDaemon = true }
        }

        private val thumbnailLock = Any()

        @Volatile
        private var cachedThumbnailUrl: String? = null

        @Volatile
        private var cachedThumbnail: Bitmap? = null

        @Volatile
        private var thumbnailLoadingUrl: String? = null

        /**
         * Re-renders every placed widget instance. Returns immediately on the caller thread;
         * the whole render (store load, RemoteViews build and widget update) is posted to
         * [LastQueueStore.runOnIo]. Never throws.
         */
        @JvmStatic
        fun requestUpdate(context: Context) {
            postRender(context, null, null)
        }

        private fun postRender(
            context: Context,
            pendingResult: BroadcastReceiver.PendingResult?,
            appWidgetIds: IntArray?
        ) {
            val appContext = try {
                context.applicationContext ?: context
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "requestUpdate failed", e)
                }
                finishPendingResultSafely(pendingResult)
                return
            }
            try {
                LastQueueStore.runOnIo(
                    Runnable {
                        try {
                            renderAll(appContext, appWidgetIds)
                        } catch (e: Exception) {
                            if (MainActivity.DEBUG) {
                                Log.d(TAG, "render failed", e)
                            }
                        } finally {
                            finishPendingResultSafely(pendingResult)
                        }
                    }
                )
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "requestUpdate failed", e)
                }
                finishPendingResultSafely(pendingResult)
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

        private fun renderAll(context: Context, requestedIds: IntArray?) {
            val manager = try {
                AppWidgetManager.getInstance(context)
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "getInstance failed", e)
                }
                return
            }
            val appWidgetIds = requestedIds ?: try {
                manager.getAppWidgetIds(
                    ComponentName(context, PodcastWidgetProvider::class.java)
                )
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "getAppWidgetIds failed", e)
                }
                return
            }
            if (appWidgetIds.isEmpty()) {
                return
            }
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
            val items = queue?.items.orEmpty()
            val currentThumbnailUrl = if (items.isEmpty()) {
                null
            } else {
                val index = queue!!.index.coerceIn(items.indices)
                items[index].thumbnailUrl?.takeIf { it.isNotEmpty() }
            }
            val thumbnail = cachedThumbnailFor(currentThumbnailUrl)
            for (appWidgetId in appWidgetIds) {
                try {
                    manager.updateAppWidget(
                        appWidgetId,
                        buildViews(
                            context,
                            appWidgetId,
                            queue,
                            playing,
                            isTall(manager, appWidgetId),
                            thumbnail
                        )
                    )
                } catch (e: Exception) {
                    if (MainActivity.DEBUG) {
                        Log.d(TAG, "updateAppWidget failed for id $appWidgetId", e)
                    }
                }
            }
            if (currentThumbnailUrl != null && thumbnail == null) {
                enqueueThumbnailLoad(context, currentThumbnailUrl)
            }
        }

        private fun cachedThumbnailFor(url: String?): Bitmap? {
            if (url == null) {
                return null
            }
            return synchronized(thumbnailLock) {
                if (cachedThumbnailUrl == url) cachedThumbnail else null
            }
        }

        private fun enqueueThumbnailLoad(context: Context, url: String) {
            synchronized(thumbnailLock) {
                if (cachedThumbnailUrl == url && cachedThumbnail != null) {
                    return
                }
                if (thumbnailLoadingUrl == url) {
                    return
                }
                thumbnailLoadingUrl = url
            }
            val appContext = try {
                context.applicationContext ?: context
            } catch (e: Exception) {
                synchronized(thumbnailLock) {
                    if (thumbnailLoadingUrl == url) {
                        thumbnailLoadingUrl = null
                    }
                }
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "thumbnail submit failed", e)
                }
                return
            }
            try {
                thumbnailExecutor.execute {
                    val loaded = try {
                        loadThumbnailBitmap(appContext, url)
                    } catch (e: Exception) {
                        if (MainActivity.DEBUG) {
                            Log.d(TAG, "thumbnail load failed", e)
                        }
                        null
                    }
                    synchronized(thumbnailLock) {
                        if (loaded != null) {
                            cachedThumbnailUrl = url
                            cachedThumbnail = loaded
                        }
                        if (thumbnailLoadingUrl == url) {
                            thumbnailLoadingUrl = null
                        }
                    }
                    if (loaded != null && isCurrentThumbnailUrl(appContext, url)) {
                        requestUpdate(appContext)
                    }
                }
            } catch (e: Exception) {
                synchronized(thumbnailLock) {
                    if (thumbnailLoadingUrl == url) {
                        thumbnailLoadingUrl = null
                    }
                }
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "thumbnail submit failed", e)
                }
            }
        }

        private fun isCurrentThumbnailUrl(context: Context, url: String): Boolean {
            return try {
                val queue = LastQueueStore.load(context) ?: return false
                if (queue.items.isEmpty()) {
                    return false
                }
                val index = queue.index.coerceIn(queue.items.indices)
                queue.items[index].thumbnailUrl == url
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "LastQueueStore.load failed", e)
                }
                false
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
