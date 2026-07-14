package com.samluiz.gyst.desktop

import com.samluiz.gyst.domain.service.AdvisorSecretStore
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopAdvisorSecretStore(private val protectedFile: Path) : AdvisorSecretStore {
    private val platform = DesktopSecretPlatform.current()

    override suspend fun readApiKey(): String? {
        if (platform != DesktopSecretPlatform.WINDOWS && Files.exists(protectedFile)) {
            val legacyKey = Files.readString(protectedFile).trim().takeIf(String::isNotBlank)
            val migrated = legacyKey != null && runCatching { platform.write(protectedFile, legacyKey) }.isSuccess
            Files.deleteIfExists(protectedFile)
            if (migrated) return legacyKey
        }
        return platform.read(protectedFile)
    }

    override suspend fun writeApiKey(apiKey: String) = platform.write(protectedFile, apiKey)

    override suspend fun clearApiKey() = platform.clear(protectedFile)
}

private enum class DesktopSecretPlatform {
    LINUX {
        override fun read(path: Path): String? =
            runCatching { runCommand(listOf("secret-tool", "lookup", "application", "gyst", "account", "advisor-api-key")) }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 }
                ?.output
                ?.trim()
                ?.takeIf(String::isNotBlank)

        override fun write(
            path: Path,
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
                        "advisor-api-key",
                    ),
                    stdin = secret,
                )
            check(result.exitCode == 0) { credentialStoreError("Secret Service", result) }
        }

        override fun clear(path: Path) {
            val result = runCommand(listOf("secret-tool", "clear", "application", "gyst", "account", "advisor-api-key"))
            check(result.exitCode == 0 || result.exitCode == 1) { credentialStoreError("Secret Service", result) }
        }
    },
    MACOS {
        override fun read(path: Path): String? =
            runCatching { runCommand(listOf("security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")) }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 }
                ?.output
                ?.trim()
                ?.takeIf(String::isNotBlank)

        override fun write(
            path: Path,
            secret: String,
        ) {
            val result =
                runCommand(
                    listOf("security", "add-generic-password", "-U", "-s", SERVICE, "-a", ACCOUNT, "-w", secret),
                )
            check(result.exitCode == 0) { credentialStoreError("macOS Keychain", result) }
        }

        override fun clear(path: Path) {
            val result = runCommand(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", ACCOUNT))
            check(result.exitCode == 0 || result.exitCode == 44) { credentialStoreError("macOS Keychain", result) }
        }
    },
    WINDOWS {
        override fun read(path: Path): String? {
            if (!Files.exists(path)) return null
            val result = runPowerShell(WINDOWS_READ_SCRIPT, path.toString())
            if (result.exitCode != 0) {
                val legacyKey = runCatching { Files.readString(path).trim() }.getOrNull()?.takeIf(String::isNotBlank)
                if (legacyKey != null) {
                    write(path, legacyKey)
                    return legacyKey
                }
                error(credentialStoreError("Windows DPAPI", result))
            }
            return result.output.trim().takeIf(String::isNotBlank)
        }

        override fun write(
            path: Path,
            secret: String,
        ) {
            Files.createDirectories(path.parent)
            val result = runPowerShell(WINDOWS_WRITE_SCRIPT, path.toString(), secret)
            check(result.exitCode == 0) { credentialStoreError("Windows DPAPI", result) }
        }

        override fun clear(path: Path) {
            Files.deleteIfExists(path)
        }
    };

    abstract fun read(path: Path): String?

    abstract fun write(
        path: Path,
        secret: String,
    )

    abstract fun clear(path: Path)

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
private const val ACCOUNT = "api-key"
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
