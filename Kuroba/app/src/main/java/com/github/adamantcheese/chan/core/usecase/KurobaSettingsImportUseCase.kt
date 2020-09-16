package com.github.adamantcheese.chan.core.usecase

import com.github.adamantcheese.chan.core.manager.*
import com.github.adamantcheese.chan.core.site.Site
import com.github.adamantcheese.chan.core.site.sites.Lainchan
import com.github.adamantcheese.chan.core.site.sites.Sushichan
import com.github.adamantcheese.chan.core.site.sites.Wired7
import com.github.adamantcheese.chan.core.site.sites.chan4.Chan4
import com.github.adamantcheese.chan.core.site.sites.chan420.Chan420
import com.github.adamantcheese.chan.core.site.sites.dvach.Dvach
import com.github.adamantcheese.chan.core.site.sites.kun8.Kun8
import com.github.adamantcheese.chan.utils.AndroidUtils
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.common.*
import com.github.adamantcheese.common.ModularResult.Companion.Try
import com.github.adamantcheese.model.data.descriptor.BoardDescriptor
import com.github.adamantcheese.model.data.descriptor.ChanDescriptor
import com.github.adamantcheese.model.data.descriptor.PostDescriptor
import com.github.adamantcheese.model.data.descriptor.SiteDescriptor
import com.github.adamantcheese.model.data.filter.ChanFilter
import com.github.adamantcheese.model.data.post.ChanPostHide
import com.github.adamantcheese.model.repository.ChanPostRepository
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.ExternalFile
import com.github.k1rakishou.fsaf.file.FileDescriptorMode
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FileReader
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class KurobaSettingsImportUseCase(
  private val fileManager: FileManager,
  private val siteManager: SiteManager,
  private val boardManager: BoardManager,
  private val filterManager: ChanFilterManager,
  private val postHideManager: PostHideManager,
  private val bookmarksManager: BookmarksManager,
  private val chanPostRepository: ChanPostRepository
) : ISuspendUseCase<ExternalFile, ModularResult<Unit>> {

  override suspend fun execute(parameter: ExternalFile): ModularResult<Unit> {
    return Try {
      val siteIdMap = readSiteIdMap(parameter)

      val parcelFileDescriptor = fileManager.getParcelFileDescriptor(parameter, FileDescriptorMode.Read)
        ?: throw IOException("Failed to get ParcelFileDescriptor")

      parcelFileDescriptor.use { pfd ->
        JsonReader(FileReader(pfd.fileDescriptor)).use { jsonReader ->
          importFromKuroba(siteIdMap, jsonReader)
        }
      }

    }.peekError { error -> Logger.e(TAG, "KurobaSettingsImportUseCase failure", error) }
  }

  private fun readSiteIdMap(parameter: ExternalFile): Map<Int, Int> {
    val parcelFileDescriptor = fileManager.getParcelFileDescriptor(parameter, FileDescriptorMode.Read)
      ?: throw IOException("Failed to get ParcelFileDescriptor")

    return parcelFileDescriptor.use { pfd ->
      JsonReader(FileReader(pfd.fileDescriptor)).use { jsonReader ->
        readSiteIdMap(jsonReader)
      }
    }
  }

  private suspend fun importFromKuroba(siteIdMap: Map<Int, Int>, jsonReader: JsonReader) {
    Logger.d(TAG, "importFromKuroba() called")

    if (AndroidUtils.isDevBuild()) {
      siteIdMap.forEach { (databaseId, classId) ->
        Logger.d(TAG, "Mapped site databaseId=$databaseId to site classId=$classId")
      }
    }

    awaitAndThrowIfRequiredTablesNotEmpty()

    val boardsToActivate = linkedSetOf<BoardDescriptor>()
    val filters = mutableSetOf<ChanFilter>()
    val postHides = mutableSetOf<ChanPostHide>()
    val bookmarks = mutableSetOf<SimpleBookmark>()

    jsonReader.jsonObject {
      while (hasNext()) {
        when (nextName()) {
          "exported_boards" -> readBoardsToActivate(siteIdMap) { boardDescriptor ->
            boardsToActivate += boardDescriptor
          }
          "exported_filters" -> readFilters(siteIdMap) { chanFilter ->
            filters += chanFilter
          }
          "exported_post_hides" -> readPostHides(siteIdMap) { postHide ->
            postHides += postHide
          }
          "exported_sites" -> {
            jsonArray {
              while (hasNext()) {
                jsonObject {
                  while (hasNext()) {
                    when (nextName()) {
                      "exported_pins" -> readBookmarks(siteIdMap) { simpleBookmark ->
                        bookmarks += simpleBookmark
                      }
                      else -> skipValue()
                    }
                  }
                }
              }
            }
          }
          else -> skipValue()
        }
      }
    }

    val siteDescriptorsToActivate = boardsToActivate
      .map { boardDescriptor -> boardDescriptor.siteDescriptor }
      .toSet()

    // Activate sites and preload board info
    val actualExistingSites = activateSitesAndLoadBoardInfo(siteDescriptorsToActivate)

    // Activate boards
    activateBoards(boardsToActivate, actualExistingSites)

    // Create filters
    filters.forEach { chanFilter -> createNewFilter(chanFilter) }

    // Create post hides
    createPostHides(postHides)

    // Create bookmarks
    createBookmarks(bookmarks)

    Logger.d(TAG, "importFromKuroba() success")
  }

  private suspend fun awaitAndThrowIfRequiredTablesNotEmpty() {
    siteManager.awaitUntilInitialized()
    boardManager.awaitUntilInitialized()
    filterManager.awaitUntilInitialized()
    bookmarksManager.awaitUntilInitialized()

    if (siteManager.activeSiteCount() > 0) {
      throw KurobaSettingsImportException.DatabaseIsNotEmpty("Sites")
    }

    if (boardManager.getTotalCount() > 0) {
      throw KurobaSettingsImportException.DatabaseIsNotEmpty("Boards")
    }

    val postHideCountResult = postHideManager.getTotalCount()
    if (postHideCountResult is ModularResult.Error) {
      Logger.e(TAG, "Failed to count post hides", postHideCountResult.error)
      throw postHideCountResult.error
    }

    postHideCountResult as ModularResult.Value

    if (postHideCountResult.value > 0) {
      throw KurobaSettingsImportException.DatabaseIsNotEmpty("Post hides")
    }

    if (filterManager.filtersCount() > 0) {
      throw KurobaSettingsImportException.DatabaseIsNotEmpty("Filters")
    }

    if (bookmarksManager.bookmarksCount() > 0) {
      throw KurobaSettingsImportException.DatabaseIsNotEmpty("Bookmarks")
    }
  }

  private suspend fun createBookmarks(bookmarks: MutableSet<SimpleBookmark>) {
    bookmarks.forEachIndexed { index, simpleBookmark ->
      Logger.d(TAG, "Creating empty thread entity in the database for bookmark ${simpleBookmark}")
      chanPostRepository.createEmptyThreadIfNotExists(simpleBookmark.threadDescriptor)

      val persist = index == bookmarks.size - 1
      Logger.d(TAG, "Creating bookmark ${simpleBookmark}, persist=$persist")

      bookmarksManager.createBookmark(
        simpleBookmark.threadDescriptor,
        simpleBookmark.title,
        simpleBookmark.thumbnailUrl,
        persist
      )
    }
  }

  private suspend fun createPostHides(postHides: MutableSet<ChanPostHide>) {
    if (AndroidUtils.isDevBuild()) {
      postHides.forEach { chanPostHide ->
        Logger.d(TAG, "Creating post hide $chanPostHide")
      }
    }

    postHideManager.createManySuspend(postHides.toList())
  }

  private suspend fun activateBoards(
    boardsToActivate: LinkedHashSet<BoardDescriptor>,
    actualExistingSites: Set<SiteDescriptor>
  ) {
    val boardsToActivateMap = mutableMapOf<SiteDescriptor, LinkedHashSet<BoardDescriptor>>()

    boardsToActivate.forEach { boardDescriptor ->
      if (boardDescriptor.siteDescriptor !in actualExistingSites) {
        return@forEach
      }

      boardsToActivateMap.putIfNotContains(boardDescriptor.siteDescriptor, linkedSetOf())
      boardsToActivateMap[boardDescriptor.siteDescriptor]!!.add(boardDescriptor)
    }

    boardsToActivateMap.forEach { (siteDescriptor, boardDescriptors) ->
      if (AndroidUtils.isDevBuild()) {
        Logger.d(TAG, "Activating boards ${boardDescriptors}")
      }

      boardManager.activateDeactivateBoards(siteDescriptor, boardDescriptors, true)
    }
  }

  private suspend fun activateSitesAndLoadBoardInfo(
    siteDescriptorsToActivate: Set<SiteDescriptor>
  ): Set<SiteDescriptor> {
    if (AndroidUtils.isDevBuild()) {
      siteDescriptorsToActivate.forEach { siteDescriptor ->
        Logger.d(TAG, "activateSitesAndLoadBoardInfo() siteDescriptor=$siteDescriptor")
      }
    }

    return siteDescriptorsToActivate.mapNotNull { siteDescriptor ->
      Logger.d(TAG, "activateSitesAndLoadBoardInfo() activating $siteDescriptor")

      activateSiteSuspend(siteDescriptor)

      val siteEnabled = siteManager.bySiteDescriptor(siteDescriptor)!!.enabled()
      val active = siteManager.isSiteActive(siteDescriptor)

      Logger.d(TAG, "activateSitesAndLoadBoardInfo() $siteDescriptor activated " +
        "(siteEnabled=$siteEnabled, active=$active), loading boards")

      val site = requireNotNull(siteManager.bySiteDescriptor(siteDescriptor)) {
        "siteManager.bySiteDescriptor returned null for $siteDescriptor"
      }

      loadBoardInfoSuspend(site).safeUnwrap { error ->
        Logger.e(TAG, "Failed to load board info for site ${siteDescriptor}", error)
        return@mapNotNull null
      }

      Logger.d(TAG, "activateSitesAndLoadBoardInfo() loaded boards for $siteDescriptor")
      return@mapNotNull siteDescriptor
    }.toSet()
  }

  private fun JsonReader.readBookmarks(siteIdMap: Map<Int, Int>, func: (SimpleBookmark) -> Unit) {
    jsonArray {
      while (hasNext()) {
        jsonObject {
          while (hasNext()) {
            when (nextName()) {
              "exported_loadable" -> readSimpleBookmarks(siteIdMap, func)
              else -> skipValue()
            }
          }
        }
      }
    }
  }

  private fun JsonReader.readSimpleBookmarks(
    siteIdMap: Map<Int, Int>,
    func: (SimpleBookmark) -> Unit
  ) {
    var boardCode: String? = null
    var threadNo: Int? = null
    var databaseSiteId: Int? = null
    var thumbnailUrl: HttpUrl? = null
    var title: String? = null

    jsonObject {
      while (hasNext()) {
        when (nextName()) {
          "board_code" -> boardCode = nextStringOrNull()
          "no" -> threadNo = nextIntOrNull()
          "site_id" -> databaseSiteId = nextIntOrNull()
          "thumbnail_url" -> {
            var url = nextStringOrNull()
            if (url != null && url.isEmpty()) {
              url = null
            }

            thumbnailUrl = url?.toHttpUrlOrNull()
          }
          "title" -> {
            title = nextStringOrNull()
            if (title != null && title!!.isEmpty()) {
              title = null
            }
          }
          else -> skipValue()
        }
      }
    }

    if (
      boardCode.isNullOrEmpty() ||
      threadNo == null ||
      databaseSiteId == null
    ) {
      Logger.e(TAG, "readSimpleBookmarks() Failed to read simple thread bookmark: boardCode=${boardCode}, " +
        "threadNo=${threadNo}, databaseSiteId=${databaseSiteId}, thumbnailUrl=${thumbnailUrl}, title=${title}")
      return
    }

    val siteId = siteIdMap[databaseSiteId]
    if (siteId == null) {
      Logger.e(TAG, "readSimpleBookmarks() Failed to find siteId by databaseSiteId=${databaseSiteId}")
      return
    }

    val siteName = kurobaSites[siteId]
    if (siteName.isNullOrEmpty()) {
      Logger.e(TAG, "readSimpleBookmarks() Failed to find siteName by siteId=${siteId}")
      return
    }

    val threadDescriptor = ChanDescriptor.ThreadDescriptor.create(
      siteName,
      boardCode!!,
      threadNo!!.toLong()
    )

    func(SimpleBookmark(threadDescriptor, title, thumbnailUrl))
  }

  private fun JsonReader.readPostHides(siteIdMap: Map<Int, Int>, func: (ChanPostHide) -> Unit) {
    jsonArray {
      while (hasNext()) {
        jsonObject {
          var boardCode: String? = null
          var hide: Boolean? = null
          var hideRepliesToThisPost: Boolean? = null
          var postNo: Int? = null
          var databaseSiteId: Int? = null
          var threadNo: Int? = null
          var wholeThread: Boolean? = null

          while (hasNext()) {
            when (nextName()) {
              "board" -> boardCode = nextStringOrNull()
              "hide" -> hide = nextBooleanOrNull()
              "hideRepliesToThisPost" -> hideRepliesToThisPost = nextBooleanOrNull()
              "no" -> postNo = nextIntOrNull()
              "site" -> databaseSiteId = nextIntOrNull()
              "threadNo" -> threadNo = nextIntOrNull()
              "wholeThread" -> wholeThread = nextBooleanOrNull()
              else -> skipValue()
            }
          }

          if (boardCode.isNullOrEmpty() ||
            hide == null ||
            hideRepliesToThisPost == null ||
            postNo == null ||
            databaseSiteId == null ||
            threadNo == null ||
            wholeThread == null
          ) {
            Logger.e(TAG, "readPostHides() Failed to read post hide info: boardCode=${boardCode}, " +
              "hide=${hide}, hideRepliesToThisPost=${hideRepliesToThisPost}, postNo=${postNo}, " +
              "databaseSiteId=${databaseSiteId}, threadNo=${threadNo}, wholeThread=${wholeThread}")
            return@jsonObject
          }

          val siteId = siteIdMap[databaseSiteId]
          if (siteId == null) {
            Logger.e(TAG, "readPostHides() Failed to find siteId by databaseSiteId=${databaseSiteId}")
            return@jsonObject
          }

          val siteName = kurobaSites[siteId]
          if (siteName.isNullOrEmpty()) {
            Logger.e(TAG, "readPostHides() Failed to find siteName by siteId=${siteId}")
            return@jsonObject
          }

          val postDescriptor = if (wholeThread) {
            PostDescriptor.create(siteName, boardCode, postNo.toLong())
          } else {
            PostDescriptor.create(siteName, boardCode, threadNo.toLong(), postNo.toLong())
          }

          val postHide = ChanPostHide(postDescriptor, hide, wholeThread, hideRepliesToThisPost)
          func(postHide)
        }
      }
    }
  }

  private fun JsonReader.readFilters(siteIdMap: Map<Int, Int>, func: (ChanFilter) -> Unit) {
    jsonArray {
      while (hasNext()) {
        jsonObject {
          var action: Int? = null
          var applyToReplies: Boolean? = null
          var applyToSaved: Boolean? = null
          var boardsRaw: String? = null
          var color: Int? = null
          var enabled: Boolean? = null
          var onlyOnOp: Boolean? = null
          var order: Int? = null
          var pattern: String? = null
          var type: Int? = null

          while (hasNext()) {
            when (nextName()) {
              "action" -> action = nextIntOrNull()
              "apply_to_replies" -> applyToReplies = nextBooleanOrNull()
              "apply_to_saved" -> applyToSaved = nextBooleanOrNull()
              "boards" -> boardsRaw = nextStringOrNull()
              "color" -> color = nextIntOrNull()
              "enabled" -> enabled = nextBooleanOrNull()
              "only_on_op" -> onlyOnOp = nextBooleanOrNull()
              "order" -> order = nextIntOrNull()
              "pattern" -> pattern = nextStringOrNull()
              "type" -> type = nextIntOrNull()
              else -> skipValue()
            }
          }

          if (action == null ||
            applyToReplies == null ||
            applyToSaved == null ||
            boardsRaw == null ||
            color == null ||
            enabled == null ||
            onlyOnOp == null ||
            order == null ||
            pattern.isNullOrEmpty() ||
            type == null
          ) {
            Logger.e(TAG, "readFilters() Failed to read filter info: action=${action}, " +
              "applyToReplies=${applyToReplies}, applyToSaved=${applyToSaved}, boards=${boardsRaw}, " +
              "color=${color}, enabled=${enabled}, onlyOnOp=${onlyOnOp}, order=${order}, " +
              "pattern=${pattern}, type=${type}")
            return@jsonObject
          }

          if (action == FilterEngine.FilterAction.WATCH.id) {
            Logger.e(TAG, "readFilters() Skipping WATCH filter")
            return@jsonObject
          }

          val boardDescriptors = try {
            parseBoardDescriptors(siteIdMap, boardsRaw)
          } catch (error: Throwable) {
            Logger.e(TAG, "readFilters() Failed to parse boardDescriptors", error)
            return@jsonObject
          }

          val filter = ChanFilter(
            enabled = enabled,
            type = type,
            pattern = pattern,
            boards = boardDescriptors,
            action = action,
            color = color,
            applyToReplies = applyToReplies,
            onlyOnOP = onlyOnOp,
            applyToSaved = applyToSaved
          )

          func(filter)
        }
      }
    }
  }

  private fun readSiteIdMap(jsonReader: JsonReader): Map<Int, Int> {
    val resultMap = mutableMapOf<Int, Int>()

    jsonReader.jsonObject {
      while (hasNext()) {
        when (nextName()) {
          "exported_sites" -> {
            jsonArray {
              while (hasNext()) {
                jsonObject {
                  var classId: Int? = null
                  var databaseSiteId: Int? = null

                  while (hasNext()) {
                    when (nextName()) {
                      "class_id" -> classId = nextIntOrNull()
                      "site_id" -> databaseSiteId = nextIntOrNull()
                      else -> skipValue()
                    }
                  }

                  if (classId == null || databaseSiteId == null) {
                    Logger.e(TAG, "readSiteIdMap() Failed to read exported site: classId=$classId," +
                      " databaseSiteId=$databaseSiteId")
                    return@jsonObject
                  }

                  resultMap[databaseSiteId] = classId
                }
              }
            }
          }
          else -> skipValue()
        }
      }
    }

    return resultMap
  }

  private fun JsonReader.readBoardsToActivate(siteIdMap: Map<Int, Int>, func: (BoardDescriptor) -> Unit) {
    jsonArray {
      while (hasNext()) {
        jsonObject {
          var databaseSiteId: Int? = null
          var boardCode: String? = null
          var saved: Boolean? = null

          while (hasNext()) {
            when (nextName()) {
              "site_id" -> databaseSiteId = nextIntOrNull()
              "code" -> boardCode = nextStringOrNull()
              "saved" -> saved = nextBooleanOrNull()
              else -> skipValue()
            }
          }

          if (databaseSiteId == null || boardCode.isNullOrEmpty() || saved == null) {
            Logger.e(TAG, "readBoardsToActivate() Failed to read board info: " +
              "databaseSiteId=${databaseSiteId}, boardCode=${boardCode}, saved=${saved}")
            return@jsonObject
          }

          val actualSiteId = siteIdMap[databaseSiteId]
          if (actualSiteId == null) {
            Logger.e(TAG, "readBoardsToActivate() Failed to find actualSiteId " +
              "for databaseSiteId=${databaseSiteId}")
            return@jsonObject
          }

          val siteName = kurobaSites[actualSiteId]
          if (siteName.isNullOrEmpty()) {
            Logger.e(TAG, "readBoardsToActivate() Failed to find siteName by siteId=${actualSiteId}")
            return@jsonObject
          }

          if (!saved) {
            return@jsonObject
          }

          val boardDescriptor = BoardDescriptor(SiteDescriptor(siteName), boardCode)
          func(boardDescriptor)
        }
      }
    }
  }

  private fun parseBoardDescriptors(siteIdMap: Map<Int, Int>, boardsRaw: String): Set<BoardDescriptor> {
    return boardsRaw.split(",")
      .map { siteIdBoardCodePairStr ->
        val split = siteIdBoardCodePairStr.split(":")
        if (split.size != 2) {
          throw KurobaSettingsImportException.ParsingException(
            "Failed to split by \":\", value=($siteIdBoardCodePairStr)"
          )
        }

        val databaseSiteId = split[0].toIntOrNull()
        val boardCode = split[1]

        if (databaseSiteId == null || boardCode.isNullOrEmpty()) {
          throw KurobaSettingsImportException.ParsingException(
            "Bad databaseSiteId=($databaseSiteId) or boardCode=($boardCode)"
          )
        }

        val siteId = siteIdMap[databaseSiteId]
          ?: throw KurobaSettingsImportException.ParsingException(
            "Failed to find siteId by it's databaseSiteId=($databaseSiteId)"
          )

        val siteName = kurobaSites[siteId]
          ?: throw KurobaSettingsImportException.ParsingException(
            "Failed to find site name by siteId: $siteId"
          )

        return@map BoardDescriptor(SiteDescriptor(siteName), boardCode)
      }
      .toSet()
  }

  private suspend fun activateSiteSuspend(siteDescriptor: SiteDescriptor) {
    val deferred = CompletableDeferred<Unit>()
    val success = siteManager.activateOrDeactivateSite(siteDescriptor, true) { deferred.complete(Unit) }

    if (success) {
      deferred.await()
    }
  }

  private suspend fun createNewFilter(chanFilter: ChanFilter) {
    suspendCoroutine<Unit> { continuation ->
      filterManager.createOrUpdateFilter(chanFilter) {
        if (AndroidUtils.isDevBuild()) {
          Logger.d(TAG, "Creating filter $chanFilter")
        }

        continuation.resume(Unit)
      }
    }
  }

  private suspend fun loadBoardInfoSuspend(site: Site): ModularResult<Unit> {
    return suspendCoroutine { continuation ->
      site.loadBoardInfo { result ->
        continuation.resume(result.mapValue { Unit })
      }
    }
  }

  companion object {
    private const val TAG = "KurobaSettingsImportUseCase"

    private val kurobaSites = mutableMapOf<Int, String>().apply {
      put(0, Chan4.SITE_NAME)
      put(2, Lainchan.SITE_NAME)
      put(4, Sushichan.SITE_NAME)
      put(5, Dvach.SITE_NAME)
      put(6, Wired7.SITE_NAME)
      put(8, Kun8.SITE_NAME)
      put(9, Chan420.SITE_NAME)
    }
  }
}

data class SimpleBookmark(
  val threadDescriptor: ChanDescriptor.ThreadDescriptor,
  val title: String?,
  val thumbnailUrl: HttpUrl?
)

sealed class KurobaSettingsImportException(message: String) : Exception(message) {
  class DatabaseIsNotEmpty(tableName: String) : KurobaSettingsImportException("Database is not empty (table name = $tableName)")
  class ParsingException(message: String) : KurobaSettingsImportException(message)
}