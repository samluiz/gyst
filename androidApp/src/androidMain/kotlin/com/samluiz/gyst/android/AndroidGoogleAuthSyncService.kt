package com.samluiz.gyst.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val BACKUP_FILE_NAME = "gyst-backup.db"
private const val BACKUP_META_FILE_NAME = "gyst-backup-meta.json"

@Suppress("DEPRECATION")
class AndroidGoogleAuthSyncService(
    private val appContext: Context,
) : GoogleAuthSyncService {
    private companion object {
        const val TAG = "AndroidGoogleSync"
    }

    private val internal = MutableStateFlow(
        GoogleSyncState(
            isAvailable = true,
            isSignedIn = false,
        )
    )
    override val state: StateFlow<GoogleSyncState> = internal.asStateFlow()

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
        .build()

    private val signInClient = runCatching { GoogleSignIn.getClient(appContext, signInOptions) }.getOrNull()

    private var signInLauncher: ActivityResultLauncher<android.content.Intent>? = null
    private var pendingSignIn: ((Result<GoogleSignInAccount>) -> Unit)? = null

    fun bind(activity: ComponentActivity) {
        if (signInLauncher != null) return
        signInLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingSignIn ?: return@registerForActivityResult
            pendingSignIn = null
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            runCatching { task.getResult(ApiException::class.java) }
                .onSuccess { callback(Result.success(it)) }
                .onFailure { callback(Result.failure(it)) }
        }
    }

    override suspend fun initialize() {
        runCatching { refreshState(lastError = null, lastErrorCode = null) }
            .onFailure {
                AppLogger.e(TAG, "Initialize failed", it)
                internal.update { state ->
                    state.copy(
                        isAvailable = false,
                        isSignedIn = false,
                        isAuthInProgress = false,
                        isSyncing = false,
                        lastError = it.message,
                        lastErrorCode = GoogleSyncErrorCode.OAUTH_NOT_CONFIGURED,
                    )
                }
            }
        if (internal.value.isSignedIn) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = fetchToken(requireSignedInAccount())
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
        if (signInClient == null) {
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
        if (isSignedInWithScope()) {
            refreshState(lastError = null, lastErrorCode = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = fetchToken(requireSignedInAccount())
                    findBackupFile(token)?.modifiedAt?.toString()
                }
            }.onSuccess { remoteTs ->
                internal.update { it.copy(lastCloudBackupAtIso = remoteTs) }
            }
            return
        }
        val launcher = signInLauncher ?: throw IllegalStateException("Google Sign-In not bound to Activity")
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    pendingSignIn = { result ->
                        result
                            .onSuccess { continuation.resume(Unit) }
                            .onFailure { continuation.resumeWithException(it) }
                    }
                    launcher.launch(signInClient.signInIntent)
                    continuation.invokeOnCancellation { pendingSignIn = null }
                }
            }
            refreshState(lastError = null, lastErrorCode = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = fetchToken(requireSignedInAccount())
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
        if (signInClient == null) {
            internal.update {
                it.copy(
                    isAvailable = false,
                    isSignedIn = false,
                    accountName = null,
                    accountEmail = null,
                    accountPhotoUrl = null,
                    isAuthInProgress = false,
                    isSyncing = false,
                    statusMessage = null,
                    requiresAppRestart = false,
                    lastError = null,
                    lastErrorCode = null,
                )
            }
            return
        }
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                signInClient.signOut()
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        }
        internal.update {
            it.copy(
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

    override suspend fun syncNow() {
        AppLogger.i(TAG, "Sync requested")
        internal.update { it.copy(isSyncing = true, statusMessage = null, lastError = null, lastErrorCode = null) }
        try {
            val account = requireSignedInAccount()
            val token = withContext(Dispatchers.IO) { fetchToken(account) }
            withContext(Dispatchers.IO) {
                val dbFile = localDbFile()
                val localBytes = readDatabaseBytes(dbFile)
                val localUpdatedAt = Instant.fromEpochMilliseconds(dbFile.lastModified())
                val remote = findBackupFile(token)

                if (remote == null) {
                    createBackupFile(token, localBytes)
                    upsertBackupMeta(token, localUpdatedAt)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastCloudBackupAtIso = Clock.System.now().toString(),
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
                    writeDatabaseBytes(remoteBytes, dbFile)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastCloudBackupAtIso = remoteUpdatedAt?.toString(),
                            lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                            lastSyncPolicy = SyncPolicy.NEWEST_WINS,
                            hadSyncConflict = true,
                            statusMessage = "Conflict resolved by timestamp: cloud data applied.",
                            requiresAppRestart = true,
                            lastError = null,
                            lastErrorCode = null,
                        )
                    }
                } else {
                    updateBackupFile(token, remote.id, localBytes)
                    upsertBackupMeta(token, localUpdatedAt)
                    internal.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncAtIso = Clock.System.now().toString(),
                            lastCloudBackupAtIso = Clock.System.now().toString(),
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
            val account = requireSignedInAccount()
            val token = withContext(Dispatchers.IO) { fetchToken(account) }
            var restoredBackupAtIso: String? = null
            withContext(Dispatchers.IO) {
                val file = findBackupFile(token) ?: throw IllegalStateException("No backup found on Google Drive")
                val remoteBytes = downloadBackupFile(token, file.id)
                writeDatabaseBytes(remoteBytes, localDbFile())
                restoredBackupAtIso = file.modifiedAt?.toString()
            }
            internal.update {
                it.copy(
                    isSyncing = false,
                    lastSyncAtIso = Clock.System.now().toString(),
                    lastCloudBackupAtIso = restoredBackupAtIso,
                    lastSyncSource = SyncSource.CLOUD_TO_LOCAL,
                    lastSyncPolicy = SyncPolicy.OVERWRITE_LOCAL,
                    hadSyncConflict = false,
                    statusMessage = "Backup restored from cloud.",
                    requiresAppRestart = true,
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

    private fun refreshState(lastError: String?, lastErrorCode: GoogleSyncErrorCode?) {
        val signedAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appContext) }.getOrNull()
            ?.takeIf { GoogleSignIn.hasPermissions(it, Scope(DRIVE_APPDATA_SCOPE)) }
        val signed = signedAccount != null
        internal.update {
            it.copy(
                isAvailable = signInClient != null,
                isSignedIn = signed,
                accountName = signedAccount?.displayName,
                accountEmail = signedAccount?.email,
                accountPhotoUrl = signedAccount?.photoUrl?.toString(),
                lastCloudBackupAtIso = if (signed) it.lastCloudBackupAtIso else null,
                isAuthInProgress = false,
                requiresAppRestart = false,
                hadSyncConflict = false,
                lastError = lastError,
                lastErrorCode = lastErrorCode,
            )
        }
    }

    private fun isSignedInWithScope(): Boolean {
        if (signInClient == null) return false
        val account = GoogleSignIn.getLastSignedInAccount(appContext) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_APPDATA_SCOPE))
    }

    private fun requireSignedInAccount(): GoogleSignInAccount {
        val account = GoogleSignIn.getLastSignedInAccount(appContext)
        if (account == null || !GoogleSignIn.hasPermissions(account, Scope(DRIVE_APPDATA_SCOPE))) {
            throw IllegalStateException("Google account is not authenticated for Drive sync")
        }
        return account
    }

    private fun fetchToken(account: GoogleSignInAccount): String {
        val selected = account.account ?: throw IllegalStateException("Account reference is missing")
        val credential = GoogleAccountCredential.usingOAuth2(appContext, listOf(DRIVE_APPDATA_SCOPE))
        credential.selectedAccount = selected
        return credential.token ?: throw IllegalStateException("Failed to obtain Google access token")
    }

    private fun localDbFile(): File = appContext.getDatabasePath("gyst.db")

    private fun readDatabaseBytes(dbFile: File = localDbFile()): ByteArray {
        if (!dbFile.exists()) throw IllegalStateException("Local database was not found")
        checkpointWal(dbFile)
        return dbFile.readBytes()
    }

    private fun writeDatabaseBytes(content: ByteArray, dbFile: File = localDbFile()) {
        verifySqliteContent(content)
        dbFile.parentFile?.mkdirs()
        val tmp = File(dbFile.parentFile, "${dbFile.name}.tmp")
        val backup = File(dbFile.parentFile, "${dbFile.name}.bak")
        tmp.writeBytes(content)
        try {
            runCatching { checkpointWal(dbFile) }
            runCatching { File("${dbFile.path}-wal").delete() }
            runCatching { File("${dbFile.path}-shm").delete() }
            if (backup.exists()) backup.delete()
            if (dbFile.exists() && !dbFile.renameTo(backup)) {
                throw IllegalStateException("Failed to create local backup before restore")
            }
            if (!tmp.renameTo(dbFile)) {
                throw IllegalStateException("Failed to move downloaded database into place")
            }
            if (backup.exists()) backup.delete()
        } catch (t: Throwable) {
            if (!dbFile.exists() && backup.exists()) {
                runCatching { backup.renameTo(dbFile) }
            }
            throw t
        } finally {
            if (tmp.exists()) tmp.delete()
        }
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
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            runCatching {
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
            }
        }
    }

    private fun findBackupFile(token: String): RemoteBackupFile? {
        val query = "name='$BACKUP_FILE_NAME' and 'appDataFolder' in parents and trashed=false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url =
            "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&spaces=appDataFolder&orderBy=modifiedTime desc&fields=files(id,name,modifiedTime)"
        val response = requestText(url, "GET", token)
        val root = JSONObject(response)
        val files = root.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val candidates = buildList {
            for (i in 0 until files.length()) {
                val file = files.optJSONObject(i) ?: continue
                val id = file.optString("id").takeIf { it.isNotBlank() } ?: continue
                val modifiedAt = file.optString("modifiedTime")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                add(RemoteBackupFile(id = id, modifiedAt = modifiedAt))
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<RemoteBackupFile> { it.modifiedAt ?: Instant.DISTANT_PAST }
                .thenBy { it.id }
        )
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

    private fun upsertBackupMeta(token: String, localUpdatedAt: Instant) {
        val metadata = JSONObject().apply {
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

    private fun createMetaFile(token: String, data: ByteArray) {
        val metadata = """{"name":"$BACKUP_META_FILE_NAME","parents":["appDataFolder"]}"""
        multipartUpload(
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            method = "POST",
            token = token,
            metadataJson = metadata,
            media = data,
        )
    }

    private fun updateMetaFile(token: String, fileId: String, data: ByteArray) {
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
        val boundary = "gyst-${Random.nextInt(100_000, 999_999)}"
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
        token: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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

private fun classifyGoogleError(t: Throwable, isRestore: Boolean = false): GoogleSyncErrorCode {
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
        "invalid backup" in msg || "not a valid sqlite" in msg || "invalid backup format" in msg -> GoogleSyncErrorCode.INVALID_BACKUP
        "network" in msg || "timeout" in msg || "unable to resolve host" in msg -> GoogleSyncErrorCode.NETWORK
        "api error" in msg || "drive api" in msg -> GoogleSyncErrorCode.API
        else -> if (isRestore) GoogleSyncErrorCode.RESTORE_FAILED else GoogleSyncErrorCode.SYNC_FAILED
    }
}
