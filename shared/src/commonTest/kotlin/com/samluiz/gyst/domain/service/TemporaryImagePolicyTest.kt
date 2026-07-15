package com.samluiz.gyst.domain.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporaryImagePolicyTest {
    @Test
    fun `canonical MIME aliases and parameters are normalized`() {
        assertEquals("image/jpeg", canonicalImageMimeType(" IMAGE/JPG; charset=binary "))
        assertEquals("image/png", canonicalImageMimeType("image/png"))
        assertNull(canonicalImageMimeType("image/svg+xml"))
        assertNull(canonicalImageMimeType("application/pdf"))
    }

    @Test
    fun `content signatures detect supported formats`() {
        assertEquals("image/jpeg", detectedImageMimeType(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals(
            "image/png",
            detectedImageMimeType(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)),
        )
        assertEquals("image/gif", detectedImageMimeType("GIF89a".encodeToByteArray()))
        assertEquals("image/webp", detectedImageMimeType("RIFF0000WEBP".encodeToByteArray()))
        assertNull(detectedImageMimeType("not-an-image".encodeToByteArray()))
    }

    @Test
    fun `empty and over-limit images are rejected`() {
        assertFalse(isTemporaryImageSizeAllowed(0))
        assertTrue(isTemporaryImageSizeAllowed(MAX_TEMPORARY_IMAGE_BYTES))
        assertFalse(isTemporaryImageSizeAllowed(MAX_TEMPORARY_IMAGE_BYTES + 1))
    }

    @Test
    fun `provider request image budget stays inside a mobile-safe envelope`() {
        val worstCaseBase64Characters = ((MAX_TEMPORARY_IMAGE_BATCH_BYTES + 2) / 3) * 4
        val estimatedPeakBytes =
            MAX_TEMPORARY_IMAGE_BATCH_BYTES +
                (worstCaseBase64Characters * 2) + // Data URL strings retained by the request model.
                (worstCaseBase64Characters * 2) + // Serialized JSON UTF-16 string.
                worstCaseBase64Characters // UTF-8 HTTP request body.

        assertTrue(MAX_TEMPORARY_IMAGE_BYTES <= 3L * 1024L * 1024L)
        assertTrue(estimatedPeakBytes <= 48L * 1024L * 1024L)
    }

    @Test
    fun `expiry uses the shared custody TTL`() {
        val now = TEMPORARY_IMAGE_TTL_MILLIS * 2
        assertTrue(isTemporaryImageExpired(now - TEMPORARY_IMAGE_TTL_MILLIS, now))
        assertFalse(isTemporaryImageExpired(now - TEMPORARY_IMAGE_TTL_MILLIS + 1, now))
    }

    @Test
    fun `display names cannot escape the cache directory`() {
        assertEquals("statement_.png", sanitizedImageDisplayName("../../statement?.png"))
        assertEquals("financial-image", sanitizedImageDisplayName(""))
    }

    @Test
    fun `temporary image hash is deterministic SHA-256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            temporaryImageSha256("abc".encodeToByteArray()),
        )
    }
}
