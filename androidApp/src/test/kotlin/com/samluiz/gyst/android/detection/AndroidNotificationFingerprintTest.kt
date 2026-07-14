package com.samluiz.gyst.android.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidNotificationFingerprintTest {
    @Test
    fun `identical duplicate callback has the same fingerprint`() {
        val envelope = envelope(text = "Compra de R$ 42,90 aprovada")

        assertEquals(
            AndroidNotificationFingerprint.create(envelope),
            AndroidNotificationFingerprint.create(envelope.copy()),
        )
    }

    @Test
    fun `content update on the same Android key is reprocessed`() {
        val original = envelope(text = "Compra em processamento")
        val updated = envelope(text = "Compra de R$ 42,90 aprovada")

        assertNotEquals(
            AndroidNotificationFingerprint.create(original),
            AndroidNotificationFingerprint.create(updated),
        )
    }

    private fun envelope(text: String) =
        AndroidNotificationEnvelope(
            identity =
                AndroidNotificationIdentity(
                    sourcePackage = "com.example.bank",
                    notificationKey = "0|com.example.bank|42|null|1000",
                    notificationId = 42,
                    notificationTag = null,
                ),
            postedAtEpochMillis = 1_752_509_800_000,
            title = "Example Bank",
            text = text,
            expandedText = null,
            category = "status",
            channelId = "transactions",
            isOngoing = false,
        )
}
