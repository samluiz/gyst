package com.samluiz.gyst.domain.service

data class ImageSourceCapabilities(
    val canSelectImages: Boolean,
    val canCaptureImage: Boolean,
    val maximumSelection: Int = 10,
)

data class TemporaryImageHandle(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val temporaryReference: String,
)

sealed interface ImageSourceResult {
    data class Selected(val images: List<TemporaryImageHandle>) : ImageSourceResult

    data object Cancelled : ImageSourceResult

    data class Failed(val reason: ImageSourceFailure) : ImageSourceResult
}

enum class ImageSourceFailure {
    UNSUPPORTED,
    PERMISSION_DENIED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    IO_FAILURE,
}

interface ImageSourceService {
    val capabilities: ImageSourceCapabilities

    suspend fun selectImages(): ImageSourceResult

    suspend fun captureImage(): ImageSourceResult

    suspend fun readBytes(handle: TemporaryImageHandle): ByteArray

    /**
     * Returns the subset of persisted temporary handles that still belong to this service and are
     * locally available after process recreation. Full content integrity is verified by [readBytes]
     * immediately before a provider request.
     */
    suspend fun restoreAvailable(handles: Collection<TemporaryImageHandle>): List<TemporaryImageHandle>

    /**
     * Returns image picker/camera results that reached the platform after their original caller was
     * recreated. Results remain queued until [acknowledgeRecoveredImages] is called.
     */
    suspend fun pendingRecoveredImages(): List<TemporaryImageHandle> = emptyList()

    /** Acknowledges custody only after the image-import database draft was persisted. */
    suspend fun acknowledgeRecoveredImages(handles: Collection<TemporaryImageHandle>) = Unit

    suspend fun cleanup(handles: Collection<TemporaryImageHandle>)

    suspend fun cleanupExpired()
}

class UnsupportedImageSourceService : ImageSourceService {
    override val capabilities = ImageSourceCapabilities(canSelectImages = false, canCaptureImage = false)

    override suspend fun selectImages(): ImageSourceResult = ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)

    override suspend fun captureImage(): ImageSourceResult = ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)

    override suspend fun readBytes(handle: TemporaryImageHandle): ByteArray = error("Image input is not supported on this platform.")

    override suspend fun restoreAvailable(handles: Collection<TemporaryImageHandle>): List<TemporaryImageHandle> = emptyList()

    override suspend fun cleanup(handles: Collection<TemporaryImageHandle>) = Unit

    override suspend fun cleanupExpired() = Unit
}
