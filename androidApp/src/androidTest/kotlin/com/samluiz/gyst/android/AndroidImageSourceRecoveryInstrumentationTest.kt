package com.samluiz.gyst.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samluiz.gyst.domain.service.ImageSourceFailure
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.MAX_TEMPORARY_IMAGE_BYTES
import com.samluiz.gyst.domain.service.TEMPORARY_IMAGE_TTL_MILLIS
import com.samluiz.gyst.domain.service.TemporaryImageHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidImageSourceRecoveryInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences(
            AndroidImageSourceService.RECOVERY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
    }
    private val cacheDirectory by lazy {
        File(context.cacheDir, AndroidImageSourceService.CACHE_DIRECTORY_NAME)
    }

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        cacheDirectory.deleteRecursively()
        assertTrue(cacheDirectory.exists() || cacheDirectory.mkdirs())
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun activeCallbackResultIsQueuedBeforeDeliveryAndSurvivesProcessRecreation() =
        runBlocking {
            val reference = "recreated-selection.png"
            val bytes = byteArrayOf(1, 2, 3, 4)
            File(cacheDirectory, reference).writeBytes(bytes)
            val callbackResult =
                ImageSourceResult.Selected(
                    listOf(
                        TemporaryImageHandle(
                            id = "recovered-id",
                            displayName = "statement.png",
                            mimeType = "image/png",
                            byteSize = bytes.size.toLong(),
                            sha256 = "stable-hash",
                            temporaryReference = reference,
                        ),
                    ),
                )
            val delivered =
                AndroidImageSourceService(context).queueRecoveredResult(callbackResult)
                    as ImageSourceResult.Selected

            assertEquals(listOf("recovered-id"), delivered.images.map { it.id })
            val recreatedService = AndroidImageSourceService(context)
            assertEquals(
                listOf("recovered-id"),
                recreatedService.pendingRecoveredImages().map { it.id },
            )
            val pending = recreatedService.pendingRecoveredImages()

            recreatedService.acknowledgeRecoveredImages(pending)
            assertTrue(recreatedService.pendingRecoveredImages().isEmpty())
            assertTrue(File(cacheDirectory, reference).isFile)
        }

    @Test
    fun expiredQueuedResultCannotBeRecoveredOnActivityResume() =
        runBlocking {
            val reference = "expired-selection.png"
            val file = File(cacheDirectory, reference).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val result =
                ImageSourceResult.Selected(
                    listOf(
                        TemporaryImageHandle(
                            id = "expired-id",
                            displayName = "expired.png",
                            mimeType = "image/png",
                            byteSize = file.length(),
                            sha256 = "expired-hash",
                            temporaryReference = reference,
                        ),
                    ),
                )
            AndroidImageSourceService(context).queueRecoveredResult(result)
            assertTrue(
                file.setLastModified(
                    System.currentTimeMillis() - TEMPORARY_IMAGE_TTL_MILLIS - 1,
                ),
            )

            assertTrue(AndroidImageSourceService(context).pendingRecoveredImages().isEmpty())
        }

    @Test
    fun largeDimensionPhotoIsNormalizedBeforeProviderCustody() =
        runBlocking {
            val source = File(cacheDirectory, "large-photo.jpg")
            writeLargeJpeg(source)

            val result = AndroidImageSourceService(context).importUris(listOf(uriFor(source))) as ImageSourceResult.Selected
            val handle = result.images.single()

            assertEquals("image/jpeg", handle.mimeType)
            assertTrue(handle.byteSize <= MAX_TEMPORARY_IMAGE_BYTES)
            assertNormalizedDimensions(File(cacheDirectory, handle.temporaryReference))
        }

    @Test
    fun cameraFileUsesTheSameNormalizationPolicy() =
        runBlocking {
            val source = File(cacheDirectory, "camera-photo.jpg")
            writeLargeJpeg(source)

            val result =
                AndroidImageSourceService(context)
                    .importCapturedFile(source, "camera-id", "camera-photo.jpg") as ImageSourceResult.Selected
            val handle = result.images.single()

            assertEquals(source.name, handle.temporaryReference)
            assertTrue(handle.byteSize <= MAX_TEMPORARY_IMAGE_BYTES)
            assertNormalizedDimensions(source)
        }

    @Test
    fun legacyDecoderAppliesExifRotationBeforeCompression() {
        val source = File(cacheDirectory, "portrait-with-exif.jpg")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = checkNotNull(BitmapFactory.decodeFile(source.absolutePath))

        val oriented = AndroidImageSourceService(context).applyExifOrientation(source, decoded)

        try {
            assertEquals(60, oriented.width)
            assertEquals(120, oriented.height)
        } finally {
            oriented.recycle()
        }
    }

    @Test
    fun unsupportedAndMissingSourcesReturnSpecificFailures() =
        runBlocking {
            val unsupported = File(cacheDirectory, "not-an-image.jpg").apply { writeText("not an image") }
            val service = AndroidImageSourceService(context)

            val unsupportedResult = service.importUris(listOf(uriFor(unsupported))) as ImageSourceResult.Failed
            val missingResult =
                service.importUris(listOf(Uri.parse("content://com.samluiz.gyst.missing/image"))) as ImageSourceResult.Failed

            assertEquals(ImageSourceFailure.UNSUPPORTED_FORMAT, unsupportedResult.reason)
            assertEquals(ImageSourceFailure.IO_FAILURE, missingResult.reason)
        }

    @Test
    fun oversizedSourceIsRejectedAndCancellationLeavesNoStagingFile() =
        runBlocking {
            val oversized =
                File(cacheDirectory, "oversized.jpg").apply {
                    outputStream().use { it.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) }
                    java.io.RandomAccessFile(this, "rw").use {
                        it.setLength(AndroidImageSourceService.MAX_TRANSCODE_SOURCE_BYTES + 1)
                    }
                }
            val service = AndroidImageSourceService(context)
            val oversizedResult = service.importUris(listOf(uriFor(oversized))) as ImageSourceResult.Failed
            assertEquals(ImageSourceFailure.FILE_TOO_LARGE, oversizedResult.reason)

            val cancellable =
                File(cacheDirectory, "cancellable.jpg").apply {
                    java.io.RandomAccessFile(this, "rw").use {
                        it.setLength(AndroidImageSourceService.MAX_TRANSCODE_SOURCE_BYTES - 1)
                    }
                }
            val job = launch(Dispatchers.IO) { service.importUris(listOf(uriFor(cancellable))) }
            delay(1)
            job.cancelAndJoin()

            assertTrue(cacheDirectory.listFiles().orEmpty().none { it.extension == "source" })
        }

    @Test
    fun cancelledPickerCallbackMustArriveBeforeAnotherPickerCanLaunch() {
        val gate = AndroidImageSourceService.SelectCallbackGate()

        gate.onCancelled(callbackReceived = false)

        assertTrue(!gate.canLaunch())
        assertTrue(gate.consumeCancelledCallback())
        assertTrue(gate.canLaunch())
        assertTrue(!gate.consumeCancelledCallback())
    }

    @Test
    fun cancellationAfterPickerCallbackDoesNotSuppressANewerCallback() {
        val gate = AndroidImageSourceService.SelectCallbackGate()

        gate.onCancelled(callbackReceived = true)

        assertTrue(gate.canLaunch())
        assertTrue(!gate.consumeCancelledCallback())
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun writeLargeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(2_500, 1_800, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertNormalizedDimensions(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= AndroidImageSourceService.NORMALIZED_IMAGE_MAX_DIMENSION)
    }
}
