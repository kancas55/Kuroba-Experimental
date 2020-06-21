package com.github.adamantcheese.chan.features.bookmarks.watcher

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.adamantcheese.chan.Chan
import com.github.adamantcheese.chan.core.manager.BookmarksManager
import com.github.adamantcheese.chan.utils.Logger
import javax.inject.Inject


class BookmarkWatcherWorker(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(context, params) {
  private val bookmarkWatcherDelegate = BookmarkWatcherDelegate()

  @Inject
  lateinit var bookmarksManager: BookmarksManager

  init {
    Chan.inject(this)
  }

  override suspend fun doWork(): Result {
    if (isStopped) {
      Logger.d(TAG, "Cannot start BookmarkWatcherWorker, already stopped")
      return Result.success()
    }

    if (!bookmarksManager.hasActiveBookmarks()) {
      Logger.d(TAG, "Cannot start BookmarkWatcherWorker, no active bookmarks requiring service")
      return Result.success()
    }

    return if (bookmarkWatcherDelegate.doWork()) {
      Result.success()
    } else {
      Result.failure()
    }
  }

  companion object {
    private const val TAG = "BookmarkWatcherWorker"
  }
}