package com.samluiz.gyst.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.samluiz.gyst.domain.service.ImageSourceCapabilities
import com.samluiz.gyst.domain.service.ImageSourceFailure
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.ImageSourceService
import com.samluiz.gyst.domain.service.MAX_TEMPORARY_IMAGE_BATCH_BYTES
import com.samluiz.gyst.domain.service.TemporaryImageHandle
import com.samluiz.gyst.domain.service.canonicalImageMimeType
import com.samluiz.gyst.domain.service.detectedImageMimeType
import com.samluiz.gyst.domain.service.imageFileExtension
import com.samluiz.gyst.domain.service.isTemporaryImageExpired
import com.samluiz.gyst.domain.service.isTemporaryImageSizeAllowed
import com.samluiz.gyst.domain.service.sanitizedImageDisplayName
import com.samluiz.gyst.domain.service.temporaryImageSha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class AndroidImageSourceService(
    context: Context,
) : ImageSourceService {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, CACHE_DIRECTORY_NAME)
    private val recoveryPreferences =
        appContext.getSharedPreferences(RECOVERY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Any()

    private var selectLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var captureLauncher: ActivityResultLauncher<Uri>? = null
    private var pendingOperation: PendingOperation? = null
    private val selectCallbackGate = SelectCallbackGate()

    init {
        serviceScope.launch { cleanupExpired() }
    }

    override val capabilities =
        ImageSourceCapabilities(
            canSelectImages = true,
            canCaptureImage = true,
            maximumSelection = MAX_SELECTION,
        )

    /** Must be called from MainActivity.onCreate before the activity reaches STARTED. */
    fun bind(activity: ComponentActivity) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Image source binding must run on the main thread." }
        selectLauncher?.unregister()
        captureLauncher?.unregister()
        selectLauncher =
            activity.activityResultRegistry.register(
                SELECT_REGISTRY_KEY,
                activity,
                ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTION),
            ) { uris ->
                onImagesSelected(uris)
            }
        captureLauncher =
            activity.activityResultRegistry.register(
                CAPTURE_REGISTRY_KEY,
                activity,
                ActivityResultContracts.TakePicture(),
            ) { saved ->
                onImageCaptured(saved)
            }
    }

    override suspend fun selectImages(): ImageSourceResult {
        val operation = PendingOperation.Select(CompletableDeferred())
        val launcher =
            synchronized(operationLock) {
                if (pendingOperation != null || !selectCallbackGate.canLaunch()) {
                    return ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
                }
                selectLauncher ?: return ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)
                pendingOperation = operation
                selectLauncher
            }
        val launched =
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    launcher?.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.isSuccess
            }
        if (!launched) {
            finish(operation, ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE))
        }
        return awaitOperation(operation)
    }

    override suspend fun captureImage(): ImageSourceResult {
        val target =
            withContext(Dispatchers.IO) { runCatching { newCaptureTarget() } }
                .getOrElse { return ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE) }
        val operation = PendingOperation.Capture(CompletableDeferred(), target)
        val launcher =
            synchronized(operationLock) {
                if (pendingOperation != null) {
                    target.file.delete()
                    return ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
                }
                captureLauncher ?: run {
                    target.file.delete()
                    return ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)
                }
                pendingOperation = operation
                captureLauncher
            }
        val targetPersisted = withContext(Dispatchers.IO) { persistPendingCaptureTarget(target) }
        if (!targetPersisted) {
            target.file.delete()
            finish(operation, ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE))
            return awaitOperation(operation)
        }
        val uri =
            runCatching {
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    target.file,
                )
            }.getOrElse {
                target.file.delete()
                finish(operation, ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE))
                return operation.result.await()
            }
        val launched = withContext(Dispatchers.Main.immediate) { runCatching { launcher?.launch(uri) }.isSuccess }
        if (!launched) {
            target.file.delete()
            finish(operation, ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE))
        }
        return awaitOperation(operation)
    }

    override suspend fun readBytes(handle: TemporaryImageHandle): ByteArray =
        withContext(Dispatchers.IO) {
            val file = resolveOwnedFile(handle.temporaryReference)
            require(file.isFile) { "Temporary image is no longer available." }
            require(file.length() == handle.byteSize && isTemporaryImageSizeAllowed(file.length())) {
                "Temporary image size changed unexpectedly."
            }
            val bytes = file.readBytes()
            require(detectedImageMimeType(bytes) == canonicalImageMimeType(handle.mimeType)) {
                "Temporary image content is invalid."
            }
            require(temporaryImageSha256(bytes) == handle.sha256) { "Temporary image integrity check failed." }
            file.setLastModified(System.currentTimeMillis())
            bytes
        }

    override suspend fun restoreAvailable(handles: Collection<TemporaryImageHandle>): List<TemporaryImageHandle> =
        withContext(Dispatchers.IO) {
            handles.filter { handle ->
                runCatching {
                    val file = resolveOwnedFile(handle.temporaryReference)
                    file.isFile &&
                        file.length() == handle.byteSize &&
                        isTemporaryImageSizeAllowed(file.length()) &&
                        !isTemporaryImageExpired(file.lastModified(), System.currentTimeMillis())
                }.getOrDefault(false)
            }
        }

    override suspend fun pendingRecoveredImages(): List<TemporaryImageHandle> =
        withContext(Dispatchers.IO) {
            recoverCompletedCaptureIfPresent()
            synchronized(operationLock) {
                val stored = readRecoveredImages()
                val available = stored.filter(::isAvailable)
                if (available.size != stored.size) writeRecoveredImages(available)
                available
            }
        }

    override suspend fun acknowledgeRecoveredImages(handles: Collection<TemporaryImageHandle>) {
        withContext(Dispatchers.IO) {
            val references = handles.mapTo(mutableSetOf(), TemporaryImageHandle::temporaryReference)
            synchronized(operationLock) {
                val remaining = readRecoveredImages().filterNot { it.temporaryReference in references }
                check(writeRecoveredImages(remaining)) { "Unable to acknowledge recovered image custody." }
            }
        }
    }

    override suspend fun cleanup(handles: Collection<TemporaryImageHandle>) {
        withContext(Dispatchers.IO) {
            handles.forEach { handle -> runCatching { resolveOwnedFile(handle.temporaryReference).delete() } }
        }
    }

    override suspend fun cleanupExpired() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            cacheDirectory.listFiles()?.forEach { file ->
                if (file.isFile && isTemporaryImageExpired(file.lastModified(), now)) {
                    runCatching { file.delete() }
                }
            }
            val pendingCapture = readPendingCaptureTarget()
            if (pendingCapture != null && !pendingCapture.file.isFile) {
                clearPendingCaptureTarget(pendingCapture.id)
            }
        }
    }

    private fun onImagesSelected(uris: List<Uri>) {
        var cancelledCallback = false
        val operation =
            synchronized(operationLock) {
                cancelledCallback = selectCallbackGate.consumeCancelledCallback()
                val current = pendingOperation as? PendingOperation.Select
                current
            }
        if (cancelledCallback) return
        if (operation == null) {
            if (uris.isNotEmpty()) {
                serviceScope.launch { queueRecoveredResult(importUris(uris.take(MAX_SELECTION))) }
            }
            return
        }
        if (uris.isEmpty()) {
            finish(operation, ImageSourceResult.Cancelled)
            return
        }
        launchProcessing(operation) { importUris(uris.take(MAX_SELECTION)) }
    }

    private fun onImageCaptured(saved: Boolean) {
        val operation = synchronized(operationLock) { pendingOperation as? PendingOperation.Capture }
        if (operation == null) {
            val recoveredTarget = synchronized(operationLock) { readPendingCaptureTarget() } ?: return
            if (!saved) {
                recoveredTarget.file.delete()
                clearPendingCaptureTarget(recoveredTarget.id)
                return
            }
            serviceScope.launch {
                val queued =
                    queueRecoveredResult(
                        importCapturedFile(recoveredTarget.file, recoveredTarget.id, recoveredTarget.displayName),
                    )
                if (queued is ImageSourceResult.Selected || !recoveredTarget.file.isFile) {
                    clearPendingCaptureTarget(recoveredTarget.id)
                }
            }
            return
        }
        if (!saved) {
            operation.target.file.delete()
            finish(operation, ImageSourceResult.Cancelled)
            return
        }
        launchProcessing(operation) {
            importCapturedFile(operation.target.file, operation.target.id, operation.target.displayName)
        }
    }

    private fun launchProcessing(
        operation: PendingOperation,
        process: suspend () -> ImageSourceResult,
    ) {
        val job =
            serviceScope.launch(start = CoroutineStart.LAZY) {
                var result: ImageSourceResult? = null
                var recoveryCustodyTransferred = false
                try {
                    result = process()
                    currentCoroutineContext().ensureActive()
                    val queued = queueRecoveredResult(result)
                    recoveryCustodyTransferred = queued is ImageSourceResult.Selected
                    finish(operation, queued)
                } catch (cancelled: CancellationException) {
                    if (!recoveryCustodyTransferred) cleanupSelectedResult(result)
                    throw cancelled
                }
            }
        val accepted =
            synchronized(operationLock) {
                if (pendingOperation === operation) {
                    operation.processingJob = job
                    true
                } else {
                    false
                }
            }
        if (accepted) job.start() else job.cancel()
    }

    internal suspend fun importUris(uris: List<Uri>): ImageSourceResult {
        val imported = mutableListOf<TemporaryImageHandle>()
        return try {
            var batchBytes = 0L
            uris.forEach { uri ->
                val metadata = queryMetadata(uri)
                val prepared = prepareImage(uri, metadata)
                val bytes = prepared.bytes
                try {
                    batchBytes += bytes.size
                    if (batchBytes > MAX_TEMPORARY_IMAGE_BATCH_BYTES) {
                        throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
                    }
                    val id = UUID.randomUUID().toString()
                    val target = File(ensureCacheDirectory(), "$id.${imageFileExtension(prepared.mimeType)}")
                    val handle =
                        TemporaryImageHandle(
                            id = id,
                            displayName = sanitizedImageDisplayName(metadata.displayName),
                            mimeType = prepared.mimeType,
                            byteSize = bytes.size.toLong(),
                            sha256 = temporaryImageSha256(bytes),
                            temporaryReference = target.name,
                        )
                    imported += handle
                    target.writeBytes(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            ImageSourceResult.Selected(imported)
        } catch (error: ImageCustodyException) {
            cleanupImported(imported)
            ImageSourceResult.Failed(error.failure)
        } catch (_: CancellationException) {
            cleanupImported(imported)
            ImageSourceResult.Cancelled
        } catch (_: SecurityException) {
            cleanupImported(imported)
            ImageSourceResult.Failed(ImageSourceFailure.PERMISSION_DENIED)
        } catch (_: Throwable) {
            cleanupImported(imported)
            ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
        }
    }

    private suspend fun prepareImage(
        uri: Uri,
        metadata: SourceMetadata,
    ): PreparedImage {
        if (metadata.declaredSize != null && metadata.declaredSize > MAX_TRANSCODE_SOURCE_BYTES) {
            throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
        }
        val stagingFile = File(ensureCacheDirectory(), "${UUID.randomUUID()}.source")
        return try {
            copyUriToStagingFile(uri, stagingFile)
            prepareFile(stagingFile, metadata.mimeType)
        } finally {
            stagingFile.delete()
        }
    }

    private suspend fun copyUriToStagingFile(
        uri: Uri,
        stagingFile: File,
    ) {
        val input =
            appContext.contentResolver.openInputStream(uri)
                ?: throw ImageCustodyException(ImageSourceFailure.IO_FAILURE)
        input.use { source ->
            stagingFile.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_TRANSCODE_SOURCE_BYTES) {
                        throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
                    }
                    target.write(buffer, 0, count)
                }
                if (total <= 0L) throw ImageCustodyException(ImageSourceFailure.IO_FAILURE)
            }
        }
    }

    private fun prepareFile(
        file: File,
        declaredMimeType: String?,
    ): PreparedImage {
        if (file.length() !in 1..MAX_TRANSCODE_SOURCE_BYTES) {
            throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
        }
        val dimensions = probeDimensions(file)
        dimensions?.requireSafeSourceDimensions()
        if (
            isTemporaryImageSizeAllowed(file.length()) &&
            dimensions != null &&
            maxOf(dimensions.width, dimensions.height) <= NORMALIZED_IMAGE_MAX_DIMENSION
        ) {
            val original = file.readBytes()
            try {
                val mimeType = validatedMimeType(declaredMimeType, original)
                return PreparedImage(mimeType = mimeType, bytes = original)
            } catch (error: ImageCustodyException) {
                original.fill(0)
                if (error.failure != ImageSourceFailure.UNSUPPORTED_FORMAT) throw error
            }
        }
        return transcodeToJpeg(file)
    }

    private fun transcodeToJpeg(file: File): PreparedImage {
        val bitmap =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeModernBitmap(file)
                } else {
                    decodeLegacyBitmap(file)
                }
            } catch (error: ImageCustodyException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: throw ImageCustodyException(ImageSourceFailure.UNSUPPORTED_FORMAT)
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, NORMALIZED_JPEG_QUALITY, output)) {
                throw ImageCustodyException(ImageSourceFailure.IO_FAILURE)
            }
            val bytes = output.toByteArray()
            if (!isTemporaryImageSizeAllowed(bytes.size.toLong())) {
                bytes.fill(0)
                throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
            }
            PreparedImage(mimeType = "image/jpeg", bytes = bytes)
        } finally {
            bitmap.recycle()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModernBitmap(file: File): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            ImageDimensions(width, height).requireSafeSourceDimensions()
            val scale = minOf(1f, NORMALIZED_IMAGE_MAX_DIMENSION.toFloat() / maxOf(width, height))
            if (scale < 1f) {
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        }
    }

    private fun decodeLegacyBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        ImageDimensions(bounds.outWidth, bounds.outHeight).requireSafeSourceDimensions()
        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > NORMALIZED_IMAGE_MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return applyExifOrientation(file, decoded)
    }

    internal fun applyExifOrientation(
        file: File,
        bitmap: Bitmap,
    ): Bitmap {
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return bitmap
        val rotation = exif.rotationDegrees.toFloat()
        val flipped = exif.isFlipped
        if (rotation == 0f && !flipped) return bitmap
        val matrix =
            Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                if (rotation != 0f) postRotate(rotation)
            }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { transformed ->
            if (transformed !== bitmap) bitmap.recycle()
        }
    }

    private fun probeDimensions(file: File): ImageDimensions? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            ImageDimensions(bounds.outWidth, bounds.outHeight)
        } else {
            null
        }
    }

    private fun ImageDimensions.requireSafeSourceDimensions() {
        val pixels = width.toLong() * height.toLong()
        if (
            width <= 0 ||
            height <= 0 ||
            width > MAX_SOURCE_DIMENSION ||
            height > MAX_SOURCE_DIMENSION ||
            pixels > MAX_SOURCE_PIXEL_COUNT
        ) {
            throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
        }
    }

    internal suspend fun importCapturedFile(
        file: File,
        id: String,
        displayName: String,
    ): ImageSourceResult =
        try {
            val prepared = prepareFile(file, "image/jpeg")
            val bytes = prepared.bytes
            try {
                currentCoroutineContext().ensureActive()
                file.writeBytes(bytes)
                ImageSourceResult.Selected(
                    listOf(
                        TemporaryImageHandle(
                            id = id,
                            displayName = displayName,
                            mimeType = prepared.mimeType,
                            byteSize = bytes.size.toLong(),
                            sha256 = temporaryImageSha256(bytes),
                            temporaryReference = file.name,
                        ),
                    ),
                )
            } finally {
                bytes.fill(0)
            }
        } catch (error: ImageCustodyException) {
            file.delete()
            ImageSourceResult.Failed(error.failure)
        } catch (_: CancellationException) {
            file.delete()
            ImageSourceResult.Cancelled
        } catch (_: Throwable) {
            file.delete()
            ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
        }

    private fun validatedMimeType(
        declaredMimeType: String?,
        bytes: ByteArray,
    ): String {
        val canonicalDeclared = canonicalImageMimeType(declaredMimeType)
        val detected =
            detectedImageMimeType(bytes)
                ?: throw ImageCustodyException(ImageSourceFailure.UNSUPPORTED_FORMAT)
        if (declaredMimeType?.startsWith("image/", ignoreCase = true) == true && canonicalDeclared == null) {
            throw ImageCustodyException(ImageSourceFailure.UNSUPPORTED_FORMAT)
        }
        if (canonicalDeclared != null && canonicalDeclared != detected) {
            throw ImageCustodyException(ImageSourceFailure.UNSUPPORTED_FORMAT)
        }
        return detected
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var declaredSize: Long? = null
        appContext.contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        return SourceMetadata(
            displayName = displayName,
            declaredSize = declaredSize,
            mimeType = appContext.contentResolver.getType(uri),
        )
    }

    private fun newCaptureTarget(): CaptureTarget {
        val id = UUID.randomUUID().toString()
        val file = File(ensureCacheDirectory(), "$id.jpg")
        check(file.createNewFile()) { "Unable to reserve temporary camera output." }
        return CaptureTarget(id = id, displayName = "camera-$id.jpg", file = file)
    }

    private fun ensureCacheDirectory(): File =
        cacheDirectory.apply {
            check(exists() || mkdirs()) { "Unable to create the image-import cache." }
        }

    private fun resolveOwnedFile(reference: String): File {
        require(reference.isNotBlank() && reference == File(reference).name) { "Invalid temporary image reference." }
        val root = ensureCacheDirectory().canonicalFile
        val resolved = File(root, reference).canonicalFile
        require(resolved.parentFile == root) { "Temporary image is outside the managed cache." }
        return resolved
    }

    private fun cleanupImported(imported: List<TemporaryImageHandle>) {
        imported.forEach { handle -> runCatching { resolveOwnedFile(handle.temporaryReference).delete() } }
    }

    private fun cleanupSelectedResult(result: ImageSourceResult?) {
        if (result is ImageSourceResult.Selected) cleanupImported(result.images)
    }

    private fun finish(
        operation: PendingOperation,
        result: ImageSourceResult,
    ) {
        var accepted = false
        synchronized(operationLock) {
            if (pendingOperation === operation) {
                pendingOperation = null
                accepted = true
            }
        }
        if (!accepted) {
            // Successful results already have durable recovery custody and are consumed on resume.
            return
        }
        (operation as? PendingOperation.Capture)?.let { capture ->
            if (result is ImageSourceResult.Selected || !capture.target.file.isFile) {
                clearPendingCaptureTarget(capture.target.id)
            }
        }
        operation.result.complete(result)
    }

    private suspend fun awaitOperation(operation: PendingOperation): ImageSourceResult =
        try {
            operation.result.await()
        } catch (error: CancellationException) {
            var processingJob: Job? = null
            synchronized(operationLock) {
                if (pendingOperation === operation) {
                    pendingOperation = null
                    processingJob = operation.processingJob
                    if (operation is PendingOperation.Select) {
                        selectCallbackGate.onCancelled(callbackReceived = processingJob != null)
                    }
                }
            }
            processingJob?.cancel(error)
            (operation as? PendingOperation.Capture)?.let { capture ->
                capture.target.file.delete()
                clearPendingCaptureTarget(capture.target.id)
            }
            operation.result.cancel(error)
            throw error
        }

    internal fun queueRecoveredResult(result: ImageSourceResult): ImageSourceResult {
        if (result !is ImageSourceResult.Selected) return result
        var delivered = emptyList<TemporaryImageHandle>()
        var discarded = emptyList<TemporaryImageHandle>()
        val persisted =
            synchronized(operationLock) {
                val combined = (readRecoveredImages() + result.images).distinctBy { it.sha256 }.take(MAX_SELECTION)
                delivered =
                    result.images.mapNotNull { incoming -> combined.firstOrNull { it.sha256 == incoming.sha256 } }
                        .distinctBy { it.id }
                discarded = result.images.filter { incoming -> combined.none { it.id == incoming.id } }
                writeRecoveredImages(combined)
            }
        if (!persisted) {
            cleanupImported(result.images)
            return ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
        }
        cleanupImported(discarded)
        return if (delivered.isEmpty()) {
            ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
        } else {
            ImageSourceResult.Selected(delivered)
        }
    }

    private suspend fun recoverCompletedCaptureIfPresent() {
        val target = synchronized(operationLock) { readPendingCaptureTarget() } ?: return
        if (!target.file.isFile || target.file.length() <= 0L) return
        val queued = queueRecoveredResult(importCapturedFile(target.file, target.id, target.displayName))
        if (queued is ImageSourceResult.Selected || !target.file.isFile) {
            clearPendingCaptureTarget(target.id)
        }
    }

    private fun isAvailable(handle: TemporaryImageHandle): Boolean =
        runCatching {
            val file = resolveOwnedFile(handle.temporaryReference)
            file.isFile &&
                file.length() == handle.byteSize &&
                isTemporaryImageSizeAllowed(file.length()) &&
                !isTemporaryImageExpired(file.lastModified(), System.currentTimeMillis())
        }.getOrDefault(false)

    private fun readRecoveredImages(): List<TemporaryImageHandle> {
        val encoded = recoveryPreferences.getString(RECOVERED_IMAGES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val handle =
                        TemporaryImageHandle(
                            id = item.getString(JSON_ID),
                            displayName = item.getString(JSON_DISPLAY_NAME),
                            mimeType = item.getString(JSON_MIME_TYPE),
                            byteSize = item.getLong(JSON_BYTE_SIZE),
                            sha256 = item.getString(JSON_SHA256),
                            temporaryReference = item.getString(JSON_TEMPORARY_REFERENCE),
                        )
                    if (
                        handle.id.isNotBlank() &&
                        handle.byteSize > 0 &&
                        handle.sha256.isNotBlank() &&
                        handle.temporaryReference == File(handle.temporaryReference).name
                    ) {
                        add(handle)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRecoveredImages(images: List<TemporaryImageHandle>): Boolean {
        val editor = recoveryPreferences.edit()
        if (images.isEmpty()) return editor.remove(RECOVERED_IMAGES_KEY).commit()
        val encoded =
            JSONArray().apply {
                images.forEach { handle ->
                    put(
                        JSONObject()
                            .put(JSON_ID, handle.id)
                            .put(JSON_DISPLAY_NAME, handle.displayName)
                            .put(JSON_MIME_TYPE, handle.mimeType)
                            .put(JSON_BYTE_SIZE, handle.byteSize)
                            .put(JSON_SHA256, handle.sha256)
                            .put(JSON_TEMPORARY_REFERENCE, handle.temporaryReference),
                    )
                }
            }.toString()
        return editor.putString(RECOVERED_IMAGES_KEY, encoded).commit()
    }

    private fun persistPendingCaptureTarget(target: CaptureTarget): Boolean {
        val encoded =
            JSONObject()
                .put(JSON_ID, target.id)
                .put(JSON_DISPLAY_NAME, target.displayName)
                .put(JSON_TEMPORARY_REFERENCE, target.file.name)
                .toString()
        return recoveryPreferences.edit().putString(PENDING_CAPTURE_KEY, encoded).commit()
    }

    private fun readPendingCaptureTarget(): CaptureTarget? {
        val encoded = recoveryPreferences.getString(PENDING_CAPTURE_KEY, null) ?: return null
        return runCatching {
            val item = JSONObject(encoded)
            val reference = item.getString(JSON_TEMPORARY_REFERENCE)
            CaptureTarget(
                id = item.getString(JSON_ID),
                displayName = item.getString(JSON_DISPLAY_NAME),
                file = resolveOwnedFile(reference),
            )
        }.getOrNull()
    }

    private fun clearPendingCaptureTarget(id: String) {
        val current = readPendingCaptureTarget() ?: return
        if (current.id == id) recoveryPreferences.edit().remove(PENDING_CAPTURE_KEY).apply()
    }

    private sealed interface PendingOperation {
        val result: CompletableDeferred<ImageSourceResult>
        var processingJob: Job?

        data class Select(
            override val result: CompletableDeferred<ImageSourceResult>,
            override var processingJob: Job? = null,
        ) : PendingOperation

        data class Capture(
            override val result: CompletableDeferred<ImageSourceResult>,
            val target: CaptureTarget,
            override var processingJob: Job? = null,
        ) : PendingOperation
    }

    internal class SelectCallbackGate {
        private var awaitingCancelledCallback = false

        fun canLaunch(): Boolean = !awaitingCancelledCallback

        fun onCancelled(callbackReceived: Boolean) {
            if (!callbackReceived) awaitingCancelledCallback = true
        }

        fun consumeCancelledCallback(): Boolean {
            if (!awaitingCancelledCallback) return false
            awaitingCancelledCallback = false
            return true
        }
    }

    private data class CaptureTarget(
        val id: String,
        val displayName: String,
        val file: File,
    )

    private data class SourceMetadata(
        val displayName: String?,
        val declaredSize: Long?,
        val mimeType: String?,
    )

    private data class PreparedImage(
        val mimeType: String,
        val bytes: ByteArray,
    )

    private data class ImageDimensions(
        val width: Int,
        val height: Int,
    )

    private class ImageCustodyException(
        val failure: ImageSourceFailure,
    ) : RuntimeException()

    internal companion object {
        const val MAX_SELECTION = 10
        const val CACHE_DIRECTORY_NAME = "transaction-import-images"
        const val SELECT_REGISTRY_KEY = "gyst.transaction-import.select-images"
        const val CAPTURE_REGISTRY_KEY = "gyst.transaction-import.capture-image"
        const val NORMALIZED_IMAGE_MAX_DIMENSION = 2_048
        const val NORMALIZED_JPEG_QUALITY = 88
        const val MAX_TRANSCODE_SOURCE_BYTES = 40L * 1024L * 1024L
        const val MAX_SOURCE_DIMENSION = 32_768
        const val MAX_SOURCE_PIXEL_COUNT = 100_000_000L
        const val RECOVERY_PREFERENCES_NAME = "gyst.image-import.activity-result-recovery"
        const val RECOVERED_IMAGES_KEY = "recovered-images-v1"
        const val PENDING_CAPTURE_KEY = "pending-capture-v1"
        const val JSON_ID = "id"
        const val JSON_DISPLAY_NAME = "displayName"
        const val JSON_MIME_TYPE = "mimeType"
        const val JSON_BYTE_SIZE = "byteSize"
        const val JSON_SHA256 = "sha256"
        const val JSON_TEMPORARY_REFERENCE = "temporaryReference"
    }
}
