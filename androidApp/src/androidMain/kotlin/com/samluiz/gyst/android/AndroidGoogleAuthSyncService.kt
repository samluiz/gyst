package com.samluiz.gyst.android

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.samluiz.gyst.data.repository.DatabaseRuntimeController
import com.samluiz.gyst.db.GystDatabase
import com.samluiz.gyst.domain.service.GoogleAuthSyncService
import com.samluiz.gyst.domain.service.GoogleSyncErrorCode
import com.samluiz.gyst.domain.service.GoogleSyncState
import com.samluiz.gyst.domain.service.SyncPolicy
import com.samluiz.gyst.domain.service.SyncSource
import com.samluiz.gyst.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val OPENID_SCOPE = "openid"
private const val USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
private const val USERINFO_PROFILE_SCOPE = "https://www.googleapis.com/auth/userinfo.profile"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"
private const val GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
private const val BACKUP_FILE_NAME = "gyst-backup.db"
private const val BACKUP_META_FILE_NAME = "gyst-backup-meta.json"

class AndroidGoogleAuthSyncService(
    private val appContext: Context,
    private val databaseRuntimeController: DatabaseRuntimeController,
) : GoogleAuthSyncService {
    private companion object {
        const val TAG = "AndroidGoogleSync"
    }

    private val internal =
        MutableStateFlow(
            GoogleSyncState(
                isAvailable = true,
                isSignedIn = false,
            ),
        )
    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    private val requestedScopes =
        listOf(
            Scope(DRIVE_APPDATA_SCOPE),
            Scope(OPENID_SCOPE),
            Scope(USERINFO_EMAIL_SCOPE),
            Scope(USERINFO_PROFILE_SCOPE),
        )
    private val authorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

    private val authorizationClient = runCatching { Identity.getAuthorizationClient(appContext) }.getOrNull()

    private var authorizationLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingAuthorization: ((Result<AuthorizationResult>) -> Unit)? = null

    fun bind(activity: ComponentActivity) {
        if (authorizationLauncher != null) return
        authorizationLauncher =
            activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val callback = pendingAuthorization ?: return@registerForActivityResult
                pendingAuthorization = null
                val data = result.data
                if (result.resultCode != Activity.RESULT_OK || data == null) {
                    callback(Result.failure(GoogleAuthorizationCancelledException()))
                    return@registerForActivityResult
                }
                runCatching { requireNotNull(authorizationClient).getAuthorizationResultFromIntent(data) }
                    .onSuccess { callback(Result.success(it)) }
                    .onFailure { callback(Result.failure(it)) }
            }
    }

    override suspend fun initialize() {
        runCatching {
            val result = requestAuthorization(interactive = false)
            activateAuthorization(result)
        }
            .onFailure {
                if (it is GoogleAuthorizationRequiredException) {
                    updateSignedOutState()
                } else {
                    AppLogger.e(TAG, "Initialize failed", it)
                    internal.update { state ->
                        state.copy(
                            isAvailable = authorizationClient != null,
                            isSignedIn = false,
                            isAuthInProgress = false,
                            isSyncing = false,
                            lastError = it.message,
                            lastErrorCode = GoogleSyncErrorCode.OAUTH_NOT_CONFIGURED,
                        )
                    }
                }
            }
        if (internal.value.isSignedIn) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = requireAccessToken()
                    findBackupFile(token)?.modifiedAt?.toString()
                }
            }.onSuccess { remoteTs ->
                internal.update { it.copy(lastCloudBackupAtIso = remoteTs) }
            }
        }
    }

    override suspend fun signIn() {
        AppLogger.i(TAG, "Sign-in requested")
        internal.update { it.copy(lastError = null, lastErrorCode = null, statusMessage = null, isAuthInProgress = true) }
        if (authorizationClient == null) {
            internal.update {
                it.copy(
                    isAvailable = false,
                    isAuthInProgress = false,
                    lastError = "Google Sign-In unavailable on this device/build.",
                    lastErrorCode = GoogleSyncErrorCode.SIGN_IN_UNAVAILABLE,
                )
            }
            return
        }
        try {
            val authorization = requestAuthorization(interactive = true)
            activateAuthorization(authorization)
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = requireAccessToken()
                    findBackupFile(token)?.modifiedAt?.toString()
                }
            }.onSuccess { remoteTs ->
                internal.update { it.copy(lastCloudBackupAtIso = remoteTs) }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Sign-in failed", t)
            internal.update {
                it.copy(
                    lastError = t.message,
                    lastErrorCode = classifyGoogleError(t),
                    statusMessage = null,
                    isAuthInProgress = false,
                )
            }
        }
    }

    override suspend fun signOut() {
        val client = authorizationClient
        val email = internal.value.accountEmail
        if (client != null && !email.isNullOrBlank()) {
            val request =
                RevokeAccessRequest.builder()
                    .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                    .setScopes(requestedScopes)
                    .build()
            client.revokeAccess(request).awaitResult()
        }
        updateSignedOutState()
    }

    override suspend fun syncNow() {
        AppLogger.i(TAG, "Sync requested")
        internal.update { it.copy(isSyncing = true, statusMessage = null, lastError = null, lastErrorCode = null) }
        try {
            val token = requireAccessToken()
            withContext(Dispatchers.IO) {
                val dbFile = localDbFile()
                val localBytes = readDatabaseBytes(dbFile)
                try {
                    val localUpdatedAt = Instant.fromEpochMilliseconds(dbFile.lastModified())
                    val remote = findBackupFile(token)

                    if (remote == null) {
                        createBackupFile(token, localBytes)
                        upsertBackupMeta(token, localUpdatedAt)
                        val syncedCloudIso =
                            findBackupFile(token)?.modifiedAt?.toString() ?: Clock.System.now().toString()
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = syncedCloudIso,
                                lastCloudBackupAtIso = syncedCloudIso,
                                lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = false,
                                statusMessage = "Uploaded local data to Google Drive.",
                                requiresAppRestart = false,
                                lastError = null,
                                lastErrorCode = null,
                            )
                        }
                        return@withContext
                    }

                    val remoteUpdatedAt = remote.modifiedAt
                    if (remoteUpdatedAt != null && remoteUpdatedAt > localUpdatedAt) {
                        val remoteBytes = downloadBackupFile(token, remote.id)
                        try {
                            writeDatabaseBytes(remoteBytes, dbFile)
                        } finally {
                            remoteBytes.fill(0)
                        }
                        val syncedCloudIso = remoteUpdatedAt.toString()
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = syncedCloudIso,
                                lastCloudBackupAtIso = syncedCloudIso,
                                lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = true,
                                statusMessage = "Conflict resolved by timestamp: cloud data applied.",
                                requiresAppRestart = false,
                                lastError = null,
                                lastErrorCode = null,
                            )
                        }
                    } else {
                        updateBackupFile(token, remote.id, localBytes)
                        upsertBackupMeta(token, localUpdatedAt)
                        val syncedCloudIso =
                            findBackupFile(token)?.modifiedAt?.toString() ?: Clock.System.now().toString()
                        internal.update {
                            it.copy(
                                isSyncing = false,
                                lastSyncAtIso = syncedCloudIso,
                                lastCloudBackupAtIso = syncedCloudIso,
                                lastSyncSource = SyncSource.LOCAL_TO_CLOUD,
                                lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                                hadSyncConflict = false,
                                statusMessage = "Synced local data to Google Drive.",
                                requiresAppRestart = false,
                                lastError = null,
                                lastErrorCode = null,
                            )
                        }
                    }
                } finally {
                    localBytes.fill(0)
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Sync failed", t)
            internal.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = null,
                    lastError = t.message,
                    lastErrorCode = classifyGoogleError(t, isRestore = false),
                )
            }
        }
    }

    override suspend fun restoreFromCloud(overwriteLocal: Boolean) {
        if (!overwriteLocal) {
            internal.update {
                it.copy(
                    lastError = "Restore canceled",
                    lastErrorCode = GoogleSyncErrorCode.RESTORE_CANCELED,
                )
            }
            return
        }
        AppLogger.i(TAG, "Restore requested overwriteLocal=$overwriteLocal")
        internal.update { it.copy(isSyncing = true, statusMessage = null, lastError = null, lastErrorCode = null) }
        try {
            val token = requireAccessToken()
            var restoredBackupAtIso: String? = null
            withContext(Dispatchers.IO) {
                val file = findBackupFile(token) ?: throw IllegalStateException("No backup found on Google Drive")
                val remoteBytes = downloadBackupFile(token, file.id)
                try {
                    writeDatabaseBytes(remoteBytes, localDbFile())
                } finally {
                    remoteBytes.fill(0)
                }
                restoredBackupAtIso = file.modifiedAt?.toString()
            }
            internal.update {
                val syncedCloudIso = restoredBackupAtIso ?: Clock.System.now().toString()
                it.copy(
                    isSyncing = false,
                    lastSyncAtIso = syncedCloudIso,
                    lastCloudBackupAtIso = syncedCloudIso,
                    lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                    lastSyncPolicy = SyncPolicy.OVERWRITE_LOCAL,
                    hadSyncConflict = false,
                    statusMessage = "Backup restored from cloud.",
                    requiresAppRestart = false,
                    lastError = null,
                    lastErrorCode = null,
                )
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Restore failed", t)
            internal.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = null,
                    lastError = t.message,
                    lastErrorCode = classifyGoogleError(t, isRestore = true),
                )
            }
        }
    }

    private suspend fun requestAuthorization(interactive: Boolean): AuthorizationResult {
        val client = authorizationClient ?: throw IllegalStateException("Google authorization is unavailable")
        val initial = client.authorize(authorizationRequest).awaitResult()
        if (!initial.hasResolution()) return initial
        if (!interactive) throw GoogleAuthorizationRequiredException()
        val pendingIntent = initial.pendingIntent ?: throw IllegalStateException("Google authorization resolution is missing")
        val launcher = authorizationLauncher ?: throw IllegalStateException("Google authorization is not bound to Activity")
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                pendingAuthorization = { result ->
                    result
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                }
                launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                continuation.invokeOnCancellation { pendingAuthorization = null }
            }
        }
    }

    private suspend fun activateAuthorization(result: AuthorizationResult) {
        val token =
            result.accessToken?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Google authorization did not return an access token")
        val profile = withContext(Dispatchers.IO) { fetchGoogleProfile(token) }
        internal.update {
            it.copy(
                isAvailable = true,
                isSignedIn = true,
                accountName = profile.name,
                accountEmail = profile.email,
                accountPhotoUrl = profile.picture,
                isAuthInProgress = false,
                requiresAppRestart = false,
                hadSyncConflict = false,
                lastError = null,
                lastErrorCode = null,
            )
        }
    }

    private suspend fun requireAccessToken(): String {
        val result = requestAuthorization(interactive = false)
        val token =
            result.accessToken?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Google authorization did not return an access token")
        if (!internal.value.isSignedIn) activateAuthorization(result)
        return token
    }

    private fun fetchGoogleProfile(token: String): GoogleProfile {
        val json = JSONObject(requestText(GOOGLE_USERINFO_URL, "GET", token))
        val email =
            json.optString("email").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Google account email is unavailable")
        return GoogleProfile(
            name = json.optString("name").takeIf(String::isNotBlank),
            email = email,
            picture = json.optString("picture").takeIf(String::isNotBlank),
        )
    }

    private fun updateSignedOutState() {
        internal.update {
            it.copy(
                isAvailable = authorizationClient != null,
                isSignedIn = false,
                accountName = null,
                accountEmail = null,
                accountPhotoUrl = null,
                lastCloudBackupAtIso = null,
                isAuthInProgress = false,
                isSyncing = false,
                statusMessage = null,
                requiresAppRestart = false,
                lastError = null,
                lastErrorCode = null,
            )
        }
    }

    private fun localDbFile(): File = appContext.getDatabasePath("gyst.db")

    private fun readDatabaseBytes(dbFile: File = localDbFile()): ByteArray {
        if (!dbFile.exists()) throw IllegalStateException("Local database was not found")
        checkpointWal(dbFile)
        return dbFile.readBytes()
    }

    private suspend fun writeDatabaseBytes(
        content: ByteArray,
        dbFile: File = localDbFile(),
    ) {
        verifySqliteContent(content)
        dbFile.parentFile?.mkdirs()
        val tmp = File(dbFile.parentFile, "${dbFile.name}.tmp")
        val backup = File(dbFile.parentFile, "${dbFile.name}.bak")
        var previousDatabaseMoved = false
        var replacementDatabaseMoved = false
        tmp.writeBytes(content)
        try {
            verifyRestorableDatabase(tmp)
            databaseRuntimeController.replaceDatabase(
                install = {
                    deleteDatabaseSidecars(dbFile)
                    if (backup.exists() && !backup.delete()) {
                        throw IllegalStateException("Failed to clear stale local backup")
                    }
                    if (dbFile.exists() && !dbFile.renameTo(backup)) {
                        throw IllegalStateException("Failed to create local backup before restore")
                    }
                    previousDatabaseMoved = backup.exists()
                    if (!tmp.renameTo(dbFile)) {
                        throw IllegalStateException("Failed to move downloaded database into place")
                    }
                    replacementDatabaseMoved = true
                },
                rollback = {
                    deleteDatabaseSidecars(dbFile)
                    if (replacementDatabaseMoved && dbFile.exists()) dbFile.delete()
                    if (previousDatabaseMoved && backup.exists() && !backup.renameTo(dbFile)) {
                        throw IllegalStateException("Failed to restore the previous local database")
                    }
                },
                commit = {
                    if (backup.exists()) backup.delete()
                },
            )
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun verifyRestorableDatabase(file: File) {
        SQLiteDatabase
            .openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { database ->
                val healthy =
                    database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                    }
                if (!healthy) throw IllegalStateException("Invalid backup database integrity")
                val schemaVersion =
                    database.rawQuery("PRAGMA user_version", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                    }
                if (schemaVersion <= 0L) {
                    throw IllegalStateException("Invalid backup schema version")
                }
                if (schemaVersion > GystDatabase.Schema.version) {
                    throw IllegalStateException(
                        "Backup schema version $schemaVersion is newer than this app supports",
                    )
                }
            }
    }

    private fun deleteDatabaseSidecars(dbFile: File) {
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
    }

    private fun verifySqliteContent(bytes: ByteArray) {
        val signature = "SQLite format 3".encodeToByteArray()
        if (bytes.size < signature.size) {
            throw IllegalStateException("Invalid backup format")
        }
        for (index in signature.indices) {
            if (bytes[index] != signature[index]) {
                throw IllegalStateException("Backup is not a valid SQLite database")
            }
        }
    }

    private fun checkpointWal(dbFile: File) {
        SQLiteDatabase
            .openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                runCatching {
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
                }
            }
    }

    private fun findBackupFile(token: String): RemoteBackupFile? {
        val query = "name='$BACKUP_FILE_NAME' and 'appDataFolder' in parents and trashed=false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url =
            "https://www.googleapis.com/drive/v3/files?q=$encodedQuery" +
                "&spaces=appDataFolder&orderBy=modifiedTime desc" +
                "&fields=files(id,name,modifiedTime)"
        val response = requestText(url, "GET", token)
        val root = JSONObject(response)
        val files = root.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val candidates =
            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    val id = file.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val modifiedAt =
                        file.optString("modifiedTime")
                            .takeIf { it.isNotBlank() }
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    add(RemoteBackupFile(id = id, modifiedAt = modifiedAt))
                }
            }
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<RemoteBackupFile> { it.modifiedAt ?: Instant.DISTANT_PAST }
                .thenBy { it.id },
        )
    }

    private fun createBackupFile(
        token: String,
        data: ByteArray,
    ) {
        val metadata = """{"name":"$BACKUP_FILE_NAME","parents":["appDataFolder"]}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
        )
    }

    private fun updateBackupFile(
        token: String,
        fileId: String,
        data: ByteArray,
    ) {
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

    private fun upsertBackupMeta(
        token: String,
        localUpdatedAt: Instant,
    ) {
        val metadata =
            JSONObject().apply {
                put("lastLocalUpdatedAtIso", localUpdatedAt.toString())
                put("savedAtIso", Clock.System.now().toString())
                put("policy", SyncPolicy.NEWEST_WINS.name)
                put("source", SyncSource.LOCAL_TO_CLOUD.name)
            }.toString()

        val existing = findMetaFileId(token)
        if (existing == null) {
            createMetaFile(token, metadata.encodeToByteArray())
        } else {
            updateMetaFile(token, existing, metadata.encodeToByteArray())
        }
    }

    private fun findMetaFileId(token: String): String? {
        val query = "name='$BACKUP_META_FILE_NAME' and 'appDataFolder' in parents and trashed=false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&spaces=appDataFolder&fields=files(id,name)"
        val response = requestText(url, "GET", token)
        val root = JSONObject(response)
        val files = root.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun createMetaFile(
        token: String,
        data: ByteArray,
    ) {
        val metadata = """{"name":"$BACKUP_META_FILE_NAME","parents":["appDataFolder"]}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
        )
    }

    private fun updateMetaFile(
        token: String,
        fileId: String,
        data: ByteArray,
    ) {
        val metadata = """{"name":"$BACKUP_META_FILE_NAME"}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
            extraHeaders = mapOf("X-HTTP-Method-Override" to "PATCH"),
        )
    }

    private fun downloadBackupFile(
        token: String,
        fileId: String,
    ): ByteArray {
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
        val boundary = "gyst-${Random.nextInt(100_000, 999_999)}"
        val newline = "\r\n"
        val body =
            ByteArrayOutputStream().apply {
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

        try {
            requestBytes(
                url = url,
                method = method,
                token = token,
                contentType = "multipart/related; boundary=$boundary",
                body = body,
                extraHeaders = extraHeaders,
            )
        } finally {
            body.fill(0)
        }
    }

    private fun requestText(
        url: String,
        method: String,
        token: String,
    ): String {
        val bytes = requestBytes(url, method, token, contentType = null, body = null)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requestBytes(
        url: String,
        method: String,
        token: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
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
        val response = stream?.readAllBytesCompat() ?: ByteArray(0)
        if (code !in 200..299) {
            AppLogger.e(
                TAG,
                "HTTP $code from ${url.substringBefore('?')}: ${response.toString(Charsets.UTF_8).take(1200)}",
            )
            throw IllegalStateException("Drive API error ($code): ${response.toString(Charsets.UTF_8)}")
        }
        return response
    }
}

private data class RemoteBackupFile(
    val id: String,
    val modifiedAt: Instant?,
)

private data class GoogleProfile(
    val name: String?,
    val email: String,
    val picture: String?,
)

private class GoogleAuthorizationRequiredException : IllegalStateException("Google Drive authorization is required")

private class GoogleAuthorizationCancelledException : IllegalStateException("Google authorization was canceled")

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.resumeWithException(GoogleAuthorizationCancelledException())
        }
    }

private fun InputStream.readAllBytesCompat(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val count = read(chunk)
        if (count <= 0) break
        buffer.write(chunk, 0, count)
    }
    return buffer.toByteArray()
}

private fun classifyGoogleError(
    t: Throwable,
    isRestore: Boolean = false,
): GoogleSyncErrorCode {
    if (t is GoogleAuthorizationCancelledException) return GoogleSyncErrorCode.SIGN_IN_CANCELED
    if (t is GoogleAuthorizationRequiredException) return GoogleSyncErrorCode.ACCOUNT_NOT_AUTHENTICATED
    if (t is ApiException) {
        return when (t.statusCode) {
            7 -> GoogleSyncErrorCode.NETWORK
            10 -> GoogleSyncErrorCode.SIGN_IN_CONFIG_MISMATCH
            16, 12501 -> GoogleSyncErrorCode.SIGN_IN_CANCELED
            12500, 12502 -> GoogleSyncErrorCode.SIGN_IN_FAILED
            else -> GoogleSyncErrorCode.SIGN_IN_FAILED
        }
    }
    val msg = t.message.orEmpty().lowercase()
    return when {
        "not authenticated" in msg -> GoogleSyncErrorCode.ACCOUNT_NOT_AUTHENTICATED
        "access token" in msg || "token" in msg && "failed" in msg -> GoogleSyncErrorCode.ACCESS_TOKEN_FAILED
        "no backup found" in msg -> GoogleSyncErrorCode.BACKUP_NOT_FOUND
        "invalid backup" in msg ||
            "backup schema version" in msg ||
            "not a valid sqlite" in msg ||
            "invalid backup format" in msg -> GoogleSyncErrorCode.INVALID_BACKUP
        "network" in msg || "timeout" in msg || "unable to resolve host" in msg -> GoogleSyncErrorCode.NETWORK
        "api error" in msg || "drive api" in msg -> GoogleSyncErrorCode.API
        else -> if (isRestore) GoogleSyncErrorCode.RESTORE_FAILED else GoogleSyncErrorCode.SYNC_FAILED
    }
}
