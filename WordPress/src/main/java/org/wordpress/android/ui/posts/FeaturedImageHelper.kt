package org.wordpress.android.ui.posts

import android.net.Uri
import androidx.lifecycle.LiveData
import org.wordpress.android.R
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.MediaActionBuilder
import org.wordpress.android.fluxc.model.MediaModel
import org.wordpress.android.fluxc.model.MediaModel.MediaUploadState
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.store.MediaStore
import org.wordpress.android.fluxc.store.MediaStore.CancelMediaPayload
import org.wordpress.android.fluxc.store.UploadStore
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.reader.utils.ReaderUtilsWrapper
import org.wordpress.android.ui.uploads.UploadServiceFacade
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.AppLog.T
import org.wordpress.android.util.FluxCUtilsWrapper
import org.wordpress.android.util.SiteUtils
import org.wordpress.android.util.StringUtils
import org.wordpress.android.viewmodel.SingleLiveEvent
import java.util.ArrayList
import javax.inject.Inject

const val EMPTY_LOCAL_POST_ID = -1

/**
 * Helper class for separating logic related to FeaturedImage upload.
 */
internal class FeaturedImageHelper @Inject constructor(
    private val uploadStore: UploadStore,
    private val mediaStore: MediaStore,
    private val uploadServiceFacade: UploadServiceFacade,
    private val readerUtilsWrapper: ReaderUtilsWrapper,
    private val fluxCUtilsWrapper: FluxCUtilsWrapper,
    private val dispatcher: Dispatcher
) {
    // TODO observe
    private val _snackBarMessages = SingleLiveEvent<SnackbarMessageHolder>()
    val snackBarMessages: LiveData<SnackbarMessageHolder> = _snackBarMessages

    fun getFailedFeaturedImageUpload(post: PostModel): MediaModel? {
        val failedMediaForPost = uploadStore.getFailedMediaForPost(post)
        for (item in failedMediaForPost) {
            if (item != null && item.markedLocallyAsFeatured) {
                return item
            }
        }
        return null
    }

    fun retryFeaturedImageUpload(site: SiteModel, post: PostModel): MediaModel? {
        val mediaModel = getFailedFeaturedImageUpload(post)
        if (mediaModel != null) {
            cancelNotifications(post, site)
            mediaModel.setUploadState(MediaUploadState.QUEUED)
            dispatcher.dispatch(MediaActionBuilder.newUpdateMediaAction(mediaModel))
            startUploadService(mediaModel)
        }
        return mediaModel
    }

    private fun startUploadService(media: MediaModel) {
        val mediaList = ArrayList<MediaModel>()
        mediaList.add(media)
        uploadServiceFacade.uploadMedia(mediaList)
    }

    fun queueFeaturedImageForUpload(
        localPostId: Int,
        site: SiteModel,
        uri: Uri,
        mimeType: String?
    ) {
        val media = fluxCUtilsWrapper.mediaModelFromLocalUri(uri, mimeType, mediaStore, site.id)
        if (media == null) {
            _snackBarMessages.value = SnackbarMessageHolder(R.string.file_not_found)
            return
        }
        if (localPostId != EMPTY_LOCAL_POST_ID) {
            media.localPostId = localPostId
        } else {
            AppLog.e(T.MEDIA, "Upload featured image can't be invoked without a valid local post id.")
        }
        media.markedLocallyAsFeatured = true

        dispatcher.dispatch(MediaActionBuilder.newUpdateMediaAction(media))
        startUploadService(media)
    }

    fun cancelFeaturedImageUpload(site: SiteModel, post: PostModel, cancelFailedOnly: Boolean) {
        var mediaModel: MediaModel? = getFailedFeaturedImageUpload(post)
        if (!cancelFailedOnly && mediaModel == null) {
            mediaModel = uploadServiceFacade.getPendingOrInProgressFeaturedImageUploadForPost(post)
        }
        if (mediaModel != null) {
            val payload = CancelMediaPayload(site, mediaModel, true)
            dispatcher.dispatch(MediaActionBuilder.newCancelMediaUploadAction(payload))
            cancelNotifications(post, site)
        }
    }

    private fun cancelNotifications(post: PostModel, site: SiteModel) {
        uploadServiceFacade.cancelFinalNotification(post)
        uploadServiceFacade.cancelFinalNotificationForMedia(site)
    }

    fun createCurrentFeaturedImageState(site: SiteModel, post: PostModel, maxDimen: Int): FeaturedImageData {
        var uploadModel: MediaModel? = uploadServiceFacade.getPendingOrInProgressFeaturedImageUploadForPost(post)
        if (uploadModel != null) {
            return FeaturedImageData(FeaturedImageState.IMAGE_UPLOAD_IN_PROGRESS, uploadModel.filePath)
        }
        uploadModel = getFailedFeaturedImageUpload(post)
        if (uploadModel != null) {
            return FeaturedImageData(FeaturedImageState.IMAGE_UPLOAD_FAILED, uploadModel.filePath)
        }
        if (!post.hasFeaturedImage()) {
            return FeaturedImageData(FeaturedImageState.IMAGE_EMPTY, null)
        }

        val media = mediaStore.getSiteMediaWithId(site, post.featuredImageId) ?: return FeaturedImageData(
                FeaturedImageState.IMAGE_EMPTY,
                null
        )

        val mediaUri = StringUtils.notNullStr(media.thumbnailUrl)
        val photonUrl = readerUtilsWrapper
                .getResizedImageUrl(mediaUri, maxDimen, maxDimen, !SiteUtils.isPhotonCapable(site))
        return FeaturedImageData(FeaturedImageState.REMOTE_IMAGE_LOADING, photonUrl)
    }

    internal data class FeaturedImageData(val uiState: FeaturedImageState, val mediaUri: String?)

    internal enum class FeaturedImageState(
        val buttonVisible: Boolean = false,
        val imageViewVisible: Boolean = false,
        val localImageViewVisible: Boolean = false,
        val progressOverlayVisible: Boolean = false,
        val retryOverlayVisible: Boolean = false
    ) {
        IMAGE_EMPTY(buttonVisible = true),
        REMOTE_IMAGE_LOADING(localImageViewVisible = true, imageViewVisible = true),
        REMOTE_IMAGE_SET(imageViewVisible = true),
        IMAGE_UPLOAD_IN_PROGRESS(localImageViewVisible = true, progressOverlayVisible = true),
        IMAGE_UPLOAD_FAILED(localImageViewVisible = true, retryOverlayVisible = true);
    }
}
