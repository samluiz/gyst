package com.samluiz.gyst.ios

import com.samluiz.gyst.domain.service.AdvisorSecretStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress("UNCHECKED_CAST")
internal class IosAdvisorSecretStore : AdvisorSecretStore {
    override suspend fun readApiKey(): String? = readApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun readApiKey(profileId: String): String? =
        withBaseQuery(profileId) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                val rawResult = result.value ?: return@memScoped null
                try {
                    val data = rawResult as CFDataRef
                    val bytes = CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt()) ?: return@memScoped null
                    bytes.decodeToString().takeIf(String::isNotBlank)
                } finally {
                    CFRelease(rawResult)
                }
            }
        }

    override suspend fun writeApiKey(apiKey: String) = writeApiKey(DEFAULT_PROFILE_SLOT, apiKey)

    override suspend fun writeApiKey(
        profileId: String,
        apiKey: String,
    ) {
        val bytes = apiKey.encodeToByteArray()
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong()) }
        checkNotNull(data) { "Unable to encode API key." }
        try {
            withBaseQuery(profileId) { query ->
                val attributes = newDictionary()
                try {
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    val updateStatus = SecItemUpdate(query, attributes)
                    val status =
                        if (updateStatus == errSecItemNotFound) {
                            CFDictionarySetValue(query, kSecValueData, data)
                            SecItemAdd(query, null)
                        } else {
                            updateStatus
                        }
                    check(status == errSecSuccess) { "Keychain rejected the API key ($status)." }
                } finally {
                    CFRelease(attributes)
                }
            }
        } finally {
            CFRelease(data)
        }
    }

    override suspend fun clearApiKey() = clearApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun clearApiKey(profileId: String) {
        withBaseQuery(profileId) { query ->
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain could not remove the API key ($status)." }
        }
    }

    private inline fun <T> withBaseQuery(
        profileId: String,
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val query = newDictionary()
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, account(profileId), kCFStringEncodingUTF8)
        checkNotNull(service)
        checkNotNull(account)
        return try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            block(query)
        } finally {
            CFRelease(account)
            CFRelease(service)
            CFRelease(query)
        }
    }

    private fun newDictionary(): CFMutableDictionaryRef =
        checkNotNull(
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        )

    private companion object {
        const val SERVICE = "com.samluiz.gyst.advisor"
        const val DEFAULT_PROFILE_SLOT = "default"

        fun account(profileId: String): String = if (profileId == DEFAULT_PROFILE_SLOT) "api-key" else "api-key:$profileId"
    }
}
