package com.samluiz.gyst.desktop

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.service.SyncPolicy
import com.samluiz.gyst.domain.service.SyncSource
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.time.Clock
import kotlin.time.Instant

private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val OPENID_SCOPE = "openid"
private const val EMAIL_SCOPE = "email"
private const val PROFILE_SCOPE = "profile"
private const val BACKUP_FILE_NAME = "gyst-backup.db"
private const val OAUTH_USER_ID = "gyst-desktop-user"

class DesktopGoogleAuthSyncService(
    private val dbPath: Path,
    private val backupPath: Path,
) : GoogleAuthSyncService {
    private companion object {
        const val TAG = "DesktopGoogleSync"
    }
    private val internal = MutableStateFlow(
        GoogleSyncState(
            isAvailable = false,
            isSignedIn = false,
        )
    )
    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val googleDir: Path = dbPath.parent.resolve("google")
    private val tokenStoreDir: Path = googleDir.resolve("tokens")

    @Volatile
    private var flow: GoogleAuthorizationCodeFlow? = null

    @Volatile
    private var initError: String? = null

    override suspend fun initialize() {
        AppLogger.i(TAG, "Initialize requested")
        withContext(Dispatchers.IO) {
            val authFlow = runCatching { ensureFlow() }.getOrElse { throwable ->
                AppLogger.e(TAG, "Initialize failed: OAuth flow unavailable", throwable)
                initError = throwable.message ?: "Desktop Google OAuth is not configured."
                internal.update {
                    it.copy(
                        isAvailable = false,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        isSyncing = false,
                        accountName = null,
                        accountEmail = null,
                        accountPhotoUrl = null,
                        lastError = initError,
                    )
                }
                return@withContext
            }

            val credential = authFlow.loadCredential(OAUTH_USER_ID)
            if (credential?.accessToken.isNullOrBlank()) {
                internal.update {
                    it.copy(
                        isAvailable = true,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        isSyncing = false,
                        accountName = null,
                        accountEmail = null,
                        accountPhotoUrl = null,
                        lastError = null,
                    )
                }
                return@withContext
            }

            val token = runCatching { requireAccessToken(authFlow) }.getOrElse { throwable ->
                AppLogger.e(TAG, "Initialize failed: token refresh", throwable)
                internal.update {
                    it.copy(
                        isAvailable = true,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        isSyncing = false,
                        accountName = null,
                        accountEmail = null,
                        accountPhotoUrl = null,
                        lastError = throwable.message ?: "Failed to refresh Google session.",
                    )
                }
                return@withContext
            }
            val profile = runCatching { fetchProfile(token) }.getOrNull()
            AppLogger.i(TAG, "Initialize completed. signedIn=${profile != null}")
            internal.update {
                it.copy(
                    isAvailable = true,
                    isSignedIn = true,
                    accountName = profile?.name,
                    accountEmail = profile?.email,
                    accountPhotoUrl = profile?.photoUrl,
                    isAuthInProgress = false,
                    isSyncing = false,
                    lastError = null,
                )
            }
        }
    }

    override suspend fun signIn() {
        AppLogger.i(TAG, "Sign-in requested")
        internal.update { it.copy(isAuthInProgress = true, lastError = null, statusMessage = null) }
        withContext(Dispatchers.IO) {
            val authFlow = runCatching { ensureFlow() }.getOrElse { throwable ->
                AppLogger.e(TAG, "Sign-in failed: OAuth flow unavailable", throwable)
                internal.update {
                    it.copy(
                        isAvailable = false,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        lastError = throwable.message ?: "Desktop Google OAuth is not configured.",
                    )
                }
                return@withContext
            }

            val receiver = LocalServerReceiver.Builder()
                .setHost("127.0.0.1")
                .setPort(findAvailablePort())
                .build()

            val authResult = runCatching {
                AuthorizationCodeInstalledApp(authFlow, receiver).authorize(OAUTH_USER_ID)
            }
            receiver.stop()
            authResult.onFailure { throwable ->
                AppLogger.e(TAG, "Sign-in failed during authorization", throwable)
                internal.update {
                    it.copy(
                        isAvailable = true,
                        isAuthInProgress = false,
                        lastError = throwable.message ?: "Google sign-in failed.",
                    )
                }
                return@withContext
            }

            val token = runCatching { requireAccessToken(authFlow) }.getOrElse { throwable ->
                AppLogger.e(TAG, "Sign-in failed during token fetch", throwable)
                internal.update {
                    it.copy(
                        isAvailable = true,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        lastError = throwable.message ?: "Failed to complete Google sign-in.",
                    )
                }
                return@withContext
            }

            val profile = runCatching { fetchProfile(token) }.getOrNull()
            AppLogger.i(TAG, "Sign-in completed. account=${profile?.email ?: "unknown"}")
            internal.update {
                it.copy(
                    isAvailable = true,
                    isSignedIn = true,
                    accountName = profile?.name,
                    accountEmail = profile?.email,
                    accountPhotoUrl = profile?.photoUrl,
                    isAuthInProgress = false,
                    lastError = null,
                )
            }
        }
    }

    override suspend fun signOut() {
        AppLogger.i(TAG, "Sign-out requested")
        withContext(Dispatchers.IO) {
            val authFlow = flow
            if (authFlow != null) {
                runCatching {
                    val credential = authFlow.loadCredential(OAUTH_USER_ID)
                    val token = credential?.accessToken
                    if (!token.isNullOrBlank()) {
                        revokeToken(token)
                    }
                    authFlow.credentialDataStore.delete(OAUTH_USER_ID)
                }
            }
            internal.update {
                it.copy(
                    isSignedIn = false,
                    accountName = null,
                    accountEmail = null,
                    accountPhotoUrl = null,
                    isAuthInProgress = false,
                    isSyncing = false,
                    statusMessage = null,
                    requiresAppRestart = false,
                    lastError = null,
                )
            }
        }
    }

    override suspend fun syncNow() {
        AppLogger.i(TAG, "Sync requested")
        internal.update { it.copy(isSyncing = true, lastError = null, statusMessage = null, requiresAppRestart = false) }
        withContext(Dispatchers.IO) {
            val authFlow = runCatching { ensureFlow() }.getOrElse { throwable ->
                AppLogger.e(TAG, "Sync failed: OAuth flow unavailable", throwable)
                internal.update { it.copy(isSyncing = false, lastError = throwable.message ?: "Google OAuth not configured.") }
                return@withContext
            }
            val token = runCatching { requireAccessToken(authFlow) }.getOrElse { throwable ->
                AppLogger.e(TAG, "Sync failed: token unavailable", throwable)
                internal.update { it.copy(isSyncing = false, lastError = throwable.message ?: "Google session expired.") }
                return@withContext
            }
            runCatching {
                Files.createDirectories(backupPath.parent)
                val localExists = dbPath.exists()
                val remote = findBackupFile(token)

                if (!localExists && remote == null) error("No local data available to sync.")

                val localUpdatedAt = if (localExists) fileUpdatedAt(dbPath) else null
                val remoteUpdatedAt = remote?.modifiedAt

                when {
                    remote == null && localExists -> {
                        AppLogger.i(TAG, "Sync path: LOCAL_TO_CLOUD create")
                        val localBytes = readDatabaseBytes()
                        createBackupFile(token, localBytes)
                        Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = Clock.System.now().toString(),
                                lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = false,
                                statusMessage = "Uploaded local data to Google Drive.",
                                lastError = null,
                            )
                        }
                    }
                    localUpdatedAt == null && remote != null -> {
                        AppLogger.i(TAG, "Sync path: CLOUD_TO_LOCAL local missing")
                        val remoteBytes = downloadBackupFile(token, remote.id)
                        writeDatabaseBytes(remoteBytes)
                        Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = Clock.System.now().toString(),
                                lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = false,
                                statusMessage = "Recovered local data from Google Drive.",
                                requiresAppRestart = true,
                                lastError = null,
                            )
                        }
                    }
                    remoteUpdatedAt != null && localUpdatedAt != null && remoteUpdatedAt > localUpdatedAt -> {
                        AppLogger.w(TAG, "Sync conflict resolved by cloud newer timestamp")
                        val remoteBytes = downloadBackupFile(token, remote.id)
                        writeDatabaseBytes(remoteBytes)
                        Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = Clock.System.now().toString(),
                                lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = true,
                                statusMessage = "Conflict resolved by timestamp: cloud data was newer.",
                                requiresAppRestart = true,
                                lastError = null,
                            )
                        }
                    }
                    else -> {
                        AppLogger.i(TAG, "Sync path: LOCAL_TO_CLOUD update")
                        val localBytes = readDatabaseBytes()
                        updateBackupFile(token, remote!!.id, localBytes)
                        Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = Clock.System.now().toString(),
                                lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = false,
                                statusMessage = "Synced local data to Google Drive.",
                                lastError = null,
                            )
                        }
                    }
                }
            }.onFailure { throwable ->
                AppLogger.e(TAG, "Sync failed", throwable)
                internal.update {
                    it.copy(
                        isSyncing = false,
                        statusMessage = null,
                        lastError = throwable.message ?: "Google Drive sync failed",
                    )
                }
            }
        }
    }

    override suspend fun restoreFromCloud(overwriteLocal: Boolean) {
        AppLogger.i(TAG, "Restore requested overwriteLocal=$overwriteLocal")
        if (!overwriteLocal) {
            internal.update { it.copy(lastError = "Restore canceled.") }
            return
        }
        internal.update { it.copy(isSyncing = true, lastError = null, statusMessage = null) }
        withContext(Dispatchers.IO) {
            val authFlow = runCatching { ensureFlow() }.getOrElse { throwable ->
                AppLogger.e(TAG, "Restore failed: OAuth flow unavailable", throwable)
                internal.update { it.copy(isSyncing = false, lastError = throwable.message ?: "Google OAuth not configured.") }
                return@withContext
            }
            val token = runCatching { requireAccessToken(authFlow) }.getOrElse { throwable ->
                AppLogger.e(TAG, "Restore failed: token unavailable", throwable)
                internal.update { it.copy(isSyncing = false, lastError = throwable.message ?: "Google session expired.") }
                return@withContext
            }
            runCatching {
                val remote = findBackupFile(token) ?: error("No backup found on Google Drive.")
                val remoteBytes = downloadBackupFile(token, remote.id)
                writeDatabaseBytes(remoteBytes)
                Files.copy(dbPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                internal.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncAtIso = Clock.System.now().toString(),
                        lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                        lastSyncPolicy = SyncPolicy.OVERWRITE_LOCAL,
                        hadSyncConflict = false,
                        statusMessage = "Backup restored from cloud. Restart app to apply data.",
                        requiresAppRestart = true,
                        lastError = null,
                    )
                }
            }.onFailure { throwable ->
                AppLogger.e(TAG, "Restore failed", throwable)
                internal.update {
                    it.copy(
                        isSyncing = false,
                        statusMessage = null,
                        lastError = throwable.message ?: "Google Drive restore failed",
                    )
                }
            }
        }
    }

    private fun ensureFlow(): GoogleAuthorizationCodeFlow {
        flow?.let { return it }
        Files.createDirectories(googleDir)
        Files.createDirectories(tokenStoreDir)

        val configJson = loadDesktopOAuthJson()
            ?: error(
                "Desktop Google OAuth client JSON not found. " +
                    "Set GYST_GOOGLE_DESKTOP_OAUTH_PATH or place desktop_oauth_client.json in ${googleDir.pathString}."
            )
        val config = parseDesktopOAuthConfig(configJson)
            ?: error("Invalid desktop OAuth JSON. Use an OAuth Client ID of type 'Desktop app'.")

        val created = GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            config.clientId,
            config.clientSecret,
            listOf(DRIVE_APPDATA_SCOPE, OPENID_SCOPE, EMAIL_SCOPE, PROFILE_SCOPE),
        )
            .setDataStoreFactory(FileDataStoreFactory(tokenStoreDir.toFile()))
            .setAccessType("offline")
            .build()
        flow = created
        return created
    }

    private fun requireAccessToken(flow: GoogleAuthorizationCodeFlow): String {
        val credential = flow.loadCredential(OAUTH_USER_ID)
            ?: error("You are not signed in with Google.")
        if (credential.accessToken.isNullOrBlank() || (credential.expiresInSeconds ?: 0) <= 60L) {
            val refreshed = credential.refreshToken()
            if (!refreshed && credential.accessToken.isNullOrBlank()) {
                error("Failed to refresh Google access token.")
            }
        }
        return credential.accessToken ?: error("Google access token is missing.")
    }

    private fun parseDesktopOAuthConfig(raw: String): DesktopOAuthConfig? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val section = root["installed"]?.jsonObject ?: root["web"]?.jsonObject ?: return null
        val clientId = section["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val clientSecret = section["client_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return DesktopOAuthConfig(clientId = clientId, clientSecret = clientSecret)
    }

    private fun loadDesktopOAuthJson(): String? {
        val envPath = System.getenv("GYST_GOOGLE_DESKTOP_OAUTH_PATH")?.trim().orEmpty()
        if (envPath.isNotEmpty()) {
            val file = Path.of(envPath)
            if (Files.exists(file)) return Files.readString(file)
        }

        val envInline = System.getenv("GYST_GOOGLE_DESKTOP_OAUTH_JSON")?.trim().orEmpty()
        if (envInline.isNotEmpty()) return envInline

        val localFile = googleDir.resolve("desktop_oauth_client.json")
        if (Files.exists(localFile)) return Files.readString(localFile)

        val resource = javaClass.classLoader.getResourceAsStream("desktop_oauth_client.json")
        return resource?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun fetchProfile(token: String): GoogleProfile {
        val response = requestText(
            url = "https://www.googleapis.com/oauth2/v2/userinfo",
            method = "GET",
            token = token,
        )
        val root = json.parseToJsonElement(response).jsonObject
        return GoogleProfile(
            name = root["name"]?.jsonPrimitive?.contentOrNull,
            email = root["email"]?.jsonPrimitive?.contentOrNull,
            photoUrl = root["picture"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun findBackupFile(token: String): RemoteBackupFile? {
        val query = "name='$BACKUP_FILE_NAME' and 'appDataFolder' in parents and trashed=false"
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url =
            "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&spaces=appDataFolder&orderBy=modifiedTime desc&fields=files(id,name,modifiedTime)"
        val response = requestText(url = url, method = "GET", token = token)
        val files = json.parseToJsonElement(response).jsonObject["files"] ?: return null
        val first = files.jsonArray.firstOrNull()?.jsonObject ?: return null
        val id = first["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val modified = first["modifiedTime"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return RemoteBackupFile(id = id, modifiedAt = modified)
    }

    private fun createBackupFile(token: String, data: ByteArray) {
        val metadata = """{"name":"$BACKUP_FILE_NAME","parents":["appDataFolder"]}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
        )
    }

    private fun updateBackupFile(token: String, fileId: String, data: ByteArray) {
        val metadata = """{"name":"$BACKUP_FILE_NAME"}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
            extraHeaders = mapOf("X-HTTP-Method-Override" to "PATCH"),
        )
    }

    private fun downloadBackupFile(token: String, fileId: String): ByteArray {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        return requestBytes(url = url, method = "GET", token = token, contentType = null, body = null)
    }

    private fun multipartUpload(
        url: String,
        method: String,
        token: String,
        metadataJson: String,
        media: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val boundary = "gyst-${System.currentTimeMillis()}"
        val newline = "\r\n"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary$newline".toByteArray())
            write("Content-Type: application/json; charset=UTF-8$newline$newline".toByteArray())
            write(metadataJson.toByteArray())
            write(newline.toByteArray())
            write("--$boundary$newline".toByteArray())
            write("Content-Type: application/octet-stream$newline$newline".toByteArray())
            write(media)
            write(newline.toByteArray())
            write("--$boundary--$newline".toByteArray())
        }.toByteArray()

        requestBytes(
            url = url,
            method = method,
            token = token,
            contentType = "multipart/related; boundary=$boundary",
            body = body,
            extraHeaders = extraHeaders,
        )
    }

    private fun requestText(url: String, method: String, token: String): String {
        val bytes = requestBytes(url, method, token, contentType = null, body = null)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requestBytes(
        url: String,
        method: String,
        token: String?,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            setRequestProperty("Accept", "application/json")
            contentType?.let { setRequestProperty("Content-Type", it) }
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            doInput = true
        }

        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.use { it.readAllBytes() } ?: ByteArray(0)
        if (code !in 200..299) {
            AppLogger.e(
                TAG,
                "HTTP $code from ${url.substringBefore('?')}: ${response.toString(Charsets.UTF_8).take(1200)}",
            )
            error("Google API error ($code): ${response.toString(Charsets.UTF_8)}")
        }
        return response
    }

    private fun revokeToken(token: String) {
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        val url = "https://oauth2.googleapis.com/revoke?token=$encodedToken"
        runCatching {
            requestBytes(url = url, method = "POST", token = token, contentType = "application/x-www-form-urlencoded", body = ByteArray(0))
        }
    }

    private fun readDatabaseBytes(): ByteArray {
        if (!dbPath.exists()) error("Local database was not found")
        return Files.readAllBytes(dbPath)
    }

    private fun writeDatabaseBytes(content: ByteArray) {
        verifySqliteContent(content)
        Files.createDirectories(dbPath.parent)
        val tmp = dbPath.resolveSibling("${dbPath.fileName}.tmp")
        val previous = dbPath.resolveSibling("${dbPath.fileName}.bak")

        Files.write(tmp, content)
        runCatching { Files.deleteIfExists(dbPath.resolveSibling("${dbPath.fileName}-wal")) }
        runCatching { Files.deleteIfExists(dbPath.resolveSibling("${dbPath.fileName}-shm")) }

        if (Files.exists(dbPath)) {
            Files.copy(dbPath, previous, StandardCopyOption.REPLACE_EXISTING)
        }

        runCatching {
            Files.move(tmp, dbPath, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { throwable ->
            if (Files.exists(previous)) {
                Files.copy(previous, dbPath, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(tmp)
            throw throwable
        }
    }

    private fun verifySqliteContent(bytes: ByteArray) {
        val signature = "SQLite format 3".encodeToByteArray()
        if (bytes.size < signature.size) error("Invalid backup format")
        for (index in signature.indices) {
            if (bytes[index] != signature[index]) error("Backup is not a valid SQLite database")
        }
    }

    private fun fileUpdatedAt(path: Path): Instant {
        val millis = Files.getLastModifiedTime(path).toMillis()
        return Instant.fromEpochMilliseconds(millis)
    }

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

private data class RemoteBackupFile(
    val id: String,
    val modifiedAt: Instant?,
)

private data class DesktopOAuthConfig(
    val clientId: String,
    val clientSecret: String,
)

private data class GoogleProfile(
    val name: String?,
    val email: String?,
    val photoUrl: String?,
)
