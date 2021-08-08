package com.github.k1rakishou.chan.features.media_viewer

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.viewpager.widget.ViewPager
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.image.ImageLoaderV2
import com.github.k1rakishou.chan.core.manager.Chan4CloudFlareImagePreloaderManager
import com.github.k1rakishou.chan.core.manager.GlobalWindowInsetsManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.features.gesture_editor.Android10GesturesExclusionZonesHolder
import com.github.k1rakishou.chan.features.image_saver.ImageSaverV2
import com.github.k1rakishou.chan.features.image_saver.ImageSaverV2OptionsController
import com.github.k1rakishou.chan.features.media_viewer.helper.ExoPlayerCache
import com.github.k1rakishou.chan.features.media_viewer.helper.ExoPlayerWrapper
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaLongClickMenuHelper
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerMenuHelper
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerOpenAlbumHelper
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerScrollerHelper
import com.github.k1rakishou.chan.features.media_viewer.media_view.MediaViewContract
import com.github.k1rakishou.chan.ui.view.AppearTransitionImageView
import com.github.k1rakishou.chan.ui.view.OptionalSwipeViewPager
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.setVisibilityFast
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.awaitSilently
import com.github.k1rakishou.common.resumeValueSafe
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.persist_state.PersistableChanState.imageSaverV2PersistedOptions
import com.google.android.exoplayer2.upstream.ContentDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import dagger.Lazy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class MediaViewerController(
  context: Context,
  private val mediaViewerCallbacks: MediaViewerCallbacks
) : Controller(context), ViewPager.OnPageChangeListener, MediaViewContract {

  @Inject
  lateinit var appConstants: AppConstants
  @Inject
  lateinit var imageLoaderV2: ImageLoaderV2
  @Inject
  lateinit var mediaViewerScrollerHelper: MediaViewerScrollerHelper
  @Inject
  lateinit var exoPlayerCache: ExoPlayerCache
  @Inject
  lateinit var imageSaverV2: Lazy<ImageSaverV2>
  @Inject
  lateinit var globalWindowInsetsManager: GlobalWindowInsetsManager
  @Inject
  lateinit var chan4CloudFlareImagePreloaderManager: Chan4CloudFlareImagePreloaderManager
  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var exclusionZonesHolder: Android10GesturesExclusionZonesHolder
  @Inject
  lateinit var mediaViewerOpenAlbumHelper: MediaViewerOpenAlbumHelper

  private var chanDescriptor: ChanDescriptor? = null

  override val viewerChanDescriptor: ChanDescriptor?
    get() = chanDescriptor

  private lateinit var mediaViewerRootLayout: MediaViewerRootLayout
  private lateinit var appearPreviewImage: AppearTransitionImageView
  private lateinit var pager: OptionalSwipeViewPager
  private lateinit var mediaViewerToolbar: MediaViewerToolbar

  private val viewModel by (context as ComponentActivity).viewModels<MediaViewerControllerViewModel>()

  private val mediaViewerMenuHelper by lazy {
    MediaViewerMenuHelper(
      globalWindowInsetsManager = globalWindowInsetsManager,
      presentControllerFunc = { controller -> presentController(controller, true) },
      showToastFunc = { messageId -> showToast(messageId) }
    )
  }

  private val mediaLongClickMenuHelper by lazy {
    MediaLongClickMenuHelper(
      scope = mainScope,
      globalWindowInsetsManager = globalWindowInsetsManager,
      imageSaverV2 = imageSaverV2.get(),
      getMediaViewerAdapterFunc = { mediaViewerAdapter },
      presentControllerFunc = { controller -> presentController(controller, true) }
    )
  }

  private val transitionAnimationAwaitable = CompletableDeferred<Unit>()

  private val mediaViewerAdapter: MediaViewerAdapter?
    get() = pager.adapter as? MediaViewerAdapter

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()

    view = AppModuleAndroidUtils.inflate(context, R.layout.controller_media_viewer)

    mediaViewerRootLayout = view.findViewById(R.id.media_viewer_root_layout)
    appearPreviewImage = view.findViewById(R.id.appear_preview_image)

    mediaViewerToolbar = view.findViewById(R.id.media_viewer_toolbar)
    mediaViewerToolbar.onCreate()

    pager = view.findViewById(R.id.pager)
    pager.addOnPageChangeListener(this)

    val offscreenPageLimit = MediaViewerControllerViewModel.offscreenPageLimit()
    Logger.d(TAG, "offscreenPageLimit=$offscreenPageLimit")
    pager.offscreenPageLimit = offscreenPageLimit

    mainScope.launch(context = Dispatchers.Main.immediate) {
      viewModel.transitionInfoFlow.collect { transitionInfo ->
        BackgroundUtils.ensureMainThread()

        if (transitionAnimationAwaitable.isCompleted) {
          return@collect
        }

        runAppearAnimation(transitionInfo)
        transitionAnimationAwaitable.complete(Unit)
      }
    }

    mainScope.launch {
      viewModel.mediaViewerState.collect { mediaViewerState ->
        BackgroundUtils.ensureMainThread()

        awaitThumbnailLoadedAndShowViewPager(mediaViewerState)
      }
    }
  }

  fun onResume() {
    mediaViewerAdapter?.onResume()
  }

  fun onPause() {
    mediaViewerAdapter?.onPause()
  }

  override fun onDestroy() {
    super.onDestroy()

    mediaViewerAdapter?.onDestroy()
    mediaLongClickMenuHelper.onDestroy()
    mediaViewerToolbar.onDestroy()

    chanDescriptor = null
    pager.removeOnPageChangeListener(this)
    pager.adapter = null

    ExoPlayerWrapper.releaseAll()
  }

  override fun onPageSelected(position: Int) {
    mediaViewerAdapter?.let { adapter ->
      adapter as MediaViewerAdapter

      adapter.doBind(position)
      viewModel.updateLastViewedIndex(position)
    }
  }

  override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
    // no-op
  }

  override fun onPageScrollStateChanged(state: Int) {
    // no-op
  }

  override fun changeMediaViewerBackgroundAlpha(newAlpha: Float) {
    mediaViewerRootLayout.alpha = newAlpha
  }

  override fun toggleSoundMuteState() {
    viewModel.toggleIsSoundMuted()
  }

  override fun isSoundCurrentlyMuted(): Boolean {
    return viewModel.isSoundMuted
  }

  override fun onTapped() {
    mediaViewerCallbacks.toggleFullScreenMode()
  }

  override fun closeMediaViewer() {
    mediaViewerCallbacks.finishActivity()
  }

  override suspend fun onDownloadButtonClick(viewableMedia: ViewableMedia, longClick: Boolean): Boolean {
    val simpleImageInfo = viewableMedia.toSimpleImageInfoOrNull()
    if (simpleImageInfo == null) {
      showToast("Cannot save image because some info required by SimpleImageInfo is not present (image=${viewableMedia})")
      return false
    }

    val imageSaverV2Options = imageSaverV2PersistedOptions.get()

    if (!longClick && !imageSaverV2Options.shouldShowImageSaverOptionsController()) {
      imageSaverV2.get().save(imageSaverV2Options, simpleImageInfo, null)
      return true
    }

    return suspendCancellableCoroutine { continuation ->
      val options = ImageSaverV2OptionsController.Options.SingleImage(
        simpleSaveableMediaInfo = simpleImageInfo,
        onSaveClicked = { updatedImageSaverV2Options, newFileName ->
          imageSaverV2.get().save(updatedImageSaverV2Options, simpleImageInfo, newFileName)
          continuation.resumeValueSafe(true)
        },
        onCanceled = { continuation.resumeValueSafe(false) }
      )

      val controller = ImageSaverV2OptionsController(context, options)
      presentController(controller)
    }
  }

  override fun onOptionsButtonClick(viewableMedia: ViewableMedia) {
    val adapter = mediaViewerAdapter
      ?: return

    mediaViewerMenuHelper.onMediaViewerOptionsClick(context, adapter)
  }

  override fun onMediaLongClick(
    view: View,
    viewableMedia: ViewableMedia
  ) {
    mediaLongClickMenuHelper.onMediaLongClick(view, viewableMedia)
  }

  override suspend fun defaultArtworkDrawable(): Drawable? {
    return imageLoaderV2.loadFromNetworkSuspend(
      context,
      AppConstants.RESOURCES_ENDPOINT + "audio_thumb.png",
      ImageLoaderV2.ImageSize.MeasurableImageSize.create(appearPreviewImage),
      emptyList()
    ).valueOrNull()
  }

  override fun openAlbum(viewableMedia: ViewableMedia) {
    val postDescriptor = viewableMedia.viewableMediaMeta.ownerPostDescriptor
      ?: return

    val albumOpened = mediaViewerOpenAlbumHelper.openAlbum(
      chanDescriptor = chanDescriptor,
      postDescriptor = postDescriptor,
      mediaLocation = viewableMedia.mediaLocation
    )

    if (!albumOpened) {
      return
    }

    closeMediaViewer()
  }

  fun onSystemUiVisibilityChanged(systemUIHidden: Boolean) {
    mediaViewerAdapter?.onSystemUiVisibilityChanged(systemUIHidden)
  }

  private suspend fun awaitThumbnailLoadedAndShowViewPager(
    mediaViewerState: MediaViewerControllerViewModel.MediaViewerControllerState?
  ) {
    BackgroundUtils.ensureMainThread()

    this.chanDescriptor = mediaViewerState?.descriptor

    if (mediaViewerState == null) {
      return
    }

    transitionAnimationAwaitable.awaitSilently(Unit)

    pager.setVisibilityFast(View.INVISIBLE)
    pager.setSwipingEnabled(true)

    val previewThumbnailLocation =
      mediaViewerState.loadedMedia[mediaViewerState.initialPagerIndex].mediaLocation

    val adapter = MediaViewerAdapter(
      context = context,
      viewModel = viewModel,
      mediaViewerToolbar = mediaViewerToolbar,
      mediaViewContract = this@MediaViewerController,
      initialPagerIndex = mediaViewerState.initialPagerIndex,
      viewableMediaList = mediaViewerState.loadedMedia,
      previewThumbnailLocation = previewThumbnailLocation,
      mediaViewerScrollerHelper = mediaViewerScrollerHelper,
      cachedHttpDataSourceFactory = createCacheDataSourceFactory(mediaViewerState.loadedMedia),
      fileDataSourceFactory = FileDataSource.Factory(),
      contentDataSourceFactory = DataSource.Factory { ContentDataSource(context) },
      chan4CloudFlareImagePreloaderManager = chan4CloudFlareImagePreloaderManager,
      isSystemUiHidden = { mediaViewerCallbacks.isSystemUiHidden() },
      swipeDirection = { pager.swipeDirection }
    )

    pager.adapter = adapter
    pager.setCurrentItem(mediaViewerState.initialPagerIndex, false)

    withTimeoutOrNull(MAX_WAIT_TIME_MS) { adapter.awaitUntilPreviewThumbnailFullyLoaded() }

    pager.setVisibilityFast(View.VISIBLE)
    appearPreviewImage.setVisibilityFast(View.INVISIBLE)

    Logger.d(TAG, "Loaded ${mediaViewerState.loadedMedia.size} media items, " +
        "initialPagerIndex=${mediaViewerState.initialPagerIndex}")
  }

  private fun createCacheDataSourceFactory(viewableMedia: List<ViewableMedia>): DataSource.Factory {
    val defaultDataSourceFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(appConstants.userAgent)
      .setDefaultRequestProperties(createRequestProperties(viewableMedia))

    return CacheDataSource.Factory()
      .setCache(exoPlayerCache.actualCache)
      .setUpstreamDataSourceFactory(defaultDataSourceFactory)
  }

  private fun createRequestProperties(viewableMediaList: List<ViewableMedia>): Map<String, String> {
    val siteDescriptors = hashSetOf<SiteDescriptor>()

    viewableMediaList.forEach { viewableMedia ->
      val siteDescriptor = viewableMedia.viewableMediaMeta.ownerPostDescriptor?.siteDescriptor()
        ?: return@forEach

      siteDescriptors += siteDescriptor
    }

    if (siteDescriptors.isEmpty()) {
      return emptyMap()
    }

    val requestProps = mutableMapOf<String, String>()

    siteDescriptors.forEach { siteDescriptor ->
      val site = siteManager.bySiteDescriptor(siteDescriptor)
      if (site != null) {
        site.requestModifier().modifyVideoStreamRequest(site, requestProps)
      }
    }

    return requestProps
  }

  private suspend fun runAppearAnimation(transitionInfo: ViewableMediaParcelableHolder.TransitionInfo?) {
    BackgroundUtils.ensureMainThread()

    if (transitionInfo == null) {
      mediaViewerRootLayout.setBackgroundColor(BACKGROUND_COLOR)
      return
    }

    val resultBitmap = withTimeoutOrNull(MAX_WAIT_TIME_MS) {
      imageLoaderV2.loadFromNetworkSuspend(
        context,
        transitionInfo.transitionThumbnailUrl,
        ImageLoaderV2.ImageSize.MeasurableImageSize.create(appearPreviewImage)
      ).valueOrNull()?.bitmap
    }

    if (resultBitmap == null) {
      mediaViewerRootLayout.setBackgroundColor(BACKGROUND_COLOR)
      return
    }

    appearPreviewImage.setWindowLocation(transitionInfo.lastTouchPosX, transitionInfo.lastTouchPosY)
    appearPreviewImage.setBitmap(resultBitmap)

    suspendCancellableCoroutine<Unit> { continuation ->
      appearPreviewImage.runAppearAnimation(
        view,
        BACKGROUND_COLOR,
        { currentBackgroundColor -> mediaViewerRootLayout.setBackgroundColor(currentBackgroundColor) },
        { continuation.resumeValueSafe(Unit) }
      )
    }
  }

  interface MediaViewerCallbacks {
    fun finishActivity()

    fun isSystemUiHidden(): Boolean
    fun toggleFullScreenMode()
  }

  companion object {
    private const val TAG = "MediaViewerController"
    private const val BACKGROUND_COLOR = 0xDD000000L.toInt()
    private const val MAX_WAIT_TIME_MS = 1000L
  }
}