package com.samluiz.gyst.app

import com.samluiz.gyst.domain.service.ImageImportStage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageImportCompletionTest {
    @Test
    fun completedImportRefreshesTheMainLedgerExactlyOnce() =
        runTest {
            var stage = ImageImportStage.PREVIEW
            var refreshes = 0

            confirmImageImportAndRefresh(
                confirmImport = { stage = ImageImportStage.COMPLETED },
                currentStage = { stage },
                onImportCompleted = { refreshes += 1 },
            )

            assertEquals(1, refreshes)
        }

    @Test
    fun rejectedImportDoesNotRefreshTheMainLedger() =
        runTest {
            var refreshes = 0

            confirmImageImportAndRefresh(
                confirmImport = {},
                currentStage = { ImageImportStage.PREVIEW },
                onImportCompleted = { refreshes += 1 },
            )

            assertEquals(0, refreshes)
        }
}
