/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization

/**
 * Feeds the expanded widget's queue list ([PodcastWidgetProvider]) with the items around the
 * current position. Tapping a row plays that index via a fill-in intent.
 */
class PodcastWidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PodcastWidgetListFactory(applicationContext)
    }
}

private class PodcastWidgetListFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<SavedQueueItem> = emptyList()
    private var currentIndex: Int = 0
    private var windowStart: Int = 0

    override fun onCreate() {
        // Nothing to do; data is loaded in onDataSetChanged().
    }

    override fun onDataSetChanged() {
        val queue = try {
            LastQueueStore.load(context)
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "LastQueueStore.load failed", e)
            }
            null
        }
        val loaded = queue?.items.orEmpty()
        if (loaded.isEmpty()) {
            items = emptyList()
            currentIndex = 0
            windowStart = 0
            return
        }
        items = loaded
        currentIndex = queue!!.index.coerceIn(loaded.indices)
        windowStart = (currentIndex - ROWS_BEFORE_CURRENT).coerceAtLeast(0)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int {
        if (items.isEmpty()) {
            return 0
        }
        val windowEnd = minOf(items.size - 1, currentIndex + ROWS_AFTER_CURRENT)
        return (windowEnd - windowStart + 1).coerceAtLeast(0)
    }

    override fun getViewAt(position: Int): RemoteViews {
        try {
            if (position < 0 || items.isEmpty() || position >= getCount()) {
                return blankRow()
            }
            val absoluteIndex = windowStart + position
            val item = items.getOrNull(absoluteIndex) ?: return blankRow()
            val views = RemoteViews(context.packageName, R.layout.podcast_widget_list_item)
            views.setTextViewText(R.id.podcast_widget_item_title, item.title)
            val duration = if (item.duration > 0) {
                Localization.getDurationString(item.duration)
            } else {
                ""
            }
            views.setTextViewText(R.id.podcast_widget_item_duration, duration)
            // Always set explicitly: row views may be recycled, so stale highlights must not leak.
            views.setInt(
                R.id.podcast_widget_item_root,
                "setBackgroundColor",
                if (absoluteIndex == currentIndex) CURRENT_ITEM_BACKGROUND else OTHER_ITEM_BACKGROUND
            )
            val fillIn = Intent()
                .putExtra(PodcastWidgetContract.EXTRA_WIDGET_ACTION, PodcastWidgetContract.ACTION_PLAY_INDEX)
                .putExtra(PodcastWidgetContract.EXTRA_WIDGET_INDEX, absoluteIndex)
            views.setOnClickFillInIntent(R.id.podcast_widget_item_root, fillIn)
            return views
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "getViewAt failed for position $position", e)
            }
            return blankRow()
        }
    }

    private fun blankRow(): RemoteViews {
        return try {
            val views = RemoteViews(context.packageName, R.layout.podcast_widget_list_item)
            views.setTextViewText(R.id.podcast_widget_item_title, "")
            views.setTextViewText(R.id.podcast_widget_item_duration, "")
            views.setInt(
                R.id.podcast_widget_item_root,
                "setBackgroundColor",
                OTHER_ITEM_BACKGROUND
            )
            views
        } catch (e: Exception) {
            if (MainActivity.DEBUG) {
                Log.d(TAG, "blankRow failed", e)
            }
            RemoteViews(context.packageName, R.layout.podcast_widget_list_item)
        }
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return (windowStart + position).toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }

    companion object {
        private const val TAG = "PodcastWidgetList"

        /**
         * The list shows the window from index - [ROWS_BEFORE_CURRENT] to
         * index + [ROWS_AFTER_CURRENT] (clamped to the queue).
         */
        private const val ROWS_BEFORE_CURRENT = 2
        private const val ROWS_AFTER_CURRENT = 8

        private const val CURRENT_ITEM_BACKGROUND = 0x33FFFFFF
        private const val OTHER_ITEM_BACKGROUND = 0x00000000
    }
}
