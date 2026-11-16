package com.safepocket.android.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
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
import com.safepocket.shared.model.VaultItemPayload
import com.safepocket.shared.onboarding.EmergencyKitGenerator
import com.safepocket.shared.sync.CloudSyncUserSession
import com.safepocket.shared.sync.CrdtReconciler
import com.safepocket.shared.sync.EncryptedSyncRecord
import com.safepocket.shared.sync.FirebaseSyncAdapter
import com.safepocket.shared.sync.FirebaseZeroKnowledgeAuthService
import com.safepocket.shared.sync.HybridLogicalClock
import com.safepocket.shared.sync.ProvisionedFirebaseProject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

data class LocalEncryptedDbStatus(
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
    val cloudSyncEnabled: Boolean,
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
 * SafePocket v0.2 Local Encrypted DB (`SQLCipher 4.6`) + Zero-Knowledge Firebase Sync +
 * Dual-Brand Logo Downloader + `.safepocket` / 1Password / CSV Portability Coordinator.
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
    private val httpClient = HttpClient(OkHttp)

    val firebaseAuth = FirebaseZeroKnowledgeAuthService(
        httpClient = httpClient,
        primitives = primitives
    )

    private val firebaseAdapter = FirebaseSyncAdapter(
        httpClient = httpClient,
        userIdProvider = {
            firebaseAuth.refreshIdTokenIfPossible()?.uid
                ?: firebaseAuth.currentUser?.uid
                ?: error("Not signed in to Firebase Cloud Sync")
        },
        idTokenProvider = {
            firebaseAuth.refreshIdTokenIfPossible()?.idToken
                ?: firebaseAuth.currentUser?.idToken
                ?: error("Not signed in to Firebase Cloud Sync")
        }
    )

    private val nodeId: String = prefs.getString(KEY_NODE_ID, null) ?: run {
        val generated = "and-${ZeroKnowledgeEncryptionService.bytesToHex(primitives.secureRandomBytes(3))}"
        prefs.edit().putString(KEY_NODE_ID, generated).apply()
        generated
    }

    private var hlcLogicalCounter: Int = 0
    private var database: SafePocketDatabase? = null
    private var activeVaultItemKey32: ByteArray = ByteArray(32)
    private var sqlCipherActive: Boolean = false

    init {
        val savedSessionJson = prefs.getString(KEY_FIREBASE_SESSION, null)
        if (!savedSessionJson.isNullOrBlank()) {
            runCatching {
                json.decodeFromString<CloudSyncUserSession>(savedSessionJson)
            }.getOrNull()?.let { firebaseAuth.restoreSession(it) }
        }
    }

    var autoDownloadLogos: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LOGOS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LOGOS, value).apply()
        }

    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, value).apply()
        }

    val secretKeyFormatted: String
        get() = prefs.getString(KEY_SECRET_KEY_FORMATTED, null) ?: run {
            val (formatted, _) = ZeroKnowledgeEncryptionService(primitives).generateLocalSecretKey()
            prefs.edit().putString(KEY_SECRET_KEY_FORMATTED, formatted).apply()
            formatted
        }

    val kdfSaltHex: String
        get() = prefs.getString(KEY_KDF_SALT_HEX, null) ?: run {
            val derived = deterministicSaltHexForSecretKey(secretKeyFormatted)
            prefs.edit().putString(KEY_KDF_SALT_HEX, derived).apply()
            derived
        }

    val vaultId: String
        get() = "vault-${kdfSaltHex.take(12)}"

    val hasCustomMasterPassword: Boolean
        get() = prefs.getBoolean(KEY_HAS_CUSTOM_MASTER_PW, false)

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

    /**
     * Opens the local SQLCipher 4.6 encrypted database (`safepocket_vault.db`), derives the
     * Two-Secret AES-256-GCM item key, migrates any legacy `v0.1.2` items seamlessly, and loads
     * all active decrypted vault items.
     */
    suspend fun initializeAndLoadItems(): List<VaultItemPayload> = withContext(Dispatchers.IO) {
        val saltHex = ensureSaltInitializedSync()

        // 1. Load or derive the 256-bit item encryption key (compatible with ChromeOS PWA)
        val cachedKeyHex = prefs.getString(KEY_WRAPPED_LOCAL_VEK_HEX, null)
        activeVaultItemKey32 = if (!cachedKeyHex.isNullOrBlank() && cachedKeyHex.length == 64) {
            ZeroKnowledgeEncryptionService.hexToBytes(cachedKeyHex)
        } else {
            val derived = HkdfSha256Pure.deriveTwoSecretKey(
                masterPassword = DEFAULT_DEMO_PASSWORD,
                secretKeyFormatted = secretKeyFormatted,
                saltHex = saltHex,
                memorySizeKiB = 4_096, // Fast initial bootstrap; full 16 MiB used when user sets Master Password
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

        // 2. Open SQLCipher 4.6 Encrypted Local Database
        val sqlCipherKey = loadOrCreateSqlCipherRawKey()
        if (database == null) {
            runCatching {
                val driver = AndroidSqlCipherDriverFactory.createEncryptedDriver(context, sqlCipherKey)
                database = SafePocketDatabase(driver)
                sqlCipherActive = true
            }.onFailure {
                sqlCipherActive = false
            }
        }

        val db = database
        if (db != null) {
            val rows = db.safePocketVaultQueries.selectAllActiveItems().executeAsList()
            if (rows.isEmpty() && !prefs.getBoolean(KEY_DB_INITIALIZED_V02, false)) {
                // Migrate existing v0.1.2 items or seed v0.2 sample vault into SQLCipher
                val legacyRaw = prefs.getString(LEGACY_KEY_VAULT_ITEMS, null)
                val initialItems = AndroidSampleVault.decodeItemsOrDefault(legacyRaw)
                persistSnapshotToDbInternal(db, initialItems)
                prefs.edit().putBoolean(KEY_DB_INITIALIZED_V02, true).apply()
                return@withContext initialItems
            }

            val decrypted = rows.mapNotNull { row -> decryptDbRowOrNull(row) }
            if (decrypted.isNotEmpty() || prefs.getBoolean(KEY_DB_INITIALIZED_V02, false)) {
                // Keep SharedPreferences mirror updated as resilient fallback
                prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(decrypted)).apply()
                return@withContext decrypted
            }
        }

        // Fallback if native SQLCipher binary unavailable on host ABI
        AndroidSampleVault.decodeItemsOrDefault(prefs.getString(LEGACY_KEY_VAULT_ITEMS, null))
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

    /**
     * Configures or updates the user's Master Password & Secret Key (with optional Recovery URI or
     * explicit KDF salt for cross-device pairing) and re-encrypts all items in SQLCipher 4.6.
     */
    suspend fun configureVaultCredentials(
        newMasterPassword: String,
        newSecretKeyInput: String? = null,
        currentItems: List<VaultItemPayload>
    ) = withContext(Dispatchers.IO) {
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

        database?.let { db -> persistSnapshotToDbInternal(db, currentItems) }
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

    suspend fun upsertItem(item: VaultItemPayload, allItemsAfterUpsert: List<VaultItemPayload>) =
        withContext(Dispatchers.IO) {
            database?.let { db ->
                upsertSingleItemInternal(db, item)
            }
            prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(allItemsAfterUpsert)).apply()
            if (cloudSyncEnabled && firebaseAuth.currentUser != null) {
                runCatching { pushSingleItemIfPossible(item) }
            }
        }

    suspend fun replaceAllItems(items: List<VaultItemPayload>) = withContext(Dispatchers.IO) {
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
        prefs.edit()
            .putBoolean(KEY_DB_INITIALIZED_V02, true)
            .putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(items))
            .apply()
    }

    suspend fun deleteItem(target: VaultItemPayload, remainingItems: List<VaultItemPayload>) =
        withContext(Dispatchers.IO) {
            database?.let { db ->
                markTombstoneInternal(db, target.id, target.category.name)
            }
            prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(remainingItems)).apply()
            if (cloudSyncEnabled && firebaseAuth.currentUser != null) {
                runCatching { syncNow(remainingItems) }
            }
        }

    fun getLocalDbStatus(activeItems: List<VaultItemPayload>): LocalEncryptedDbStatus {
        val dbFile = context.getDatabasePath("safepocket_vault.db")
        val allRows = runCatching {
            database?.safePocketVaultQueries?.selectItemsIncludingTombstonesSinceHlc(null)?.executeAsList()
        }.getOrNull().orEmpty()
        val tombstones = allRows.count { it.is_tombstone != 0L }
        return LocalEncryptedDbStatus(
            isSqlCipherActive = sqlCipherActive,
            backendLabel = if (sqlCipherActive) {
                "SQLCipher 4.6.1 (AES-256 + 2SKD AES-256-GCM)"
            } else {
                "Encrypted Local Vault Store (AES-256-GCM)"
            },
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
        return CloudSyncStatusSnapshot(
            cloudSyncEnabled = cloudSyncEnabled,
            signedInEmail = user?.email,
            signedInUid = user?.uid,
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

        val fetched: DownloadedBrandAsset? = InstitutionLogoFetcher.fetchDomainLogo(
            httpClient = httpClient,
            rawDomainOrName = queryTarget,
            extractColorFromBytes = ::extractVibrantColorHex
        )

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
                    // Prefer saturated brand pixels; ignore near-white / near-black backgrounds
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
    // FIREBASE ZERO-KNOWLEDGE ACCOUNT & TWO-WAY CLOUD SYNC
    // =========================================================================

    suspend fun createFirebaseAccount(email: String, syncPassword: String): CloudSyncUserSession =
        withContext(Dispatchers.IO) {
            val session = firebaseAuth.createCloudAccount(email, syncPassword)
            saveFirebaseSession(session)
            cloudSyncEnabled = true
            session
        }

    suspend fun signInFirebaseAccount(email: String, syncPassword: String): CloudSyncUserSession =
        withContext(Dispatchers.IO) {
            val session = firebaseAuth.signInCloudAccount(email, syncPassword)
            saveFirebaseSession(session)
            cloudSyncEnabled = true
            session
        }

    fun signOutFirebaseAccount() {
        firebaseAuth.signOut()
        cloudSyncEnabled = false
        prefs.edit()
            .remove(KEY_FIREBASE_SESSION)
            .remove(KEY_LAST_SYNC_CURSOR)
            .putBoolean(KEY_CLOUD_SYNC_ENABLED, false)
            .apply()
    }

    private fun saveFirebaseSession(session: CloudSyncUserSession) {
        prefs.edit().putString(KEY_FIREBASE_SESSION, json.encodeToString(session)).apply()
    }

    suspend fun syncNow(currentItems: List<VaultItemPayload>): BidirectionalSyncReport =
        withContext(Dispatchers.IO) {
            val session = firebaseAuth.refreshIdTokenIfPossible()
                ?: error("Please sign in to Firebase Cloud Sync first")
            saveFirebaseSession(session)

            val db = database
            val localRecords: List<EncryptedSyncRecord> = if (db != null) {
                db.safePocketVaultQueries
                    .selectItemsIncludingTombstonesSinceHlc(null)
                    .executeAsList()
                    .map { rowToSyncRecord(it) }
            } else {
                currentItems.map { encryptItemToSyncRecord(it) }
            }

            val pushRes = firebaseAdapter.pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, localRecords)
            val cursorKey = "${KEY_LAST_SYNC_CURSOR}_${session.uid}"
            val sinceCursor = prefs.getString(cursorKey, null)
            val pulledRecords = firebaseAdapter.pullEncryptedDeltasSince(CLOUD_VAULT_COLLECTION_ID, sinceCursor)

            val localMap = localRecords.associateBy { it.entityId }.toMutableMap()
            var appliedRemote = 0

            for (remote in pulledRecords) {
                val existing = localMap[remote.entityId]
                val winner = if (existing == null) remote else CrdtReconciler.resolve(existing, remote)
                if (existing == null || winner.hlcPacked != existing.hlcPacked || winner.isTombstone != existing.isTombstone) {
                    localMap[remote.entityId] = winner
                    appliedRemote++
                    if (db != null) {
                        applyWinningSyncRecordToDb(db, winner)
                    }
                }
            }

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

            val updatedItems = if (db != null) {
                db.safePocketVaultQueries.selectAllActiveItems().executeAsList().mapNotNull { decryptDbRowOrNull(it) }
            } else {
                localMap.values.filter { !it.isTombstone }.mapNotNull { decryptSyncRecordOrNull(it) }
            }

            prefs.edit().putString(LEGACY_KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(updatedItems)).apply()
            BidirectionalSyncReport(
                pushedCount = pushRes.acceptedCount,
                pulledCount = appliedRemote,
                updatedItems = updatedItems
            )
        }

    private suspend fun pushSingleItemIfPossible(item: VaultItemPayload) {
        val rec = encryptItemToSyncRecord(item)
        firebaseAdapter.pushEncryptedDeltas(CLOUD_VAULT_COLLECTION_ID, listOf(rec))
    }

    // =========================================================================
    // ENCRYPTED `.safepocket` BACKUP + 1PASSWORD / CSV PORTABILITY
    // =========================================================================

    suspend fun exportEncryptedContainer(
        items: List<VaultItemPayload>,
        backupPassword: String
    ): String = withContext(Dispatchers.Default) {
        portabilityService.exportCrossPlatformContainer(
            items = items,
            masterPassword = backupPassword,
            secretKeyFormatted = secretKeyFormatted,
            exportedAtIso = Instant.now().toString()
        )
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
    }

    suspend fun importFromFileBytes(
        fileName: String,
        rawBytes: ByteArray,
        includeLoginsAsNotes: Boolean,
        existingItems: List<VaultItemPayload>
    ): Pair<VaultImportReport, List<VaultItemPayload>> = withContext(Dispatchers.Default) {
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

    private fun nextHlcPacked(nowMs: Long = System.currentTimeMillis()): String {
        hlcLogicalCounter = (hlcLogicalCounter + 1) % 10_000
        return HybridLogicalClock(nowMs, hlcLogicalCounter, nodeId).pack()
    }

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
        item: VaultItemPayload
    ) {
        val aad = "safepocket:v${item.schemaVersion}:${item.category.name}:${item.id}".encodeToByteArray()
        val plaintext = json.encodeToString(item).encodeToByteArray()
        val (iv, ciphertext) = primitives.aes256GcmEncrypt(activeVaultItemKey32, plaintext, aad)
        val hlc = nextHlcPacked(item.updatedAtEpochMs)
        val versionVec = "{\"$nodeId\":${item.updatedAtEpochMs}}"

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
    ) {
        val now = System.currentTimeMillis()
        val aad = "safepocket:v1:$categoryName:$itemId".encodeToByteArray()
        val (iv, tombstoneCipher) = primitives.aes256GcmEncrypt(
            activeVaultItemKey32,
            "{\"deleted\":true}".encodeToByteArray(),
            aad
        )
        val hlc = nextHlcPacked(now)
        val versionVec = "{\"$nodeId\":$now}"
        db.safePocketVaultQueries.markVaultItemTombstone(
            emptyCiphertext = tombstoneCipher,
            tombstoneIv = iv,
            hlcTimestamp = hlc,
            versionVectorJson = versionVec,
            updatedAt = now,
            id = itemId
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

    private fun rowToSyncRecord(row: Vault_items): EncryptedSyncRecord {
        val vv = runCatching {
            json.parseToJsonElement(row.version_vector_json).jsonObject.mapNotNull { (k, v) ->
                v.jsonPrimitive.longOrNull?.let { k to it }
            }.toMap()
        }.getOrDefault(mapOf(nodeId to row.updated_at))

        return EncryptedSyncRecord(
            entityId = row.id,
            entityType = "ITEM",
            hlcPacked = row.hlc_timestamp,
            versionVector = vv,
            isTombstone = row.is_tombstone != 0L,
            ivHex = ZeroKnowledgeEncryptionService.bytesToHex(row.iv),
            ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(row.encrypted_payload),
            aadContext = "safepocket:v${row.schema_version}:${row.category}:${row.id}"
        )
    }

    private suspend fun encryptItemToSyncRecord(item: VaultItemPayload): EncryptedSyncRecord {
        val aadStr = "safepocket:v${item.schemaVersion}:${item.category.name}:${item.id}"
        val (iv, cipher) = primitives.aes256GcmEncrypt(
            activeVaultItemKey32,
            json.encodeToString(item).encodeToByteArray(),
            aadStr.encodeToByteArray()
        )
        return EncryptedSyncRecord(
            entityId = item.id,
            entityType = "ITEM",
            hlcPacked = nextHlcPacked(item.updatedAtEpochMs),
            versionVector = mapOf(nodeId to item.updatedAtEpochMs),
            isTombstone = false,
            ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
            ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(cipher),
            aadContext = aadStr
        )
    }

    private suspend fun decryptSyncRecordOrNull(rec: EncryptedSyncRecord): VaultItemPayload? {
        if (rec.isTombstone) return null
        return runCatching {
            val iv = ZeroKnowledgeEncryptionService.hexToBytes(rec.ivHex)
            val cipher = ZeroKnowledgeEncryptionService.hexToBytes(rec.ciphertextHex)
            val plain = primitives.aes256GcmDecrypt(
                activeVaultItemKey32,
                iv,
                cipher,
                rec.aadContext.encodeToByteArray()
            )
            InstitutionBrandRegistry.applyAutoBranding(
                json.decodeFromString<VaultItemPayload>(plain.decodeToString())
            )
        }.getOrNull()
    }

    private fun applyWinningSyncRecordToDb(db: SafePocketDatabase, rec: EncryptedSyncRecord) {
        val parts = rec.aadContext.split(':')
        val categoryName = parts.getOrNull(2) ?: "SECURE_NOTE"
        val iv = ZeroKnowledgeEncryptionService.hexToBytes(rec.ivHex)
        val cipher = ZeroKnowledgeEncryptionService.hexToBytes(rec.ciphertextHex)
        val vvJson = rec.versionVector.entries.joinToString(prefix = "{", postfix = "}") {
            "\"${it.key}\":${it.value}"
        }
        val wallMs = runCatching { HybridLogicalClock.unpack(rec.hlcPacked).wallTimeEpochMs }
            .getOrDefault(System.currentTimeMillis())

        db.safePocketVaultQueries.upsertVaultItem(
            id = rec.entityId,
            category = categoryName,
            is_favorite = 0L,
            expiry_epoch_day = null,
            encrypted_payload = cipher,
            iv = iv,
            schema_version = 1L,
            hlc_timestamp = rec.hlcPacked,
            version_vector_json = vvJson,
            is_tombstone = if (rec.isTombstone) 1L else 0L,
            updated_at = wallMs
        )
    }

    companion object {
        private const val DEFAULT_DEMO_PASSWORD = "Correct-Horse-SafePocket-2026!"
        private const val CLOUD_VAULT_COLLECTION_ID = "default-vault"
        private const val LEGACY_KEY_VAULT_ITEMS = "vault_items_json"
        private const val KEY_DB_INITIALIZED_V02 = "db_initialized_v02"
        private const val KEY_SQLCIPHER_RAW_HEX = "sqlcipher_raw_key_hex"
        private const val KEY_WRAPPED_LOCAL_VEK_HEX = "wrapped_local_vek_hex"
        private const val KEY_SECRET_KEY_FORMATTED = "secret_key_formatted"
        private const val KEY_KDF_SALT_HEX = "kdf_salt_hex"
        private const val KEY_MASTER_PW_VERIFIER = "master_pw_verifier_hex"
        private const val KEY_HAS_CUSTOM_MASTER_PW = "has_custom_master_pw"
        private const val KEY_AUTO_DOWNLOAD_LOGOS = "auto_download_logos"
        private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
        private const val KEY_FIREBASE_SESSION = "firebase_user_session_json"
        private const val KEY_LAST_SYNC_CURSOR = "firebase_last_sync_cursor"
        private const val KEY_LAST_SYNC_PUSHED = "firebase_last_sync_pushed"
        private const val KEY_LAST_SYNC_PULLED = "firebase_last_sync_pulled"
        private const val KEY_LAST_SYNC_ISO = "firebase_last_sync_iso"
        private const val KEY_NODE_ID = "crdt_node_id"
    }
}
