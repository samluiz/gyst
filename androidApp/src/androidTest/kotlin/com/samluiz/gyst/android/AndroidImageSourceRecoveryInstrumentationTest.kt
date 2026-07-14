package com.samluiz.gyst.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samluiz.gyst.domain.service.ImageSourceResult
import com.samluiz.gyst.domain.service.TEMPORARY_IMAGE_TTL_MILLIS
import com.samluiz.gyst.domain.service.TemporaryImageHandle
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
}
