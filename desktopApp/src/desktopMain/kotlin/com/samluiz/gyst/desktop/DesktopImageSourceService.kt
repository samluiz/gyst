package com.samluiz.gyst.desktop

import com.samluiz.gyst.domain.service.ImageSourceCapabilities
import com.samluiz.gyst.domain.service.ImageSourceFailure
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.ImageSourceService
import com.samluiz.gyst.domain.service.MAX_TEMPORARY_IMAGE_BATCH_BYTES
import com.samluiz.gyst.domain.service.MAX_TEMPORARY_IMAGE_BYTES
import com.samluiz.gyst.domain.service.TemporaryImageHandle
import com.samluiz.gyst.domain.service.canonicalImageMimeType
import com.samluiz.gyst.domain.service.detectedImageMimeType
import com.samluiz.gyst.domain.service.imageFileExtension
import com.samluiz.gyst.domain.service.isTemporaryImageExpired
import com.samluiz.gyst.domain.service.isTemporaryImageSizeAllowed
import com.samluiz.gyst.domain.service.sanitizedImageDisplayName
import com.samluiz.gyst.domain.service.temporaryImageSha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

class DesktopImageSourceService(
    private val cacheDirectory: Path,
) : ImageSourceService {
    private val selectionMutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        serviceScope.launch { cleanupExpired() }
    }

    override val capabilities =
        ImageSourceCapabilities(
            canSelectImages = !GraphicsEnvironment.isHeadless(),
            canCaptureImage = false,
            maximumSelection = MAX_SELECTION,
        )

    override suspend fun selectImages(): ImageSourceResult =
        selectionMutex.withLock {
            if (!capabilities.canSelectImages) {
                return@withLock ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)
            }
            val selected = chooseFiles() ?: return@withLock ImageSourceResult.Cancelled
            if (selected.isEmpty()) return@withLock ImageSourceResult.Cancelled
            importFiles(selected.take(MAX_SELECTION))
        }

    override suspend fun captureImage(): ImageSourceResult = ImageSourceResult.Failed(ImageSourceFailure.UNSUPPORTED)

    override suspend fun readBytes(handle: TemporaryImageHandle): ByteArray =
        withContext(Dispatchers.IO) {
            val path = resolveOwnedFile(handle.temporaryReference)
            require(Files.isRegularFile(path)) { "Temporary image is no longer available." }
            val size = Files.size(path)
            require(size == handle.byteSize && isTemporaryImageSizeAllowed(size)) {
                "Temporary image size changed unexpectedly."
            }
            val bytes = Files.readAllBytes(path)
            require(detectedImageMimeType(bytes) == canonicalImageMimeType(handle.mimeType)) {
                "Temporary image content is invalid."
            }
            require(temporaryImageSha256(bytes) == handle.sha256) { "Temporary image integrity check failed." }
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            bytes
        }

    override suspend fun restoreAvailable(handles: Collection<TemporaryImageHandle>): List<TemporaryImageHandle> =
        withContext(Dispatchers.IO) {
            handles.filter { handle ->
                runCatching {
                    val path = resolveOwnedFile(handle.temporaryReference)
                    val modifiedAt = Files.getLastModifiedTime(path).toMillis()
                    Files.isRegularFile(path) &&
                        Files.size(path) == handle.byteSize &&
                        isTemporaryImageSizeAllowed(Files.size(path)) &&
                        !isTemporaryImageExpired(modifiedAt, System.currentTimeMillis())
                }.getOrDefault(false)
            }
        }

    override suspend fun cleanup(handles: Collection<TemporaryImageHandle>) {
        withContext(Dispatchers.IO) {
            handles.forEach { handle -> runCatching { Files.deleteIfExists(resolveOwnedFile(handle.temporaryReference)) } }
        }
    }

    override suspend fun cleanupExpired() {
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(cacheDirectory)) return@withContext
            val now = System.currentTimeMillis()
            Files.list(cacheDirectory).use { paths ->
                paths.filter { path -> Files.isRegularFile(path) }.forEach { path ->
                    val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
                    if (modified != null && isTemporaryImageExpired(modified, now)) {
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }
    }

    private suspend fun chooseFiles(): List<File>? =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                if (!continuation.isActive) return@invokeLater
                val chooser =
                    JFileChooser().apply {
                        isMultiSelectionEnabled = true
                        isAcceptAllFileFilterUsed = false
                        fileFilter =
                            FileNameExtensionFilter(
                                "JPEG, PNG, WebP, GIF",
                                "jpg",
                                "jpeg",
                                "png",
                                "webp",
                                "gif",
                            )
                    }
                continuation.invokeOnCancellation {
                    SwingUtilities.invokeLater { chooser.cancelSelection() }
                }
                val result = chooser.showOpenDialog(null)
                if (continuation.isActive) {
                    continuation.resume(
                        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else null,
                    )
                }
            }
        }

    private suspend fun importFiles(files: List<File>): ImageSourceResult =
        withContext(Dispatchers.IO) {
            val imported = mutableListOf<TemporaryImageHandle>()
            try {
                var batchBytes = 0L
                files.forEach { source ->
                    ensureActive()
                    if (!source.isFile || !isTemporaryImageSizeAllowed(source.length())) {
                        throw ImageCustodyException(
                            if (source.isFile) ImageSourceFailure.FILE_TOO_LARGE else ImageSourceFailure.IO_FAILURE,
                        )
                    }
                    val bytes = readBounded(source)
                    try {
                        batchBytes += bytes.size
                        if (batchBytes > MAX_TEMPORARY_IMAGE_BATCH_BYTES) {
                            throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
                        }
                        val declaredMimeType = runCatching { Files.probeContentType(source.toPath()) }.getOrNull()
                        val mimeType = validatedMimeType(declaredMimeType, bytes)
                        val id = UUID.randomUUID().toString()
                        val target = ensureCacheDirectory().resolve("$id.${imageFileExtension(mimeType)}")
                        val handle =
                            TemporaryImageHandle(
                                id = id,
                                displayName = sanitizedImageDisplayName(source.name),
                                mimeType = mimeType,
                                byteSize = bytes.size.toLong(),
                                sha256 = temporaryImageSha256(bytes),
                                temporaryReference = target.fileName.toString(),
                            )
                        imported += handle
                        Files.write(target, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
                ImageSourceResult.Selected(imported)
            } catch (error: ImageCustodyException) {
                cleanupImported(imported)
                ImageSourceResult.Failed(error.failure)
            } catch (error: CancellationException) {
                cleanupImported(imported)
                throw error
            } catch (_: Throwable) {
                cleanupImported(imported)
                ImageSourceResult.Failed(ImageSourceFailure.IO_FAILURE)
            }
        }

    private suspend fun readBounded(source: File): ByteArray {
        val output = ByteArrayOutputStream()
        source.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_TEMPORARY_IMAGE_BYTES) {
                    throw ImageCustodyException(ImageSourceFailure.FILE_TOO_LARGE)
                }
                output.write(buffer, 0, count)
            }
            if (!isTemporaryImageSizeAllowed(total)) {
                throw ImageCustodyException(ImageSourceFailure.IO_FAILURE)
            }
        }
        return output.toByteArray()
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

    private fun ensureCacheDirectory(): Path = cacheDirectory.also { path -> Files.createDirectories(path) }

    private fun resolveOwnedFile(reference: String): Path {
        require(reference.isNotBlank() && reference == Path.of(reference).fileName.toString()) {
            "Invalid temporary image reference."
        }
        val root = ensureCacheDirectory().toAbsolutePath().normalize()
        val resolved = root.resolve(reference).normalize()
        require(resolved.parent == root) { "Temporary image is outside the managed cache." }
        return resolved
    }

    private fun cleanupImported(imported: List<TemporaryImageHandle>) {
        imported.forEach { handle -> runCatching { Files.deleteIfExists(resolveOwnedFile(handle.temporaryReference)) } }
    }

    private class ImageCustodyException(
        val failure: ImageSourceFailure,
    ) : RuntimeException()

    private companion object {
        const val MAX_SELECTION = 10
    }
}
