package com.samluiz.gyst.desktop

import com.samluiz.gyst.domain.service.AdvisorSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class DesktopAdvisorSecretStore(private val protectedFile: Path) : AdvisorSecretStore {
    private val platform = DesktopSecretPlatform.current()

    override suspend fun readApiKey(): String? = readApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun readApiKey(profileId: String): String? {
        if (
            platform != DesktopSecretPlatform.WINDOWS &&
            profileId == DEFAULT_PROFILE_SLOT &&
            Files.exists(protectedFile)
        ) {
            val legacyKey = Files.readString(protectedFile).trim().takeIf(String::isNotBlank)
            if (legacyKey == null) {
                Files.deleteIfExists(protectedFile)
            } else {
                val migrated = runCatching { platform.write(profilePath(profileId), profileId, legacyKey) }.isSuccess
                if (migrated) Files.deleteIfExists(protectedFile)
                return legacyKey
            }
        }
        return platform.read(profilePath(profileId), profileId)
    }

    override suspend fun writeApiKey(apiKey: String) = writeApiKey(DEFAULT_PROFILE_SLOT, apiKey)

    override suspend fun writeApiKey(
        profileId: String,
        apiKey: String,
    ) = platform.write(profilePath(profileId), profileId, apiKey)

    override suspend fun clearApiKey() = clearApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun clearApiKey(profileId: String) = platform.clear(profilePath(profileId), profileId)

    private fun profilePath(profileId: String): Path {
        if (profileId == DEFAULT_PROFILE_SLOT) return protectedFile
        val digest = MessageDigest.getInstance("SHA-256").digest(profileId.encodeToByteArray())
        val suffix = digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
        return protectedFile.resolveSibling("${protectedFile.fileName}.$suffix")
    }
}

private enum class DesktopSecretPlatform {
    LINUX {
        override fun read(
            path: Path,
            profileId: String,
        ): String? {
            val current =
                runCatching { runCommand(listOf("secret-tool", "lookup", "application", "gyst", "account", account(profileId))) }
                    .getOrNull()
                    ?.takeIf { it.exitCode == 0 }
                    ?.output
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            if (current != null || profileId != DEFAULT_PROFILE_SLOT) return current
            val legacy =
                runCatching { runCommand(listOf("secret-tool", "lookup", "application", "gyst", "account", LEGACY_LINUX_ACCOUNT)) }
                    .getOrNull()
                    ?.takeIf { it.exitCode == 0 }
                    ?.output
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            if (legacy != null) {
                write(path, profileId, legacy)
                runCommand(listOf("secret-tool", "clear", "application", "gyst", "account", LEGACY_LINUX_ACCOUNT))
            }
            return legacy
        }

        override fun write(
            path: Path,
            profileId: String,
            secret: String,
        ) {
            val result =
                runCommand(
                    listOf(
                        "secret-tool",
                        "store",
                        "--label=Gyst financial advisor",
                        "application",
                        "gyst",
                        "account",
                        account(profileId),
                    ),
                    stdin = secret,
                )
            check(result.exitCode == 0) { credentialStoreError("Secret Service", result) }
        }

        override fun clear(
            path: Path,
            profileId: String,
        ) {
            val result = runCommand(listOf("secret-tool", "clear", "application", "gyst", "account", account(profileId)))
            check(result.exitCode == 0 || result.exitCode == 1) { credentialStoreError("Secret Service", result) }
        }
    },
    MACOS {
        override fun read(
            path: Path,
            profileId: String,
        ): String? {
            val current =
                runCatching { runCommand(listOf("security", "find-generic-password", "-s", SERVICE, "-a", account(profileId), "-w")) }
                    .getOrNull()
                    ?.takeIf { it.exitCode == 0 }
                    ?.output
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            if (current != null || profileId != DEFAULT_PROFILE_SLOT) return current
            val legacy =
                runCatching { runCommand(listOf("security", "find-generic-password", "-s", SERVICE, "-a", LEGACY_MACOS_ACCOUNT, "-w")) }
                    .getOrNull()
                    ?.takeIf { it.exitCode == 0 }
                    ?.output
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            if (legacy != null) {
                write(path, profileId, legacy)
                runCommand(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", LEGACY_MACOS_ACCOUNT))
            }
            return legacy
        }

        override fun write(
            path: Path,
            profileId: String,
            secret: String,
        ) {
            val result =
                runCommand(
                    listOf("security", "add-generic-password", "-U", "-s", SERVICE, "-a", account(profileId), "-w", secret),
                )
            check(result.exitCode == 0) { credentialStoreError("macOS Keychain", result) }
        }

        override fun clear(
            path: Path,
            profileId: String,
        ) {
            val result = runCommand(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", account(profileId)))
            check(result.exitCode == 0 || result.exitCode == 44) { credentialStoreError("macOS Keychain", result) }
        }
    },
    WINDOWS {
        override fun read(
            path: Path,
            profileId: String,
        ): String? {
            if (!Files.exists(path)) return null
            val result = runPowerShell(WINDOWS_READ_SCRIPT, path.toString())
            if (result.exitCode != 0) {
                val legacyKey = runCatching { Files.readString(path).trim() }.getOrNull()?.takeIf(String::isNotBlank)
                if (legacyKey != null) {
                    write(path, profileId, legacyKey)
                    return legacyKey
                }
                error(credentialStoreError("Windows DPAPI", result))
            }
            return result.output.trim().takeIf(String::isNotBlank)
        }

        override fun write(
            path: Path,
            profileId: String,
            secret: String,
        ) {
            Files.createDirectories(path.parent)
            val result = runPowerShell(WINDOWS_WRITE_SCRIPT, path.toString(), secret)
            check(result.exitCode == 0) { credentialStoreError("Windows DPAPI", result) }
        }

        override fun clear(
            path: Path,
            profileId: String,
        ) {
            Files.deleteIfExists(path)
        }
    },
    ;

    abstract fun read(
        path: Path,
        profileId: String,
    ): String?

    abstract fun write(
        path: Path,
        profileId: String,
        secret: String,
    )

    abstract fun clear(
        path: Path,
        profileId: String,
    )

    companion object {
        fun current(): DesktopSecretPlatform {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> MACOS
                os.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

private fun account(profileId: String): String = "advisor-api-key:$profileId"

private data class CommandResult(val exitCode: Int, val output: String)

private fun runCommand(
    command: List<String>,
    stdin: String? = null,
): CommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (stdin != null) {
        process.outputStream.bufferedWriter().use { it.write(stdin) }
    } else {
        process.outputStream.close()
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return CommandResult(process.waitFor(), output)
}

private fun runPowerShell(
    script: String,
    vararg inputLines: String,
): CommandResult =
    runCommand(
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script),
        stdin = inputLines.joinToString("\n"),
    )

private fun credentialStoreError(
    store: String,
    result: CommandResult,
): String = "$store is unavailable (${result.exitCode}): ${result.output.trim()}"

private const val SERVICE = "com.samluiz.gyst.advisor"
private const val DEFAULT_PROFILE_SLOT = "default"
private const val LEGACY_LINUX_ACCOUNT = "advisor-api-key"
private const val LEGACY_MACOS_ACCOUNT = "api-key"
private const val WINDOWS_READ_SCRIPT =
    "${'$'}path = [Console]::In.ReadLine(); " +
        "${'$'}bytes = [IO.File]::ReadAllBytes(${'$'}path); " +
        "${'$'}plain = [Security.Cryptography.ProtectedData]::Unprotect(${'$'}bytes, ${'$'}null, 'CurrentUser'); " +
        "[Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}plain))"
private const val WINDOWS_WRITE_SCRIPT =
    "${'$'}path = [Console]::In.ReadLine(); " +
        "${'$'}secret = [Console]::In.ReadToEnd().TrimStart([char]10, [char]13); " +
        "${'$'}plain = [Text.Encoding]::UTF8.GetBytes(${'$'}secret); " +
        "${'$'}bytes = [Security.Cryptography.ProtectedData]::Protect(${'$'}plain, ${'$'}null, 'CurrentUser'); " +
        "[IO.File]::WriteAllBytes(${'$'}path, ${'$'}bytes)"
