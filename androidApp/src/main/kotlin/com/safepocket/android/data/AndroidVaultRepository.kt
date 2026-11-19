package com.safepocket.android.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.sqldelight.db.SqlDriver
import com.safepocket.db.SafePocketDatabase
import com.safepocket.db.Vault_items
import com.safepocket.shared.backup.ContainerInspectionSummary
import com.safepocket.shared.backup.CsvAndOnePasswordPortability
import com.safepocket.shared.backup.VaultImportReport
import com.safepocket.shared.backup.VaultPortabilityService
import com.safepocket.shared.brand.DownloadedBrandAsset
import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.brand.InstitutionLogoFetcher
import com.safepocket.shared.crypto.AndroidCryptoPrimitives
import com.safepocket.shared.crypto.AndroidSqlCipherDriverFactory
import com.safepocket.shared.crypto.AndroidZipCodec
import com.safepocket.shared.crypto.Argon2idParameters
import com.safepocket.shared.crypto.HkdfSha256Pure
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import com.safepocket.shared.error.SafePocketErrorHandler
import com.safepocket.shared.intelligence.SmartFieldIntelligenceEngine
import com.safepocket.shared.intelligence.WebsiteReachabilityResult
import com.safepocket.shared.model.VaultItemPayload
import com.safepocket.shared.model.VaultStorageMode
import com.safepocket.shared.onboarding.EmergencyKitGenerator
import com.safepocket.shared.sync.CloudAuthExpiredException
import com.safepocket.shared.sync.CloudSyncAdapter
import com.safepocket.shared.sync.CloudSyncUserSession
import com.safepocket.shared.sync.CrdtReconciler
import com.safepocket.shared.sync.EncryptedSyncRecord
import com.safepocket.shared.sync.FirebaseSyncAdapter
import com.safepocket.shared.sync.FirebaseZeroKnowledgeAuthService
import com.safepocket.shared.sync.GoogleDriveSyncAdapter
import com.safepocket.shared.sync.HybridLogicalClock
import com.safepocket.shared.sync.ProvisionedFirebaseProject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

enum class SyncBadgeStatus {
    LOCAL_ONLY,
    CLOUD_SYNCED,
    SYNCING,
    CLOUD_OFFLINE_ARMED,
    SYNC_ERROR
}

data class SyncIndicatorSnapshot(
    val status: SyncBadgeStatus,
    val storageMode: VaultStorageMode,
    val isOnline: Boolean,
    val queuedMutationCount: Int,
    val signedInEmail: String?,
    val lastSyncedIso: String?,
    val errorDetail: String? = null
)

data class LocalEncryptedDbStatus(
    val storageMode: VaultStorageMode,
    val isSqlCipherActive: Boolean,
    val backendLabel: String,
    val databaseFileName: String,
    val databaseSizeBytes: Long,
    val activeItemCount: Int,
    val tombstoneCount: Int,
    val vaultId: String,
    val secretKeyFormatted: String,
    val kdfSaltHex: String,
    val hasCustomMasterPassword: Boolean
)

data class CloudSyncStatusSnapshot(
    val storageMode: VaultStorageMode,
    val cloudSyncEnabled: Boolean,
    val isNetworkOnline: Boolean,
    val queuedOfflineMutations: Int,
    val projectId: String = ProvisionedFirebaseProject.PROJECT_ID,
    val region: String = ProvisionedFirebaseProject.FIRESTORE_REGION,
    val signedInEmail: String?,
    val signedInUid: String?,
    val lastSyncedPushed: Int,
    val lastSyncedPulled: Int,
    val lastSyncedIso: String?
)

data class BidirectionalSyncReport(
    val pushedCount: Int,
    val pulledCount: Int,
    val updatedItems: List<VaultItemPayload>
)

/**
 * SafePocket v0.3 Repository:
 * - Strictly mutually exclusive `VaultStorageMode.LOCAL_ONLY` XOR `VaultStorageMode.CLOUD_SYNC`
 *   (when Cloud Sync is active, standalone `safepocket_vault.db` is closed and deleted; Cloud
 *   uses session-scoped `safepocket_cloud_offline_cache.db` + encrypted mutation outbox +
 *   `ConnectivityManager.NetworkCallback` for 100% automatic zero-input offline reconciliation)
 * - Website `https://` reachability verifier
 * - One-click Delete All Example Data (without re-seeding on restart)
 * - Structured `SafePocketErrorHandler` wrapping across all operations
 */
class AndroidVaultRepository(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val primitives = AndroidCryptoPrimitives()
    val portabilityService = VaultPortabilityService(primitives)
    val httpClient = HttpClient(OkHttp)

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private fun configuredFirebaseProjectId(): String =
        prefs.getString("cfg_fb_project_id", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ProvisionedFirebaseProject.PROJECT_ID

    private fun configuredFirebaseApiKey(): String =
        prefs.getString("cfg_fb_api_key", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ProvisionedFirebaseProject.WEB_API_KEY

    private fun configuredFirebaseBucket(): String =
        prefs.getString("cfg_fb_bucket", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ProvisionedFirebaseProject.STORAGE_BUCKET

    private fun selectedCloudProviderId(): String =
        prefs.getString("cloud_provider_id", "FIREBASE")?.trim()?.uppercase() ?: "FIREBASE"

    private fun configuredGdriveToken(): String =
        prefs.getString("cfg_gdrive_token", null)?.trim().orEmpty()

    private fun isGoogleDriveConfigured(): Boolean =
        selectedCloudProviderId() == "GDRIVE_APPDATA" && configuredGdriveToken().isNotEmpty()

    private fun hasActiveCloudAuth(): Boolean =
        if (selectedCloudProviderId() == "GDRIVE_APPDATA") {
            configuredGdriveToken().isNotEmpty()
        } else {
            firebaseAuth.currentUser != null
        }

    val firebaseAuth = FirebaseZeroKnowledgeAuthService(
        httpClient = httpClient,
        primitives = primitives,
        apiKeyProvider = ::configuredFirebaseApiKey,
        projectIdProvider = ::configuredFirebaseProjectId,
        onSessionRefreshed = ::saveFirebaseSession
    )

    private val firebaseAdapter = FirebaseSyncAdapter(
        httpClient = httpClient,
        projectIdProvider = ::configuredFirebaseProjectId,
        storageBucketProvider = ::configuredFirebaseBucket,
        userIdProvider = {
            firebaseAuth.refreshIdTokenIfPossible(force = false)?.uid
                ?: firebaseAuth.currentUser?.uid
                ?: error("Not signed in to Firebase Cloud Sync")
        },
        idTokenProvider = {
            firebaseAuth.refreshIdTokenIfPossible(force = false)?.idToken
                ?: firebaseAuth.currentUser?.idToken
                ?: error("Not signed in to Firebase Cloud Sync")
        },
        forceRefreshTokenProvider = {
            firebaseAuth.refreshIdTokenIfPossible(force = true)?.idToken
        }
    )

    private val googleDriveAdapter = GoogleDriveSyncAdapter(
        httpClient = httpClient,
        oauthAccessTokenProvider = { configuredGdriveToken() }
    )

    private fun activeSyncAdapter(): CloudSyncAdapter =
        if (selectedCloudProviderId() == "GDRIVE_APPDATA") googleDriveAdapter else firebaseAdapter

    private val nodeId: String = prefs.getString(KEY_NODE_ID, null) ?: run {
        val generated = "and-${ZeroKnowledgeEncryptionService.bytesToHex(primitives.secureRandomBytes(3))}"
        prefs.edit().putString(KEY_NODE_ID, generated).apply()
        generated
    }

    private var hlcLogicalCounter: Int = 0
    private var sqlDriver: SqlDriver? = null
    private var database: SafePocketDatabase? = null
    private var openedDbFileName: String? = null
    private var activeVaultItemKey32: ByteArray = ByteArray(32)
    private var sqlCipherActive: Boolean = false
    private var latestKnownItems: List<VaultItemPayload> = emptyList()

    private val _isNetworkOnline = MutableStateFlow(queryInitialNetworkOnline())
    val isNetworkOnline: StateFlow<Boolean> = _isNetworkOnline.asStateFlow()

    private val _autoReconciledItems = MutableSharedFlow<List<VaultItemPayload>>(extraBufferCapacity = 4)
    val autoReconciledItems: SharedFlow<List<VaultItemPayload>> = _autoReconciledItems.asSharedFlow()

    private val _syncIndicatorFlow = MutableStateFlow(
        SyncIndicatorSnapshot(
            status = SyncBadgeStatus.LOCAL_ONLY,
            storageMode = VaultStorageMode.LOCAL_ONLY,
            isOnline = _isNetworkOnline.value,
            queuedMutationCount = 0,
            signedInEmail = null,
            lastSyncedIso = null
        )
    )
    val syncIndicatorFlow: StateFlow<SyncIndicatorSnapshot> = _syncIndicatorFlow.asStateFlow()

    init {
        val savedSessionJson = prefs.getString(KEY_FIREBASE_SESSION, null)
        if (!savedSessionJson.isNullOrBlank()) {
            runCatching {
                json.decodeFromString<CloudSyncUserSession>(savedSessionJson)
            }.getOrNull()?.let { firebaseAuth.restoreSession(it) }
        }
        // Migrate v0.2 cloud_sync_enabled flag into v0.3 VaultStorageMode
        if (!prefs.contains(KEY_STORAGE_MODE)) {
            val migratedMode = if (prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false) && hasActiveCloudAuth()) {
                VaultStorageMode.CLOUD_SYNC
            } else {
                VaultStorageMode.LOCAL_ONLY
            }
            prefs.edit().putString(KEY_STORAGE_MODE, migratedMode.name).apply()
        }
        refreshSyncIndicator()
        registerAutomaticOfflineConnectivityWatcher()
    }

    private fun queryInitialNetworkOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun registerAutomaticOfflineConnectivityWatcher() {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isNetworkOnline.value = true
                    refreshSyncIndicator()
                    // Zero-user-input automatic Cloud Sync reconciliation when returning online!
                    if (storageMode == VaultStorageMode.CLOUD_SYNC && hasActiveCloudAuth()) {
                        backgroundScope.launch {
                            runCatching {
                                val report = syncNow(latestKnownItems, surfaceErrorBanner = false)
                                _autoReconciledItems.tryEmit(report.updatedItems)
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    _isNetworkOnline.value = queryInitialNetworkOnline()
                    refreshSyncIndicator()
                }
            })
        }
    }

    var storageMode: VaultStorageMode
        get() {
            val raw = prefs.getString(KEY_STORAGE_MODE, VaultStorageMode.LOCAL_ONLY.name)
            val parsed = runCatching { VaultStorageMode.valueOf(raw!!) }.getOrDefault(VaultStorageMode.LOCAL_ONLY)
            return if (parsed == VaultStorageMode.CLOUD_SYNC && !hasActiveCloudAuth()) {
                VaultStorageMode.LOCAL_ONLY
            } else {
                parsed
            }
        }
        private set(value) {
            prefs.edit()
                .putString(KEY_STORAGE_MODE, value.name)
                .putBoolean(KEY_CLOUD_SYNC_ENABLED, value == VaultStorageMode.CLOUD_SYNC)
                .apply()
            refreshSyncIndicator()
        }

    val sampleDataPurged: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE_DATA_PURGED, false)

    var autoDownloadLogos: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LOGOS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LOGOS, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply()
        }

    val secretKeyFormatted: String
        get() = prefs.getString(KEY_SECRET_KEY_FORMATTED, null) ?: run {
            val (formatted, _) = ZeroKnowledgeEncryptionService(primitives).generateLocalSecretKey()
            prefs.edit().putString(KEY_SECRET_KEY_FORMATTED, formatted).apply()
            formatted
        }

    val kdfSaltHex: String
        get() = ensureSaltInitializedSync()

    val vaultId: String
        get() = "vault-${kdfSaltHex.take(12)}"

    val hasCustomMasterPassword: Boolean
        get() = prefs.getBoolean(KEY_HAS_CUSTOM_MASTER_PW, false)

    private fun activeTargetDbFileName(): String = LOCAL_STANDALONE_DB_NAME

    private fun loadPendingCloudOutbox(): List<EncryptedSyncRecord> {
        val raw = prefs.getString(KEY_CLOUD_OUTBOX_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<EncryptedSyncRecord>>(raw) }.getOrDefault(emptyList())
    }

    private fun savePendingCloudOutbox(records: List<EncryptedSyncRecord>) {
        if (records.isEmpty()) {
            prefs.edit().remove(KEY_CLOUD_OUTBOX_JSON).apply()
        } else {
            prefs.edit().putString(KEY_CLOUD_OUTBOX_JSON, json.encodeToString(records)).apply()
        }
        refreshSyncIndicator()
    }

    private fun enqueueCloudOutboxRecord(record: EncryptedSyncRecord) {
        val existing = loadPendingCloudOutbox().filterNot { it.entityId == record.entityId }
        savePendingCloudOutbox(existing + record)
    }

    private fun refreshSyncIndicator(
        explicitSyncing: Boolean = false,
        errorMessage: String? = null
    ) {
        val mode = storageMode
        val online = _isNetworkOnline.value
        val queued = loadPendingCloudOutbox().size
        val badge = when {
            mode == VaultStorageMode.LOCAL_ONLY -> SyncBadgeStatus.LOCAL_ONLY
            explicitSyncing -> SyncBadgeStatus.SYNCING
            !errorMessage.isNullOrBlank() && online -> SyncBadgeStatus.SYNC_ERROR
            !online || queued > 0 -> SyncBadgeStatus.CLOUD_OFFLINE_ARMED
            else -> SyncBadgeStatus.CLOUD_SYNCED
        }
        _syncIndicatorFlow.value = SyncIndicatorSnapshot(
            status = badge,
            storageMode = mode,
            isOnline = online,
            queuedMutationCount = queued,
            signedInEmail = firebaseAuth.currentUser?.email,
            lastSyncedIso = prefs.getString(KEY_LAST_SYNC_ISO, null),
            errorDetail = errorMessage
        )
    }

    private suspend fun deterministicSaltHexForSecretKey(secretKey: String): String {
        val digest = primitives.sha256("safepocket-2skd-salt-v1:${secretKey.trim().uppercase()}".encodeToByteArray())
        return ZeroKnowledgeEncryptionService.bytesToHex(digest.copyOfRange(0, 16))
    }

    private fun ensureSaltInitializedSync(): String {
        val existing = prefs.getString(KEY_KDF_SALT_HEX, null)
        if (!existing.isNullOrBlank()) return existing
        val md = java.security.MessageDigest.getInstance("SHA-256")
            .digest("safepocket-2skd-salt-v1:${secretKeyFormatted.trim().uppercase()}".encodeToByteArray())
        val hex = ZeroKnowledgeEncryptionService.bytesToHex(md.copyOfRange(0, 16))
        prefs.edit().putString(KEY_KDF_SALT_HEX, hex).apply()
        return hex
    }

    private fun ensureActiveDatabaseOpened() {
        val desiredFile = activeTargetDbFileName()
        if (database != null && openedDbFileName == desiredFile) return
        closeCurrentDatabase()
        val sqlCipherKey = loadOrCreateSqlCipherRawKey()
        runCatching {
            val drv = AndroidSqlCipherDriverFactory.createEncryptedDriver(
                context = context,
                rawDatabaseKey32 = sqlCipherKey,
                databaseFileName = desiredFile
            )
            sqlDriver = drv
            database = SafePocketDatabase(drv)
            openedDbFileName = desiredFile
            sqlCipherActive = true
        }.onFailure { err ->
            sqlCipherActive = false
            SafePocketErrorHandler.record("openSqlCipher:$desiredFile", err, System.currentTimeMillis(), surfaceBanner = false)
        }
    }

    private fun closeCurrentDatabase() {
        runCatching { sqlDriver?.close() }
        sqlDriver = null
        database = null
        openedDbFileName = null
    }

    /**
     * Opens the active storage store (`LOCAL_ONLY` standalone SQLCipher DB OR `CLOUD_SYNC`
     * session-scoped offline mirror + automatic background cloud reconciliation).
     */
    suspend fun initializeAndLoadItems(): List<VaultItemPayload> = withContext(Dispatchers.IO) {
        val saltHex = ensureSaltInitializedSync()

        val cachedKeyHex = prefs.getString(KEY_WRAPPED_LOCAL_VEK_HEX, null)
        activeVaultItemKey32 = if (!cachedKeyHex.isNullOrBlank() && cachedKeyHex.length == 64) {
            ZeroKnowledgeEncryptionService.hexToBytes(cachedKeyHex)
        } else {
            val derived = HkdfSha256Pure.deriveTwoSecretKey(
                masterPassword = DEFAULT_DEMO_PASSWORD,
                secretKeyFormatted = secretKeyFormatted,
                saltHex = saltHex,
                memorySizeKiB = 4_096,
                iterations = 2,
                parallelism = 1,
                domainContext = "safepocket-2skd-v1"
            )
            val verifier = computePasswordVerifier(DEFAULT_DEMO_PASSWORD, secretKeyFormatted, saltHex)
            prefs.edit()
                .putString(KEY_WRAPPED_LOCAL_VEK_HEX, ZeroKnowledgeEncryptionService.bytesToHex(derived))
                .putString(KEY_MASTER_PW_VERIFIER, verifier)
                .apply()
            derived
        }

        // Seamless v0.3.6 -> v0.3.7 migration: preserve any existing cloud offline cache DB into unified safepocket_vault.db
        runCatching {
            val localDbFile = context.getDatabasePath(LOCAL_STANDALONE_DB_NAME)
            val legacyCloudCacheFile = context.getDatabasePath(CLOUD_OFFLINE_CACHE_DB_NAME)
            if (legacyCloudCacheFile.exists()) {
                if (!localDbFile.exists() || localDbFile.length() == 0L) {
                    localDbFile.parentFile?.mkdirs()
                    legacyCloudCacheFile.copyTo(localDbFile, overwrite = true)
                    for (suffix in listOf("-wal", "-shm", "-journal")) {
                        val srcSidecar = java.io.File(legacyCloudCacheFile.path + suffix)
                        if (srcSidecar.exists()) {
                            srcSidecar.copyTo(java.io.File(localDbFile.path + suffix), overwrite = true)
                        }
                    }
                }
                context.deleteDatabase(CLOUD_OFFLINE_CACHE_DB_NAME)
            }
        }

        ensureActiveDatabaseOpened()
        val db = database
        if (db != null) {
            val rows = db.safePocketVaultQueries.selectAllActiveItems().executeAsList()
            if (rows.isEmpty() && storageMode == VaultStorageMode.LOCAL_ONLY && !prefs.getBoolean(KEY_DB_INITIALIZED_V02, false)) {
                val legacyRaw = prefs.getString(LEGACY_KEY_VAULT_ITEMS, null)
                val initialItems = AndroidSampleVault.decodeItemsOrDefault(legacyRaw, sampleDataPurged)
                persistSnapshotToDbInternal(db, initialItems)
                prefs.edit().putBoolean(KEY_DB_INITIALIZED_V02, true).apply()
                latestKnownItems = initialItems
                refreshSyncIndicator()
                return@withContext initialItems
            }

            val decrypted = rows.mapNotNull { row -> decryptDbRowOrNull(row) }
                .let { list -> if (sampleDataPurged) list.filterNot { it.isBuiltInSample() } else list }

            if (storageMode == VaultStorageMode.LOCAL_ONLY) {
                prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(decrypted)).apply()
            } else if (_isNetworkOnline.value && firebaseAuth.currentUser != null) {
                // Trigger non-blocking automatic cloud sync on startup when in CLOUD_SYNC mode!
                backgroundScope.launch {
                    runCatching {
                        val rep = syncNow(decrypted, surfaceErrorBanner = false)
                        _autoReconciledItems.tryEmit(rep.updatedItems)
                    }
                }
            }

            latestKnownItems = decrypted
            refreshSyncIndicator()
            return@withContext decrypted
        }

        val fallback = if (storageMode == VaultStorageMode.LOCAL_ONLY) {
            AndroidSampleVault.decodeItemsOrDefault(prefs.getString(LEGACY_KEY_VAULT_ITEMS, null), sampleDataPurged)
        } else {
            emptyList()
        }
        latestKnownItems = fallback
        refreshSyncIndicator()
        fallback
    }

    private fun loadOrCreateSqlCipherRawKey(): ByteArray {
        val hex = prefs.getString(KEY_SQLCIPHER_RAW_HEX, null)
        if (!hex.isNullOrBlank() && hex.length == 64) {
            return ZeroKnowledgeEncryptionService.hexToBytes(hex)
        }
        val fresh = primitives.secureRandomBytes(32)
        prefs.edit().putString(KEY_SQLCIPHER_RAW_HEX, ZeroKnowledgeEncryptionService.bytesToHex(fresh)).apply()
        return fresh
    }

    private suspend fun computePasswordVerifier(
        masterPassword: String,
        secretKey: String,
        saltHex: String
    ): String {
        val bytes = "verifier:$saltHex:$masterPassword:${secretKey.trim().uppercase()}".encodeToByteArray()
        return ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(bytes))
    }

    suspend fun verifyMasterPassword(candidatePassword: String): Boolean = withContext(Dispatchers.Default) {
        if (!hasCustomMasterPassword && candidatePassword.isBlank()) return@withContext true
        val expected = prefs.getString(KEY_MASTER_PW_VERIFIER, null) ?: return@withContext true
        val actual = computePasswordVerifier(candidatePassword, secretKeyFormatted, ensureSaltInitializedSync())
        actual.equals(expected, ignoreCase = true)
    }

    suspend fun verifyWebsiteExists(rawUrl: String): WebsiteReachabilityResult =
        withContext(Dispatchers.IO) {
            SmartFieldIntelligenceEngine.verifyWebsiteExists(rawUrl, httpClient)
        }

    suspend fun configureVaultCredentials(
        newMasterPassword: String,
        newSecretKeyInput: String? = null,
        currentItems: List<VaultItemPayload>
    ) = withContext(Dispatchers.IO) {
        try {
            require(newMasterPassword.length >= 6) { "Master Password must be at least 6 characters" }
            val resolvedSecretKey = parseSecretKeyOrUri(newSecretKeyInput) ?: secretKeyFormatted
            val nextSaltHex = deterministicSaltHexForSecretKey(resolvedSecretKey)

            val nextKey = HkdfSha256Pure.deriveTwoSecretKey(
                masterPassword = newMasterPassword,
                secretKeyFormatted = resolvedSecretKey,
                saltHex = nextSaltHex,
                memorySizeKiB = 16_384,
                iterations = 3,
                parallelism = 2,
                domainContext = "safepocket-2skd-v1"
            )
            val verifier = computePasswordVerifier(newMasterPassword, resolvedSecretKey, nextSaltHex)

            activeVaultItemKey32 = nextKey
            prefs.edit()
                .putString(KEY_SECRET_KEY_FORMATTED, resolvedSecretKey)
                .putString(KEY_KDF_SALT_HEX, nextSaltHex)
                .putString(KEY_WRAPPED_LOCAL_VEK_HEX, ZeroKnowledgeEncryptionService.bytesToHex(nextKey))
                .putString(KEY_MASTER_PW_VERIFIER, verifier)
                .putBoolean(KEY_HAS_CUSTOM_MASTER_PW, true)
                .apply()

            ensureActiveDatabaseOpened()
            database?.let { db -> persistSnapshotToDbInternal(db, currentItems) }
            if (storageMode == VaultStorageMode.CLOUD_SYNC && firebaseAuth.currentUser != null) {
                runCatching { syncNow(currentItems, surfaceErrorBanner = false) }
            }
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("configureVaultCredentials", t, System.currentTimeMillis())
            throw t
        }
    }

    private fun parseSecretKeyOrUri(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("safepocket-recovery://", ignoreCase = true)) {
            val afterQuery = trimmed.substringAfter('?', "")
            for (part in afterQuery.split('&')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "secretKey" && kv[1].isNotBlank()) {
                    return kv[1].trim().uppercase()
                }
            }
        }
        return trimmed.uppercase()
    }

    // =========================================================================
    // MUTUALLY EXCLUSIVE STORAGE MODE TRANSITIONS (`LOCAL_ONLY` XOR `CLOUD_SYNC`)
    // =========================================================================

    /**
     * Switches to `CLOUD_SYNC` mode (`Lokale DB + Echtzeit-Backup über alle Geräte`):
     * 1. Requires a signed-in Cloud Sync account.
     * 2. Keeps unified `safepocket_vault.db` intact on device and reconciles immediately with
     *    Firebase / Google Drive (or queues into automatic offline outbox).
     */
    suspend fun switchToCloudSyncExclusiveMode(
        currentItems: List<VaultItemPayload>
    ): List<VaultItemPayload> = withContext(Dispatchers.IO) {
        check(hasActiveCloudAuth()) {
            "Sign in or create a Cloud Sync account first to activate Cloud Sync mode."
        }
        if (!isGoogleDriveConfigured() && _isNetworkOnline.value) {
            try {
                firebaseAuth.refreshIdTokenIfPossible(force = false)
            } catch (expired: CloudAuthExpiredException) {
                prefs.edit().remove(KEY_FIREBASE_SESSION).apply()
                refreshSyncIndicator(errorMessage = expired.message)
                throw expired
            }
        }

        storageMode = VaultStorageMode.CLOUD_SYNC
        ensureActiveDatabaseOpened()
        database?.let { db -> persistSnapshotToDbInternal(db, currentItems) }
        prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(currentItems)).apply()
        latestKnownItems = currentItems

        return@withContext try {
            val rep = syncNow(currentItems, surfaceErrorBanner = false)
            latestKnownItems = rep.updatedItems
            rep.updatedItems
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            val isAuthOrPermissionError = t is CloudAuthExpiredException ||
                msg.contains("(401)") ||
                msg.contains("(403)") ||
                msg.contains("authentication", ignoreCase = true)
            val userOnly = currentItems.filterNot { it.isBuiltInSample() }
            if (isAuthOrPermissionError) {
                runCatching {
                    val queued = userOnly.map { encryptItemToSyncRecord(it) }
                    savePendingCloudOutbox(queued)
                }
                SafePocketErrorHandler.record(
                    operation = "cloudSync",
                    throwable = t,
                    epochMs = System.currentTimeMillis(),
                    surfaceBanner = true
                )
                throw t
            }
            if (t is IOException || !_isNetworkOnline.value) {
                runCatching {
                    val queued = userOnly.map { encryptItemToSyncRecord(it) }
                    savePendingCloudOutbox(queued)
                }
                currentItems
            } else {
                runCatching {
                    val queued = userOnly.map { encryptItemToSyncRecord(it) }
                    savePendingCloudOutbox(queued)
                }
                throw t
            }
        }
    }

    /**
     * Switches to `LOCAL_ONLY` mode (`Lokale DB`):
     * Pauses network sync while keeping both `safepocket_vault.db` and the signed-in Cloud session
     * intact so the user can toggle back to `Cloud-Sync` in a single click without re-authenticating.
     */
    suspend fun switchToLocalOnlyExclusiveMode(
        currentItems: List<VaultItemPayload>
    ): List<VaultItemPayload> = withContext(Dispatchers.IO) {
        prefs.edit()
            .putBoolean(KEY_DB_INITIALIZED_V02, true)
            .apply()

        storageMode = VaultStorageMode.LOCAL_ONLY
        ensureActiveDatabaseOpened()
        database?.let { db -> persistSnapshotToDbInternal(db, currentItems) }
        prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(currentItems)).apply()
        latestKnownItems = currentItems
        refreshSyncIndicator()
        currentItems
    }

    // =========================================================================
    // VAULT CRUD + DELETE ALL EXAMPLE DATA
    // =========================================================================

    suspend fun upsertItem(item: VaultItemPayload, allItemsAfterUpsert: List<VaultItemPayload>) =
        withContext(Dispatchers.IO) {
            try {
                val normalizedItem = item.copy(isSampleItem = false)
                latestKnownItems = allItemsAfterUpsert.map { if (it.id == normalizedItem.id) normalizedItem else it }
                ensureActiveDatabaseOpened()
                val savedHlc = nextHlcPacked(normalizedItem.updatedAtEpochMs)
                val savedVv = mapOf(nodeId to normalizedItem.updatedAtEpochMs)
                database?.let { db ->
                    upsertSingleItemInternal(db, normalizedItem, hlcOverride = savedHlc, vvOverride = savedVv)
                }
                if (storageMode == VaultStorageMode.LOCAL_ONLY) {
                    prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(latestKnownItems)).apply()
                } else if (hasActiveCloudAuth()) {
                    runCatching {
                        val cloudKey = resolveCloudSyncKey32()
                        val rec = encryptItemWithKey(normalizedItem, cloudKey, hlcOverride = savedHlc, vvOverride = savedVv)
                        if (_isNetworkOnline.value) {
                            runCatching {
                                activeSyncAdapter().pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, listOf(rec))
                                prefs.edit().putString(KEY_LAST_SYNC_ISO, Instant.now().toString()).apply()
                                refreshSyncIndicator()
                            }.onFailure {
                                enqueueCloudOutboxRecord(rec)
                            }
                            backgroundScope.launch {
                                runCatching {
                                    val rep = syncNow(latestKnownItems, surfaceErrorBanner = false)
                                    _autoReconciledItems.tryEmit(rep.updatedItems)
                                }
                            }
                        } else {
                            enqueueCloudOutboxRecord(rec)
                        }
                    }.onFailure { err ->
                        SafePocketErrorHandler.record("upsertItem.cloudSync", err, System.currentTimeMillis(), surfaceBanner = false)
                    }
                }
            } catch (t: Throwable) {
                SafePocketErrorHandler.record("upsertItem", t, System.currentTimeMillis())
            }
        }

    suspend fun replaceAllItems(items: List<VaultItemPayload>) = withContext(Dispatchers.IO) {
        latestKnownItems = items
        ensureActiveDatabaseOpened()
        database?.let { db ->
            val existingRows = db.safePocketVaultQueries.selectAllActiveItems().executeAsList()
            val incomingIds = items.map { it.id }.toSet()
            for (old in existingRows) {
                if (old.id !in incomingIds) {
                    markTombstoneInternal(db, old.id, old.category)
                }
            }
            persistSnapshotToDbInternal(db, items)
        }
        prefs.edit().putBoolean(KEY_DB_INITIALIZED_V02, true).apply()
        if (storageMode == VaultStorageMode.LOCAL_ONLY) {
            prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(items)).apply()
        } else if (hasActiveCloudAuth()) {
            runCatching { syncNow(items, surfaceErrorBanner = false) }
        }
    }

    suspend fun deleteItem(target: VaultItemPayload, remainingItems: List<VaultItemPayload>) =
        withContext(Dispatchers.IO) {
            try {
                latestKnownItems = remainingItems
                ensureActiveDatabaseOpened()
                var localTombstone: EncryptedSyncRecord? = null
                database?.let { db ->
                    localTombstone = markTombstoneInternal(db, target.id, target.category.name)
                }
                if (storageMode == VaultStorageMode.LOCAL_ONLY) {
                    prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(remainingItems)).apply()
                } else if (hasActiveCloudAuth() && localTombstone != null) {
                    runCatching {
                        val base = localTombstone!!
                        val cloudKey = resolveCloudSyncKey32()
                        val rec = encryptTombstoneWithKey(base.entityId, target.category.name, cloudKey, base.hlcPacked, base.versionVector)
                        if (_isNetworkOnline.value) {
                            runCatching {
                                activeSyncAdapter().pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, listOf(rec))
                                prefs.edit().putString(KEY_LAST_SYNC_ISO, Instant.now().toString()).apply()
                                refreshSyncIndicator()
                            }.onFailure {
                                enqueueCloudOutboxRecord(rec)
                            }
                            backgroundScope.launch {
                                runCatching {
                                    val rep = syncNow(latestKnownItems, surfaceErrorBanner = false)
                                    _autoReconciledItems.tryEmit(rep.updatedItems)
                                }
                            }
                        } else {
                            enqueueCloudOutboxRecord(rec)
                        }
                    }
                }
            } catch (t: Throwable) {
                SafePocketErrorHandler.record("deleteItem", t, System.currentTimeMillis())
            }
        }

    /**
     * Deletes all built-in example entries in one click and sets `KEY_SAMPLE_DATA_PURGED = true`
     * so sample entries are never re-seeded on app restart.
     */
    suspend fun deleteAllExampleData(
        currentItems: List<VaultItemPayload>
    ): Pair<Int, List<VaultItemPayload>> = withContext(Dispatchers.IO) {
        val (samples, userCreated) = currentItems.partition { it.isBuiltInSample() }
        prefs.edit()
            .putBoolean(KEY_SAMPLE_DATA_PURGED, true)
            .putBoolean(KEY_DB_INITIALIZED_V02, true)
            .apply()

        latestKnownItems = userCreated
        ensureActiveDatabaseOpened()
        val localTombstones = mutableListOf<Pair<String, EncryptedSyncRecord>>()
        database?.let { db ->
            for (sample in samples) {
                localTombstones += sample.category.name to markTombstoneInternal(db, sample.id, sample.category.name)
            }
        }

        if (storageMode == VaultStorageMode.LOCAL_ONLY) {
            prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(userCreated)).apply()
        } else if (hasActiveCloudAuth() && localTombstones.isNotEmpty()) {
            runCatching {
                val cloudKey = resolveCloudSyncKey32()
                val cloudTombstones = localTombstones.map { (catName, t) ->
                    encryptTombstoneWithKey(t.entityId, catName, cloudKey, t.hlcPacked, t.versionVector)
                }
                if (_isNetworkOnline.value) {
                    runCatching {
                        activeSyncAdapter().pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, cloudTombstones)
                        prefs.edit().putString(KEY_LAST_SYNC_ISO, Instant.now().toString()).apply()
                        refreshSyncIndicator()
                    }.onFailure {
                        cloudTombstones.forEach(::enqueueCloudOutboxRecord)
                    }
                } else {
                    cloudTombstones.forEach(::enqueueCloudOutboxRecord)
                }
            }
        }
        samples.size to userCreated
    }

    fun getLocalDbStatus(activeItems: List<VaultItemPayload>): LocalEncryptedDbStatus {
        val activeFile = activeTargetDbFileName()
        val dbFile = context.getDatabasePath(activeFile)
        val allRows = runCatching {
            database?.safePocketVaultQueries?.selectItemsIncludingTombstonesSinceHlc(null)?.executeAsList()
        }.getOrNull().orEmpty()
        val tombstones = allRows.count { it.is_tombstone != 0L }
        val modeLabel = if (storageMode == VaultStorageMode.CLOUD_SYNC) {
            "Cloud Session Offline Mirror (AES-256-GCM • Auto-Sync)"
        } else if (sqlCipherActive) {
            "SQLCipher 4.6.1 Standalone Local DB (AES-256 + 2SKD)"
        } else {
            "Standalone Encrypted Local Vault Store (AES-256-GCM)"
        }
        return LocalEncryptedDbStatus(
            storageMode = storageMode,
            isSqlCipherActive = sqlCipherActive,
            backendLabel = modeLabel,
            databaseFileName = dbFile.name,
            databaseSizeBytes = if (dbFile.exists()) dbFile.length() else 0L,
            activeItemCount = activeItems.size,
            tombstoneCount = tombstones,
            vaultId = vaultId,
            secretKeyFormatted = secretKeyFormatted,
            kdfSaltHex = ensureSaltInitializedSync(),
            hasCustomMasterPassword = hasCustomMasterPassword
        )
    }

    fun getCloudSyncStatus(): CloudSyncStatusSnapshot {
        val user = firebaseAuth.currentUser
        val gdriveActive = isGoogleDriveConfigured()
        return CloudSyncStatusSnapshot(
            storageMode = storageMode,
            cloudSyncEnabled = storageMode == VaultStorageMode.CLOUD_SYNC,
            isNetworkOnline = _isNetworkOnline.value,
            queuedOfflineMutations = loadPendingCloudOutbox().size,
            projectId = if (gdriveActive) "google-drive-appdata" else configuredFirebaseProjectId(),
            signedInEmail = if (gdriveActive) "Google Drive (appDataFolder)" else user?.email,
            signedInUid = if (gdriveActive) "gdrive" else user?.uid,
            lastSyncedPushed = prefs.getInt(KEY_LAST_SYNC_PUSHED, 0),
            lastSyncedPulled = prefs.getInt(KEY_LAST_SYNC_PULLED, 0),
            lastSyncedIso = prefs.getString(KEY_LAST_SYNC_ISO, null)
        )
    }

    // =========================================================================
    // BRAND AUTO-DETECTION & ONLINE DOMAIN LOGO + COLOR FETCHING
    // =========================================================================

    suspend fun enrichItemBranding(
        item: VaultItemPayload,
        forceOnlineFetch: Boolean = false
    ): VaultItemPayload = withContext(Dispatchers.IO) {
        val branded = InstitutionBrandRegistry.applyAutoBranding(item)
        if (!forceOnlineFetch && !autoDownloadLogos) return@withContext branded
        if (!forceOnlineFetch && !branded.cardTheme?.institutionLogoDataUri.isNullOrBlank()) {
            return@withContext branded
        }

        val queryTarget = branded.cardTheme?.institutionDomain?.takeIf { it.isNotBlank() }
            ?: branded.cardTheme?.institutionName?.takeIf { it.isNotBlank() }
            ?: return@withContext branded

        val fetched: DownloadedBrandAsset? = runCatching {
            InstitutionLogoFetcher.fetchDomainLogo(
                httpClient = httpClient,
                rawDomainOrName = queryTarget,
                extractColorFromBytes = ::extractVibrantColorHex
            )
        }.getOrNull()

        if (fetched == null) return@withContext branded

        val prevTheme = branded.cardTheme ?: com.safepocket.shared.model.CardVisualTheme()
        val curatedColor = InstitutionBrandRegistry.resolveForItem(branded).institution?.primaryColorHex
        val nextHex = when {
            !prevTheme.autoBrandColor -> prevTheme.customColorHex
            curatedColor != null -> curatedColor
            fetched.extractedColorHex != null -> fetched.extractedColorHex
            else -> prevTheme.customColorHex
        }

        branded.copy(
            cardTheme = prevTheme.copy(
                paletteId = if (nextHex != null) "custom" else prevTheme.paletteId,
                customColorHex = nextHex,
                institutionDomain = fetched.domain,
                institutionLogoDataUri = fetched.dataUri
            )
        )
    }

    private fun extractVibrantColorHex(imageBytes: ByteArray): String? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightSum = 0L
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        val hsv = FloatArray(3)

        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                val alpha = AndroidColor.alpha(px)
                if (alpha >= 180) {
                    val r = AndroidColor.red(px)
                    val g = AndroidColor.green(px)
                    val b = AndroidColor.blue(px)
                    AndroidColor.RGBToHSV(r, g, b, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (sat > 0.22f && value in 0.14f..0.94f) {
                        val w = (sat * 100f).toLong().coerceAtLeast(1L)
                        rSum += r * w
                        gSum += g * w
                        bSum += b * w
                        weightSum += w
                    }
                }
                x += stepX
            }
            y += stepY
        }
        if (weightSum == 0L) return null
        val rAvg = (rSum / weightSum).toInt().coerceIn(0, 255)
        val gAvg = (gSum / weightSum).toInt().coerceIn(0, 255)
        val bAvg = (bSum / weightSum).toInt().coerceIn(0, 255)
        String.format("#%02X%02X%02X", rAvg, gAvg, bAvg)
    }.getOrNull()

    // =========================================================================
    // FIREBASE ZERO-KNOWLEDGE ACCOUNT & EXCLUSIVE CLOUD SYNC
    // =========================================================================

    suspend fun createFirebaseAccount(
        email: String,
        syncPassword: String,
        currentItems: List<VaultItemPayload>
    ): Pair<CloudSyncUserSession, List<VaultItemPayload>> = withContext(Dispatchers.IO) {
        try {
            val session = firebaseAuth.createCloudAccount(email, syncPassword)
            saveFirebaseSession(session)
            val reconciled = switchToCloudSyncExclusiveMode(currentItems)
            session to reconciled
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("createFirebaseAccount", t, System.currentTimeMillis())
            throw t
        }
    }

    suspend fun signInFirebaseAccount(
        email: String,
        syncPassword: String,
        currentItems: List<VaultItemPayload>
    ): Pair<CloudSyncUserSession, List<VaultItemPayload>> = withContext(Dispatchers.IO) {
        try {
            val session = firebaseAuth.signInCloudAccount(email, syncPassword)
            saveFirebaseSession(session)
            val reconciled = switchToCloudSyncExclusiveMode(currentItems)
            session to reconciled
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("signInFirebaseAccount", t, System.currentTimeMillis())
            throw t
        }
    }

    suspend fun signOutFirebaseAndReturnToLocal(
        currentItems: List<VaultItemPayload>
    ): List<VaultItemPayload> = withContext(Dispatchers.IO) {
        firebaseAuth.signOut()
        savePendingCloudOutbox(emptyList())
        prefs.edit().remove(KEY_FIREBASE_SESSION).apply()
        switchToLocalOnlyExclusiveMode(currentItems)
    }

    private fun saveFirebaseSession(session: CloudSyncUserSession) {
        prefs.edit().putString(KEY_FIREBASE_SESSION, json.encodeToString(session)).apply()
    }

    suspend fun syncNow(
        currentItems: List<VaultItemPayload>,
        surfaceErrorBanner: Boolean = true
    ): BidirectionalSyncReport = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            refreshSyncIndicator(explicitSyncing = true)
            try {
                val cursorScope: String = if (isGoogleDriveConfigured()) {
                    "gdrive"
                } else {
                    val session = firebaseAuth.refreshIdTokenIfPossible(force = false)
                        ?: error("Please sign in to Firebase Cloud Sync first")
                    saveFirebaseSession(session)
                    session.uid
                }
                val cloudKey = resolveCloudSyncKey32()
                val adapter = activeSyncAdapter()

                ensureActiveDatabaseOpened()
                val db = database

                // 1. Build local candidate map (excluding untouched built-in sample items so sample noise never pollutes Cloud)
                val localSyncMap = mutableMapOf<String, EncryptedSyncRecord>()
                if (db != null) {
                    val rows = db.safePocketVaultQueries.selectItemsIncludingTombstonesSinceHlc(null).executeAsList()
                    for (row in rows) {
                        val normHlc = normalizeHlc(row.hlc_timestamp)
                        val vv = parseRowVersionVector(row)
                        if (row.is_tombstone != 0L) {
                            localSyncMap[row.id] = encryptTombstoneWithKey(row.id, row.category, cloudKey, normHlc, vv)
                        } else {
                            val item = decryptDbRowOrNull(row)
                            if (item != null && !item.isBuiltInSample()) {
                                localSyncMap[item.id] = encryptItemWithKey(item, cloudKey, hlcOverride = normHlc, vvOverride = vv)
                            }
                        }
                    }
                } else {
                    for (item in currentItems) {
                        if (!item.isBuiltInSample()) {
                            localSyncMap[item.id] = encryptItemWithKey(item, cloudKey)
                        }
                    }
                }

                // Merge any pending offline outbox mutations into localSyncMap via CRDT LWW
                for (queuedRaw in loadPendingCloudOutbox()) {
                    val queued = queuedRaw.copy(hlcPacked = normalizeHlc(queuedRaw.hlcPacked))
                    val prev = localSyncMap[queued.entityId]
                    localSyncMap[queued.entityId] = if (prev == null) queued else CrdtReconciler.resolve(prev, queued)
                }

                // 2. PULL FIRST (full delta map, sinceHlc = null -> 100% immune to clock skew & out-of-order offline flushes)
                val pulledRecords = adapter.pullEncryptedDeltasSince(CLOUD_VAULT_COLLECTION_ID, null)
                    .map { it.copy(hlcPacked = normalizeHlc(it.hlcPacked)) }
                val remoteById = pulledRecords.associateBy { it.entityId }

                // 3. CRDT Reconcile: determine winning local deltas to push AND winning remote deltas to apply locally
                val deltasToPush = mutableListOf<EncryptedSyncRecord>()
                var appliedRemote = 0
                val allEntityIds = (localSyncMap.keys + remoteById.keys).toSet()

                for (entityId in allEntityIds) {
                    val localRec = localSyncMap[entityId]
                    val remoteRec = remoteById[entityId]
                    when {
                        remoteRec == null && localRec != null -> {
                            deltasToPush += localRec
                        }
                        localRec == null && remoteRec != null -> {
                            if (applyRemoteWinnerToLocalDb(db, remoteRec, cloudKey, deltasToPush)) {
                                localSyncMap[entityId] = remoteRec
                                appliedRemote++
                            }
                        }
                        localRec != null && remoteRec != null -> {
                            val winner = CrdtReconciler.resolve(localRec, remoteRec)
                            val localWon = (winner.hlcPacked == localRec.hlcPacked && winner.hlcPacked != remoteRec.hlcPacked) ||
                                (winner.isTombstone == localRec.isTombstone && winner.isTombstone != remoteRec.isTombstone && winner.hlcPacked >= remoteRec.hlcPacked)
                            val remoteWon = (winner.hlcPacked == remoteRec.hlcPacked && winner.hlcPacked != localRec.hlcPacked) ||
                                (winner.isTombstone == remoteRec.isTombstone && winner.isTombstone != localRec.isTombstone && winner.hlcPacked > localRec.hlcPacked)

                            if (localWon) {
                                deltasToPush += localRec.copy(versionVector = winner.versionVector)
                            } else if (remoteWon) {
                                val mergedRemote = remoteRec.copy(versionVector = winner.versionVector)
                                if (applyRemoteWinnerToLocalDb(db, mergedRemote, cloudKey, deltasToPush)) {
                                    localSyncMap[entityId] = mergedRemote
                                    appliedRemote++
                                }
                            }
                        }
                    }
                }

                // 4. PUSH ONLY WINNING LOCAL DELTAS (zero unnecessary Firestore writes when idle!)
                val pushRes = if (deltasToPush.isNotEmpty()) {
                    adapter.pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, deltasToPush)
                } else {
                    com.safepocket.shared.sync.SyncPushResult(0, pulledRecords.maxOfOrNull { it.hlcPacked })
                }
                savePendingCloudOutbox(emptyList())

                // If remote vault already contains real user records, hide untouched built-in sample noise automatically
                val hasRealRemoteItems = pulledRecords.any { !it.isTombstone && it.entityId !in com.safepocket.shared.model.SampleVaultCatalogIds.ALL_KNOWN_SAMPLE_IDS }
                if (hasRealRemoteItems && !sampleDataPurged) {
                    prefs.edit().putBoolean(KEY_SAMPLE_DATA_PURGED, true).apply()
                }

                val cursorKey = "${KEY_LAST_SYNC_CURSOR}_$cursorScope"
                val maxPulledHlc = pulledRecords.maxOfOrNull { it.hlcPacked } ?: pushRes.latestServerCursorHlc
                val nowIso = Instant.now().toString()
                prefs.edit()
                    .apply {
                        if (!maxPulledHlc.isNullOrBlank()) putString(cursorKey, maxPulledHlc)
                        putInt(KEY_LAST_SYNC_PUSHED, pushRes.acceptedCount)
                        putInt(KEY_LAST_SYNC_PULLED, appliedRemote)
                        putString(KEY_LAST_SYNC_ISO, nowIso)
                    }
                    .apply()

                val updatedItems = (if (db != null) {
                    db.safePocketVaultQueries.selectAllActiveItems().executeAsList().mapNotNull { decryptDbRowOrNull(it) }
                } else {
                    localSyncMap.values.filter { !it.isTombstone }.mapNotNull { decryptSyncRecordWithKeyOrNull(it, cloudKey) }
                }).let { list ->
                    if (sampleDataPurged) list.filterNot { it.isBuiltInSample() } else list
                }

                latestKnownItems = updatedItems
                refreshSyncIndicator(explicitSyncing = false, errorMessage = null)
                BidirectionalSyncReport(
                    pushedCount = pushRes.acceptedCount,
                    pulledCount = appliedRemote,
                    updatedItems = updatedItems
                )
            } catch (t: Throwable) {
                if (t is CloudAuthExpiredException) {
                    prefs.edit().remove(KEY_FIREBASE_SESSION).apply()
                }
                val diag = SafePocketErrorHandler.record(
                    operation = "cloudSync",
                    throwable = t,
                    epochMs = System.currentTimeMillis(),
                    surfaceBanner = surfaceErrorBanner
                )
                refreshSyncIndicator(explicitSyncing = false, errorMessage = diag.userMessage)
                throw t
            }
        }
    }

    // =========================================================================
    // ENCRYPTED `.safepocket` BACKUP + 1PASSWORD / CSV PORTABILITY
    // =========================================================================

    suspend fun exportEncryptedContainer(
        items: List<VaultItemPayload>,
        backupPassword: String
    ): String = withContext(Dispatchers.Default) {
        try {
            portabilityService.exportCrossPlatformContainer(
                items = items,
                masterPassword = backupPassword,
                secretKeyFormatted = secretKeyFormatted,
                exportedAtIso = Instant.now().toString()
            )
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("exportEncryptedContainer", t, System.currentTimeMillis())
            throw t
        }
    }

    suspend fun inspectEncryptedContainer(rawJson: String): ContainerInspectionSummary =
        withContext(Dispatchers.Default) {
            portabilityService.inspectContainer(rawJson)
        }

    suspend fun restoreEncryptedContainer(
        rawJson: String,
        backupPassword: String,
        backupSecretKey: String,
        existingItems: List<VaultItemPayload>,
        replaceExisting: Boolean
    ): List<VaultItemPayload> = withContext(Dispatchers.Default) {
        try {
            val decrypted = portabilityService.restoreCrossPlatformContainer(
                containerJson = rawJson,
                masterPassword = backupPassword,
                secretKeyFormatted = backupSecretKey.ifBlank { secretKeyFormatted }
            ).map { InstitutionBrandRegistry.applyAutoBranding(it) }

            val finalItems = if (replaceExisting) {
                decrypted
            } else {
                portabilityService.mergeSnapshots(existingItems, decrypted)
            }
            replaceAllItems(finalItems)
            finalItems
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("restoreEncryptedContainer", t, System.currentTimeMillis())
            throw t
        }
    }

    suspend fun importFromFileBytes(
        fileName: String,
        rawBytes: ByteArray,
        includeLoginsAsNotes: Boolean,
        existingItems: List<VaultItemPayload>
    ): Pair<VaultImportReport, List<VaultItemPayload>> = withContext(Dispatchers.Default) {
        try {
            val lower = fileName.lowercase()
            val is1Pux = lower.endsWith(".1pux") ||
                (rawBytes.size >= 4 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) ||
                rawBytes.take(64).toByteArray().decodeToString().trimStart().startsWith("{\"accounts\"")

            val report = if (is1Pux) {
                CsvAndOnePasswordPortability.importFrom1Password1Pux(
                    rawBytes = rawBytes,
                    includeLoginsAsNotes = includeLoginsAsNotes,
                    nowEpochMs = System.currentTimeMillis(),
                    zipExtractor = AndroidZipCodec::extractEntries
                )
            } else {
                CsvAndOnePasswordPortability.importFromCsv(
                    rawCsv = rawBytes.decodeToString(),
                    includeLoginsAsNotes = includeLoginsAsNotes,
                    nowEpochMs = System.currentTimeMillis()
                )
            }

            val merged = portabilityService.mergeSnapshots(existingItems, report.importedItems)
            replaceAllItems(merged)
            report to merged
        } catch (t: Throwable) {
            SafePocketErrorHandler.record("importFromFileBytes", t, System.currentTimeMillis())
            throw t
        }
    }

    suspend fun export1Password1PuxBytes(items: List<VaultItemPayload>): ByteArray =
        withContext(Dispatchers.Default) {
            CsvAndOnePasswordPortability.exportTo1Password1Pux(
                items = items,
                exportedAtEpochSeconds = System.currentTimeMillis() / 1000L,
                zipCreator = AndroidZipCodec::createZip
            )
        }

    suspend fun export1PasswordCsvText(items: List<VaultItemPayload>): String =
        withContext(Dispatchers.Default) {
            CsvAndOnePasswordPortability.exportTo1PasswordCsv(items)
        }

    suspend fun exportSafePocketCsvText(items: List<VaultItemPayload>): String =
        withContext(Dispatchers.Default) {
            CsvAndOnePasswordPortability.exportToSafePocketCsv(items)
        }

    fun buildEmergencyKitHtml(passwordHint: String? = null): String =
        EmergencyKitGenerator.buildPrintableEmergencyKitHtml(
            vaultId = vaultId,
            createdAtIso = Instant.now().toString(),
            secretKeyFormatted = secretKeyFormatted,
            kdfParams = Argon2idParameters(
                memoryKiB = 16_384,
                iterations = 3,
                parallelism = 2,
                saltHex = ensureSaltInitializedSync()
            ),
            passwordHint = passwordHint
        )

    // =========================================================================
    // INTERNAL SQLCIPHER + AES-GCM ROW SERIALIZATION
    // =========================================================================

    private fun normalizeHlc(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 21 && trimmed[15] == '-' && trimmed[20] == '-') {
            return "${trimmed.substring(0, 15)}:${trimmed.substring(16, 20)}:${trimmed.substring(21)}"
        }
        return trimmed
    }

    private fun resolveCloudSyncKey32(): ByteArray {
        if (isGoogleDriveConfigured()) {
            return activeVaultItemKey32
        }
        val hex = firebaseAuth.currentUser?.cloudKeyHex?.trim()
        if (hex.isNullOrBlank() || hex.length != 64) {
            throw CloudAuthExpiredException(
                "Cloud Sync upgraded to Cross-Platform Zero-Knowledge v2 — please sign in once with your Cloud Sync password."
            )
        }
        return ZeroKnowledgeEncryptionService.hexToBytes(hex)
    }

    private fun nextHlcPacked(nowMs: Long = System.currentTimeMillis()): String {
        hlcLogicalCounter = (hlcLogicalCounter + 1) % 10_000
        return HybridLogicalClock(nowMs, hlcLogicalCounter, nodeId).pack()
    }

    private fun parseRowVersionVector(row: Vault_items): Map<String, Long> =
        runCatching {
            json.parseToJsonElement(row.version_vector_json).jsonObject.mapNotNull { (k, v) ->
                v.jsonPrimitive.longOrNull?.let { k to it }
            }.toMap()
        }.getOrDefault(mapOf(nodeId to row.updated_at))

    private suspend fun persistSnapshotToDbInternal(
        db: SafePocketDatabase,
        items: List<VaultItemPayload>
    ) {
        for (item in items) {
            upsertSingleItemInternal(db, item)
        }
    }

    private suspend fun upsertSingleItemInternal(
        db: SafePocketDatabase,
        item: VaultItemPayload,
        hlcOverride: String? = null,
        vvOverride: Map<String, Long>? = null
    ) {
        val aad = "safepocket:v${item.schemaVersion}:${item.category.name}:${item.id}".encodeToByteArray()
        val plaintext = json.encodeToString(item).encodeToByteArray()
        val (iv, ciphertext) = primitives.aes256GcmEncrypt(activeVaultItemKey32, plaintext, aad)
        val hlc = hlcOverride?.let(::normalizeHlc) ?: nextHlcPacked(item.updatedAtEpochMs)
        val versionVec = if (vvOverride != null) {
            vvOverride.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
        } else {
            "{\"$nodeId\":${item.updatedAtEpochMs}}"
        }

        db.safePocketVaultQueries.upsertVaultItem(
            id = item.id,
            category = item.category.name,
            is_favorite = if (item.favorite) 1L else 0L,
            expiry_epoch_day = null,
            encrypted_payload = ciphertext,
            iv = iv,
            schema_version = item.schemaVersion.toLong(),
            hlc_timestamp = hlc,
            version_vector_json = versionVec,
            is_tombstone = 0L,
            updated_at = item.updatedAtEpochMs
        )
    }

    private suspend fun markTombstoneInternal(
        db: SafePocketDatabase,
        itemId: String,
        categoryName: String
    ): EncryptedSyncRecord {
        val now = System.currentTimeMillis()
        val aadStr = "safepocket:v1:$categoryName:$itemId"
        val (iv, tombstoneCipher) = primitives.aes256GcmEncrypt(
            activeVaultItemKey32,
            "{\"deleted\":true}".encodeToByteArray(),
            aadStr.encodeToByteArray()
        )
        val hlc = nextHlcPacked(now)
        val versionVec = "{\"$nodeId\":$now}"
        val existingRow = db.safePocketVaultQueries.selectAllActiveItems().executeAsList().firstOrNull { it.id == itemId }
        if (existingRow != null) {
            db.safePocketVaultQueries.markVaultItemTombstone(
                emptyCiphertext = tombstoneCipher,
                tombstoneIv = iv,
                hlcTimestamp = hlc,
                versionVectorJson = versionVec,
                updatedAt = now,
                id = itemId
            )
        } else {
            db.safePocketVaultQueries.upsertVaultItem(
                id = itemId,
                category = categoryName,
                is_favorite = 0L,
                expiry_epoch_day = null,
                encrypted_payload = tombstoneCipher,
                iv = iv,
                schema_version = 1L,
                hlc_timestamp = hlc,
                version_vector_json = versionVec,
                is_tombstone = 1L,
                updated_at = now
            )
        }
        return EncryptedSyncRecord(
            entityId = itemId,
            entityType = "ITEM",
            hlcPacked = hlc,
            versionVector = mapOf(nodeId to now),
            isTombstone = true,
            ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
            ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(tombstoneCipher),
            aadContext = aadStr
        )
    }

    private suspend fun decryptDbRowOrNull(row: Vault_items): VaultItemPayload? {
        if (row.is_tombstone != 0L) return null
        val aad = "safepocket:v${row.schema_version}:${row.category}:${row.id}".encodeToByteArray()
        return runCatching {
            val plain = primitives.aes256GcmDecrypt(activeVaultItemKey32, row.iv, row.encrypted_payload, aad)
            val parsed = json.decodeFromString<VaultItemPayload>(plain.decodeToString())
            InstitutionBrandRegistry.applyAutoBranding(parsed)
        }.getOrNull()
    }

    private suspend fun encryptItemWithKey(
        item: VaultItemPayload,
        key32: ByteArray,
        hlcOverride: String? = null,
        vvOverride: Map<String, Long>? = null
    ): EncryptedSyncRecord {
        val aadStr = "safepocket:v${item.schemaVersion}:${item.category.name}:${item.id}"
        val (iv, cipher) = primitives.aes256GcmEncrypt(
            key32,
            json.encodeToString(item).encodeToByteArray(),
            aadStr.encodeToByteArray()
        )
        return EncryptedSyncRecord(
            entityId = item.id,
            entityType = "ITEM",
            hlcPacked = hlcOverride?.let(::normalizeHlc) ?: nextHlcPacked(item.updatedAtEpochMs),
            versionVector = vvOverride ?: mapOf(nodeId to item.updatedAtEpochMs),
            isTombstone = false,
            ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
            ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(cipher),
            aadContext = aadStr
        )
    }

    private suspend fun encryptTombstoneWithKey(
        entityId: String,
        categoryName: String,
        key32: ByteArray,
        hlcPacked: String,
        versionVector: Map<String, Long>
    ): EncryptedSyncRecord {
        val aadStr = "safepocket:v1:$categoryName:$entityId"
        val (iv, cipher) = primitives.aes256GcmEncrypt(
            key32,
            "{\"deleted\":true}".encodeToByteArray(),
            aadStr.encodeToByteArray()
        )
        return EncryptedSyncRecord(
            entityId = entityId,
            entityType = "ITEM",
            hlcPacked = normalizeHlc(hlcPacked),
            versionVector = versionVector,
            isTombstone = true,
            ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
            ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(cipher),
            aadContext = aadStr
        )
    }

    private suspend fun encryptItemToSyncRecord(item: VaultItemPayload): EncryptedSyncRecord =
        encryptItemWithKey(item, resolveCloudSyncKey32())

    private suspend fun decryptSyncRecordWithKeyOrNull(
        rec: EncryptedSyncRecord,
        key32: ByteArray
    ): VaultItemPayload? {
        if (rec.isTombstone) return null
        return runCatching {
            val iv = ZeroKnowledgeEncryptionService.hexToBytes(rec.ivHex)
            val cipher = ZeroKnowledgeEncryptionService.hexToBytes(rec.ciphertextHex)
            val plain = primitives.aes256GcmDecrypt(
                key32,
                iv,
                cipher,
                rec.aadContext.encodeToByteArray()
            )
            InstitutionBrandRegistry.applyAutoBranding(
                json.decodeFromString<VaultItemPayload>(plain.decodeToString())
            )
        }.getOrNull()
    }

    private suspend fun applyRemoteWinnerToLocalDb(
        db: SafePocketDatabase?,
        rec: EncryptedSyncRecord,
        cloudKey32: ByteArray,
        deltasToPush: MutableList<EncryptedSyncRecord>
    ): Boolean {
        val parts = rec.aadContext.split(':')
        val categoryName = parts.getOrNull(2) ?: "SECURE_NOTE"
        val normHlc = normalizeHlc(rec.hlcPacked)
        val vvJson = rec.versionVector.entries.joinToString(prefix = "{", postfix = "}") {
            "\"${it.key}\":${it.value}"
        }
        val wallMs = runCatching { HybridLogicalClock.unpack(normHlc).wallTimeEpochMs }
            .getOrDefault(System.currentTimeMillis())

        if (rec.isTombstone) {
            if (db != null) {
                val aadStr = "safepocket:v1:$categoryName:${rec.entityId}"
                val (localIv, localCipher) = primitives.aes256GcmEncrypt(
                    activeVaultItemKey32,
                    "{\"deleted\":true}".encodeToByteArray(),
                    aadStr.encodeToByteArray()
                )
                db.safePocketVaultQueries.upsertVaultItem(
                    id = rec.entityId,
                    category = categoryName,
                    is_favorite = 0L,
                    expiry_epoch_day = null,
                    encrypted_payload = localCipher,
                    iv = localIv,
                    schema_version = 1L,
                    hlc_timestamp = normHlc,
                    version_vector_json = vvJson,
                    is_tombstone = 1L,
                    updated_at = wallMs
                )
            }
            return true
        }

        // 1. Decrypt with unified v2 cross-platform cloudKey32
        var decrypted = decryptSyncRecordWithKeyOrNull(rec, cloudKey32)
        if (decrypted == null) {
            // 2. Self-migration fallback: if this same device pushed a v0.3.x record under activeVaultItemKey32,
            // decrypt it and automatically upgrade its cloud envelope to cloudKey32!
            val legacyLocal = decryptSyncRecordWithKeyOrNull(rec, activeVaultItemKey32)
            if (legacyLocal != null) {
                decrypted = legacyLocal
                deltasToPush += encryptItemWithKey(
                    legacyLocal,
                    cloudKey32,
                    hlcOverride = normHlc,
                    vvOverride = rec.versionVector
                )
            }
        }
        if (decrypted == null) {
            SafePocketErrorHandler.record(
                operation = "cloudSync.decryptSkip",
                throwable = IllegalStateException("Skipped legacy undecryptable sync envelope ${rec.entityId}"),
                epochMs = System.currentTimeMillis(),
                surfaceBanner = false
            )
            return false
        }

        if (db != null) {
            upsertSingleItemInternal(
                db = db,
                item = decrypted,
                hlcOverride = normHlc,
                vvOverride = rec.versionVector
            )
        }
        return true
    }

    companion object {
        const val LOCAL_STANDALONE_DB_NAME = "safepocket_vault.db"
        const val CLOUD_OFFLINE_CACHE_DB_NAME = "safepocket_cloud_offline_cache.db"
        private const val DEFAULT_DEMO_PASSWORD = "Correct-Horse-SafePocket-2026!"
        private const val CLOUD_VAULT_COLLECTION_ID = "default-vault"
        private const val LEGACY_KEY_VAULT_ITEMS = "vault_items_json"
        private const val KEY_DB_INITIALIZED_V02 = "db_initialized_v02"
        private const val KEY_SAMPLE_DATA_PURGED = "sample_data_purged_v03"
        private const val KEY_STORAGE_MODE = "vault_storage_mode_v03"
        private const val KEY_CLOUD_OUTBOX_JSON = "cloud_offline_outbox_json_v03"
        private const val KEY_SQLCIPHER_RAW_HEX = "sqlcipher_raw_key_hex"
        private const val KEY_WRAPPED_LOCAL_VEK_HEX = "wrapped_local_vek_hex"
        private const val KEY_SECRET_KEY_FORMATTED = "secret_key_formatted"
        private const val KEY_KDF_SALT_HEX = "kdf_salt_hex"
        private const val KEY_MASTER_PW_VERIFIER = "master_pw_verifier_hex"
        private const val KEY_HAS_CUSTOM_MASTER_PW = "has_custom_master_pw"
        private const val KEY_AUTO_DOWNLOAD_LOGOS = "auto_download_logos"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates_v03"
        private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
        private const val KEY_FIREBASE_SESSION = "firebase_user_session_json"
        private const val KEY_LAST_SYNC_CURSOR = "firebase_last_sync_cursor"
        private const val KEY_LAST_SYNC_PUSHED = "firebase_last_sync_pushed"
        private const val KEY_LAST_SYNC_PULLED = "firebase_last_sync_pulled"
        private const val KEY_LAST_SYNC_ISO = "firebase_last_sync_iso"
        private const val KEY_NODE_ID = "crdt_node_id"
    }
}
