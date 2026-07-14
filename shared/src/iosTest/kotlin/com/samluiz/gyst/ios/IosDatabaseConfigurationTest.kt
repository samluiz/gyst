package com.samluiz.gyst.ios

import co.touchlab.sqliter.DatabaseConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosDatabaseConfigurationTest {
    @Test
    fun nativeDatabaseConfigurationEnforcesForeignKeysWithoutDiscardingOtherOptions() {
        val original =
            DatabaseConfiguration(
                name = "gyst.db",
                version = 8,
                create = {},
                extendedConfig =
                    DatabaseConfiguration.Extended(
                        foreignKeyConstraints = false,
                        busyTimeout = 12_345,
                        recursiveTriggers = true,
                    ),
            )

        val configured = original.withGystIosIntegrity("/documents")

        assertTrue(configured.extendedConfig.foreignKeyConstraints)
        assertEquals("/documents", configured.extendedConfig.basePath)
        assertEquals(12_345, configured.extendedConfig.busyTimeout)
        assertTrue(configured.extendedConfig.recursiveTriggers)
    }
}
