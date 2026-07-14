package com.samluiz.gyst.domain.service

import com.samluiz.gyst.domain.model.sha256

const val MAX_TEMPORARY_IMAGE_BYTES: Long = 12L * 1024L * 1024L
const val MAX_TEMPORARY_IMAGE_BATCH_BYTES: Long = 40L * 1024L * 1024L
const val TEMPORARY_IMAGE_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L

private val supportedImageMimeTypes =
    setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
    )

fun canonicalImageMimeType(rawMimeType: String?): String? {
    val normalized =
        rawMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.let { mimeType -> if (mimeType == "image/jpg") "image/jpeg" else mimeType }
    return normalized?.takeIf(supportedImageMimeTypes::contains)
}

fun imageFileExtension(mimeType: String): String =
    when (canonicalImageMimeType(mimeType)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> error("Unsupported image MIME type.")
    }

fun detectedImageMimeType(bytes: ByteArray): String? =
    when {
        bytes.startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> "image/gif"
        bytes.size >= 12 && bytes.startsWithAscii("RIFF") && bytes.startsWithAscii("WEBP", offset = 8) -> "image/webp"
        else -> null
    }

fun isTemporaryImageSizeAllowed(byteSize: Long): Boolean = byteSize in 1..MAX_TEMPORARY_IMAGE_BYTES

fun isTemporaryImageExpired(
    lastModifiedMillis: Long,
    nowMillis: Long,
): Boolean = lastModifiedMillis <= nowMillis - TEMPORARY_IMAGE_TTL_MILLIS

fun sanitizedImageDisplayName(name: String?): String {
    val safe =
        name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            ?.trim()
            ?.take(96)
            .orEmpty()
    return safe.ifBlank { "financial-image" }
}

fun temporaryImageSha256(bytes: ByteArray): String = sha256(bytes)

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> (this[index].toInt() and 0xff) == expected[index] }

private fun ByteArray.startsWithAscii(
    expected: String,
    offset: Int = 0,
): Boolean =
    size >= offset + expected.length &&
        expected.indices.all { index -> this[offset + index].toInt() == expected[index].code }
