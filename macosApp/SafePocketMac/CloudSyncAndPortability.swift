import Foundation
import CryptoKit
import CommonCrypto
import Security
import IOKit
import Darwin

// MARK: - Zero-Knowledge Cloud Sync & Keychain Session (100% Compatible with Android & ChromeOS PWA)

enum MacCloudProvider: String, CaseIterable, Identifiable {
    case firebase = "FIREBASE"
    case googleDrive = "GDRIVE_APPDATA"

    var id: String { rawValue }
}

struct MacCloudUserSession: Codable, Equatable {
    var uid: String
    var email: String
    var idToken: String
    var refreshToken: String?
    var cloudKeyHex: String? = nil
}

struct MacCloudEndpointConfig: Codable, Equatable {
    var firebaseProjectId: String = "safepocket-vault-eu"
    var firebaseWebApiKey: String = "AIzaSyBCeyZx121Eq5xxyWZD4k3w1-9vyr0yIsw"
    var firebaseStorageBucket: String = "safepocket-vault-eu.firebasestorage.app"
    var vaultCollectionId: String = "default-vault"
    var googleDriveBearerToken: String = ""
}

struct MacEncryptedSyncDelta: Codable {
    var entityId: String
    var entityType: String
    var hlcPacked: String
    var versionVectorJson: String
    var ivHex: String
    var ciphertextHex: String
    var aadContext: String
    var isTombstone: Bool
}

struct MacPendingTombstone: Codable, Equatable {
    var id: String
    var category: String
    var hlcPacked: String
    var deletedAtMs: Int64
}

// MARK: - Canonical Cross-Platform Wire JSON Schema (100% Identical to Android VaultItemPayload & PWA VaultItem)

struct MacCanonicalWireField: Codable {
    var id: String
    var labelKey: String
    var isCustomLabel: Bool = false
    var value: String
    var valueType: String = "TEXT"
    var isSensitive: Bool = false
}

struct MacCanonicalWireCardTheme: Codable {
    var paletteId: String = "obsidian_slate"
    var customColorHex: String? = nil
    var showVisualCard: Bool? = true
    var barcodeFormat: String? = nil
    var networkBrand: String? = nil
    var institutionId: String? = nil
    var institutionName: String? = nil
    var institutionDomain: String? = nil
    var institutionLogoDataUri: String? = nil
    var autoBrandColor: Bool? = true
}

struct MacCanonicalWireAttachment: Codable {
    var id: String
    var fileName: String
    var mimeType: String
    var side: String? = nil
    var sizeBytes: Int64
    var sha256Hex: String
    var ivHex: String? = nil
    var fileKeyHex: String? = nil
    var ciphertextBase64: String? = nil
    var blobSynced: Bool? = nil
    var storageObjectPath: String? = nil
}

struct MacCanonicalWireVaultItem: Codable {
    var schemaVersion: Int = 1
    var id: String
    var category: String
    var title: String
    var subtitle: String = ""
    var favorite: Bool = false
    var pinned: Bool? = false
    var isSampleItem: Bool? = false
    var tags: [String] = []
    var cardTheme: MacCanonicalWireCardTheme? = nil
    var documentSubtype: String? = nil
    var fields: [MacCanonicalWireField] = []
    var attachments: [MacCanonicalWireAttachment] = []
    var createdAtEpochMs: Int64
    var updatedAtEpochMs: Int64
}

extension MacVaultEntry {
    static let knownBuiltInSampleIds: Set<String> = [
        "item-card-db-visa",
        "item-card-n26-metal",
        "item-card-alpine-metal",
        "item-bank-ing-giro",
        "item-bank-ing-sepa",
        "item-bank-sepa-main",
        "item-ins-tk-egk",
        "item-ins-ehic-eu",
        "item-mem-bahncard",
        "item-mem-adac-plus",
        "item-identity-primary",
        "item-id-clara",
        "item-id-eu-citizen",
        "item-doc-hellenic-passport",
        "item-doc-cie-italy",
        "item-doc-passport-de",
        "item-doc-passport-eu",
        "item-note-home-safe",
        "item-note-emergency",
        "item-note-emergency-kit",
        "item-note-vault-codes"
    ]

    var isBuiltInSampleEntry: Bool {
        isSampleItem && Self.knownBuiltInSampleIds.contains(id) || isSampleItem
    }

    func toCanonicalWireData() throws -> Data {
        let epochMs = Int64((updatedAt.timeIntervalSince1970 * 1000.0).rounded())
        let domainVal = websiteUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let theme: MacCanonicalWireCardTheme? = (customColorHex != nil || networkBrand != nil || !domainVal.isEmpty)
            ? MacCanonicalWireCardTheme(
                paletteId: customColorHex != nil ? "custom" : "obsidian_slate",
                customColorHex: customColorHex,
                showVisualCard: true,
                networkBrand: networkBrand,
                institutionDomain: domainVal.isEmpty ? nil : domainVal,
                autoBrandColor: true
            )
            : nil

        let wireFields = fields.map { f in
            MacCanonicalWireField(
                id: f.id,
                labelKey: f.labelKey,
                isCustomLabel: f.isCustomLabel,
                value: f.value,
                valueType: f.valueType.rawValue,
                isSensitive: f.isSensitive
            )
        }

        let wireAttachments = attachments.map { a in
            MacCanonicalWireAttachment(
                id: a.id,
                fileName: a.fileName,
                mimeType: a.mimeType,
                side: a.side,
                sizeBytes: a.sizeBytes,
                sha256Hex: a.sha256Hex,
                ivHex: a.ivHex,
                fileKeyHex: a.fileKeyHex,
                ciphertextBase64: a.sizeBytes <= 16_384 ? a.ciphertextBase64 : nil,
                blobSynced: a.blobSynced,
                storageObjectPath: a.storageObjectPath
            )
        }

        let wire = MacCanonicalWireVaultItem(
            schemaVersion: 1,
            id: id,
            category: category,
            title: title,
            subtitle: subtitle,
            favorite: favorite,
            pinned: pinned,
            isSampleItem: isSampleItem,
            tags: tags,
            cardTheme: theme,
            documentSubtype: nil,
            fields: wireFields,
            attachments: wireAttachments,
            createdAtEpochMs: epochMs,
            updatedAtEpochMs: epochMs
        )
        return try JSONEncoder().encode(wire)
    }

    static func fromCanonicalOrLegacyData(_ data: Data, hlcPacked: String) -> MacVaultEntry? {
        if let wire = try? JSONDecoder().decode(MacCanonicalWireVaultItem.self, from: data) {
            let mappedFields: [MacVaultField] = wire.fields.map { wf in
                let resolvedType = MacFieldValueType(rawValue: wf.valueType) ?? .text
                return MacVaultField(
                    id: wf.id,
                    labelKey: wf.labelKey,
                    isCustomLabel: wf.isCustomLabel,
                    value: wf.value,
                    valueType: resolvedType,
                    isSensitive: wf.isSensitive
                )
            }
            let mappedAttachments: [MacVaultAttachment] = wire.attachments.map { wa in
                MacVaultAttachment(
                    id: wa.id,
                    fileName: wa.fileName,
                    mimeType: wa.mimeType,
                    side: wa.side ?? "front",
                    sizeBytes: wa.sizeBytes,
                    sha256Hex: wa.sha256Hex,
                    ivHex: wa.ivHex ?? "",
                    fileKeyHex: wa.fileKeyHex,
                    ciphertextBase64: wa.ciphertextBase64,
                    blobSynced: wa.blobSynced ?? false,
                    storageObjectPath: wa.storageObjectPath
                )
            }
            let updatedDate = Date(timeIntervalSince1970: Double(wire.updatedAtEpochMs) / 1000.0)
            return MacVaultEntry(
                id: wire.id,
                category: wire.category,
                title: wire.title,
                subtitle: wire.subtitle,
                fields: mappedFields,
                tags: wire.tags,
                attachments: mappedAttachments,
                customColorHex: wire.cardTheme?.customColorHex,
                networkBrand: wire.cardTheme?.networkBrand,
                pinned: wire.pinned ?? false,
                favorite: wire.favorite,
                isSampleItem: wire.isSampleItem ?? false,
                updatedAt: updatedDate,
                hlcPacked: hlcPacked
            )
        }
        if var legacy = try? JSONDecoder().decode(MacVaultEntry.self, from: data) {
            legacy.hlcPacked = hlcPacked
            return legacy
        }
        return nil
    }
}

enum MacKeychainStore {
    private static let legacyService = "com.safepocket.mac.zkvault"
    private static let keybagAAD = Data("safepocket:mac:keybag:v2".utf8)
    private static let lock = NSLock()
    private static var cachedEntries: [String: String]? = nil

    private static var appSupportDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("SafePocket", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(
                at: dir,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
        return dir
    }

    private static var keybagFileURL: URL {
        appSupportDirectory.appendingPathComponent("safepocket_keybag_v2.enc")
    }

    private static func hardwarePlatformUUID() -> String {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        guard service != 0 else { return "mac-fallback-platform-id" }
        defer { IOObjectRelease(service) }
        if let cfProp = IORegistryEntryCreateCFProperty(service, kIOPlatformUUIDKey as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String,
           !cfProp.isEmpty {
            return cfProp
        }
        return "mac-fallback-platform-id"
    }

    private static func deriveKeybagKey() -> SymmetricKey {
        let ikm = Data("safepocket-mac-keybag-v2:\(hardwarePlatformUUID()):\(getuid())".utf8)
        let salt = Data("com.safepocket.mac.keybag.salt.v2".utf8)
        let info = Data("safepocket-aes256gcm-keybag".utf8)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: info,
            outputByteCount: 32
        )
    }

    private static func persistEntriesUnlocked(_ dict: [String: String]) {
        guard let plain = try? JSONEncoder().encode(dict),
              let sealed = try? AES.GCM.seal(plain, using: deriveKeybagKey(), authenticating: keybagAAD),
              let combined = sealed.combined else {
            return
        }
        var url = keybagFileURL
        try? combined.write(to: url, options: .atomic)
        _ = chmod(url.path, 0o600)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    private static func legacyAccountNamesPresent() -> Set<String> {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyService,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let list = result as? [[String: Any]] else {
            return []
        }
        var names: Set<String> = []
        for attrs in list {
            if let acc = attrs[kSecAttrAccount as String] as? String {
                names.insert(acc)
            }
        }
        return names
    }

    private static func loadLegacyKeychainItem(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else {
            return nil
        }
        return item as? Data
    }

    private static func loadOrMigrateUnlocked() -> [String: String] {
        if let existing = cachedEntries {
            return existing
        }

        let url = keybagFileURL
        if FileManager.default.fileExists(atPath: url.path),
           let raw = try? Data(contentsOf: url),
           let box = try? AES.GCM.SealedBox(combined: raw),
           let plain = try? AES.GCM.open(box, using: deriveKeybagKey(), authenticating: keybagAAD),
           let decoded = try? JSONDecoder().decode([String: String].self, from: plain) {
            cachedEntries = decoded
            return decoded
        }

        // One-time seamless migration from legacy per-item Keychain (ad-hoc cdhash ACLs) -> single encrypted keybag
        var migrated: [String: String] = [:]
        let localVaultExists = FileManager.default.fileExists(
            atPath: appSupportDirectory.appendingPathComponent("safepocket_local_vault.enc").path
        ) || FileManager.default.fileExists(
            atPath: appSupportDirectory.appendingPathComponent("safepocket_cloud_mirror.enc").path
        )

        if localVaultExists {
            let present = legacyAccountNamesPresent()
            for acc in ["secret_key", "master_password", "cloud_session", "embedded_cloud_users_v1"] where present.contains(acc) {
                if let data = loadLegacyKeychainItem(account: acc) {
                    migrated[acc] = data.base64EncodedString()
                }
            }
            if !present.isEmpty {
                let cleanupQuery: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrService as String: legacyService
                ]
                SecItemDelete(cleanupQuery as CFDictionary)
            }
        }

        cachedEntries = migrated
        persistEntriesUnlocked(migrated)
        return migrated
    }

    static func saveData(_ data: Data, account: String) {
        lock.lock()
        defer { lock.unlock() }
        var dict = loadOrMigrateUnlocked()
        dict[account] = data.base64EncodedString()
        cachedEntries = dict
        persistEntriesUnlocked(dict)
    }

    static func loadData(account: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        let dict = loadOrMigrateUnlocked()
        guard let b64 = dict[account] else { return nil }
        return Data(base64Encoded: b64)
    }

    static func delete(account: String) {
        lock.lock()
        defer { lock.unlock() }
        var dict = loadOrMigrateUnlocked()
        dict.removeValue(forKey: account)
        cachedEntries = dict
        persistEntriesUnlocked(dict)
    }
}

enum MacZeroKnowledgeCrypto {
    static func generateFormattedSecretKey() -> String {
        let alphabet = Array("23456789ABCDEFGHJKLMNPQRSTUVWXYZ")
        var groups: [String] = []
        for _ in 0..<5 {
            var chunk = ""
            for _ in 0..<5 {
                let idx = Int.random(in: 0..<alphabet.count)
                chunk.append(alphabet[idx])
            }
            groups.append(chunk)
        }
        return "SPK-\(groups.joined(separator: "-"))"
    }

    /// Exact RFC 5869 HKDF-SHA256 derivation matching `FirebaseAuthService.kt` and `liveCloudConnectors.ts`
    /// so accounts created on macOS sign in seamlessly on Android & ChromeOS PWA without sending raw passwords.
    static func deriveZeroKnowledgeCloudAuthPassword(email: String, syncPassword: String) -> String {
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let ikm = SymmetricKey(data: Data("safepocket-cloud-auth:\(cleanEmail):\(syncPassword)".utf8))
        let salt = Data("salt:\(cleanEmail):safepocket-v0.1".utf8)
        let info = Data("safepocket-firebase-auth-verifier-v1".utf8)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: info,
            outputByteCount: 32
        )
        let hex = derived.withUnsafeBytes { rawBuf in
            rawBuf.map { String(format: "%02x", $0) }.joined()
        }
        return "spzk_\(hex)"
    }

    /// Exact RFC 8018 PBKDF2-HMAC-SHA256 (100,000 iterations, 32-byte key) matching `HkdfSha256Pure.deriveCloudSyncKey`
    /// on Android and `deriveZeroKnowledgeCloudSyncKeyHex` on ChromeOS / Web PWA byte-for-byte.
    static func deriveCloudSyncKeyHex(email: String, syncPassword: String, uid: String) -> String {
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let cleanUid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        let passwordStr = "safepocket-zk-cloud-sync-kek-v2:\(cleanEmail):\(syncPassword)"
        let saltData = Data("safepocket-zk-cloud-salt-v2:\(cleanUid):\(cleanEmail)".utf8)
        var derivedBytes = [UInt8](repeating: 0, count: 32)
        let passwordData = Data(passwordStr.utf8)

        passwordData.withUnsafeBytes { pwRaw in
            saltData.withUnsafeBytes { saltRaw in
                _ = CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pwRaw.bindMemory(to: Int8.self).baseAddress,
                    passwordData.count,
                    saltRaw.bindMemory(to: UInt8.self).baseAddress,
                    saltData.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    100_000,
                    &derivedBytes,
                    32
                )
            }
        }
        return derivedBytes.map { String(format: "%02x", $0) }.joined()
    }

    static func symmetricKeyFromHex(_ hex: String) -> SymmetricKey? {
        guard let data = Data(hexString: hex), data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    static func deriveVaultSymmetricKey(masterPassword: String, secretKey: String) -> SymmetricKey {
        let cleanKey = secretKey.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let ikm = SymmetricKey(data: Data("safepocket-2skd:\(masterPassword):\(cleanKey)".utf8))
        let salt = Data("safepocket-mac-vault-salt-v03".utf8)
        let info = Data("safepocket-aes256-gcm-v1".utf8)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: info,
            outputByteCount: 32
        )
    }

    static func encryptData(_ plaintext: Data, key: SymmetricKey, aad: Data) throws -> (ivHex: String, ciphertextHex: String) {
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        let ivHex = Data(nonce).map { String(format: "%02x", $0) }.joined()
        let combinedCipherAndTag = sealed.ciphertext + sealed.tag
        let cipherHex = combinedCipherAndTag.map { String(format: "%02x", $0) }.joined()
        return (ivHex, cipherHex)
    }

    static func decryptData(ivHex: String, ciphertextHex: String, key: SymmetricKey, aad: Data) throws -> Data {
        guard let ivData = Data(hexString: ivHex),
              let combined = Data(hexString: ciphertextHex),
              combined.count >= 16 else {
            throw NSError(domain: "SafePocketCrypto", code: -10, userInfo: [NSLocalizedDescriptionKey: "Malformed AES-256-GCM envelope"])
        }
        let nonce = try AES.GCM.Nonce(data: ivData)
        let cipherBody = combined.prefix(combined.count - 16)
        let tag = combined.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipherBody, tag: tag)
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }

    static func generateAttachmentKeyHex() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func constantTimeHexEquals(_ a: String, _ b: String) -> Bool {
        let lhs = Array(a.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().utf8)
        let rhs = Array(b.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().utf8)
        guard lhs.count == rhs.count, !lhs.isEmpty else { return false }
        var diff: UInt8 = 0
        for i in 0..<lhs.count {
            diff |= (lhs[i] ^ rhs[i])
        }
        return diff == 0
    }

    static func encryptAttachmentBlob(
        attachmentId: String,
        plaintext: Data,
        fileKeyHex: String
    ) throws -> (ivHex: String, sha256Hex: String, encryptedBytes: Data) {
        guard let key = symmetricKeyFromHex(fileKeyHex) else {
            throw NSError(domain: "SafePocketCrypto", code: -21, userInfo: [NSLocalizedDescriptionKey: "Invalid 256-bit attachment key"])
        }
        let nonce = AES.GCM.Nonce()
        let aad = Data("safepocket:attachment:\(attachmentId)".utf8)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        let ivHex = Data(nonce).map { String(format: "%02x", $0) }.joined()
        let encBytes = Data(sealed.ciphertext) + Data(sealed.tag)
        let digest = sha256Hex(encBytes)
        return (ivHex, digest, encBytes)
    }

    static func decryptAttachmentBlob(
        attachmentId: String,
        ivHex: String,
        expectedSha256Hex: String,
        encryptedBytes: Data,
        fileKeyHex: String?,
        fallbackVaultKey: SymmetricKey
    ) throws -> Data {
        let actualSha = sha256Hex(encryptedBytes)
        guard constantTimeHexEquals(actualSha, expectedSha256Hex) else {
            throw NSError(domain: "SafePocketCrypto", code: -22, userInfo: [NSLocalizedDescriptionKey: "Attachment SHA-256 integrity check failed — tampering detected"])
        }
        guard let ivData = Data(hexString: ivHex), encryptedBytes.count >= 16 else {
            throw NSError(domain: "SafePocketCrypto", code: -23, userInfo: [NSLocalizedDescriptionKey: "Malformed encrypted attachment envelope"])
        }
        let targetKey: SymmetricKey
        if let fk = fileKeyHex?.trimmingCharacters(in: .whitespacesAndNewlines), !fk.isEmpty,
           let parsed = symmetricKeyFromHex(fk) {
            targetKey = parsed
        } else {
            targetKey = fallbackVaultKey
        }
        let nonce = try AES.GCM.Nonce(data: ivData)
        let cipherBody = encryptedBytes.prefix(encryptedBytes.count - 16)
        let tag = encryptedBytes.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipherBody, tag: tag)
        let aad = Data("safepocket:attachment:\(attachmentId)".utf8)
        return try AES.GCM.open(box, using: targetKey, authenticating: aad)
    }
}

enum MacEncryptedBlobStore {
    private static let safeIdRegex = try! NSRegularExpression(pattern: "^[a-zA-Z0-9_-]+$")

    static func isSafeAttachmentId(_ raw: String) -> Bool {
        let range = NSRange(raw.startIndex..<raw.endIndex, in: raw)
        return safeIdRegex.firstMatch(in: raw, options: [], range: range) != nil
    }

    private static var blobsDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("SafePocket", isDirectory: true)
            .appendingPathComponent("blobs", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(
                at: dir,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
        return dir
    }

    static func saveEncryptedBlob(_ data: Data, attachmentId: String) throws {
        guard isSafeAttachmentId(attachmentId) else {
            throw NSError(domain: "SafePocketBlobStore", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unsafe attachment ID"])
        }
        var url = blobsDirectory.appendingPathComponent("\(attachmentId).enc")
        try data.write(to: url, options: .atomic)
        _ = chmod(url.path, 0o600)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    static func loadEncryptedBlob(attachmentId: String) -> Data? {
        guard isSafeAttachmentId(attachmentId) else { return nil }
        let url = blobsDirectory.appendingPathComponent("\(attachmentId).enc")
        return try? Data(contentsOf: url)
    }

    static func hasLocalEncryptedBlob(attachmentId: String) -> Bool {
        guard isSafeAttachmentId(attachmentId) else { return false }
        let url = blobsDirectory.appendingPathComponent("\(attachmentId).enc")
        return FileManager.default.fileExists(atPath: url.path)
    }

    static func deleteEncryptedBlob(attachmentId: String) {
        guard isSafeAttachmentId(attachmentId) else { return }
        let url = blobsDirectory.appendingPathComponent("\(attachmentId).enc")
        try? FileManager.default.removeItem(at: url)
    }
}

enum MacCloudSyncClient {
    private struct EmbeddedCloudAccountRecord: Codable {
        let uid: String
        let email: String
        let saltHex: String
        let verifierHex: String
    }

    static func authenticateFirebase(
        action: String, // "signUp" or "signInWithPassword"
        email: String,
        syncPassword: String,
        apiKey: String
    ) async throws -> MacCloudUserSession {
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard cleanEmail.contains("@"), cleanEmail.contains(".") else {
            throw NSError(
                domain: "SafePocketCloudAuth",
                code: -10,
                userInfo: [NSLocalizedDescriptionKey: "Please enter a valid email address (e.g. name@example.com)."]
            )
        }
        guard syncPassword.count >= 6 else {
            throw NSError(
                domain: "SafePocketCloudAuth",
                code: -11,
                userInfo: [NSLocalizedDescriptionKey: "Cloud Sync password must be at least 6 characters."]
            )
        }

        let zkPassword = MacZeroKnowledgeCrypto.deriveZeroKnowledgeCloudAuthPassword(
            email: cleanEmail,
            syncPassword: syncPassword
        )

        let cleanApiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanApiKey.isEmpty {
            var embedded = try authenticateEmbeddedFallback(action: action, cleanEmail: cleanEmail, zkPassword: zkPassword)
            embedded.cloudKeyHex = MacZeroKnowledgeCrypto.deriveCloudSyncKeyHex(
                email: cleanEmail,
                syncPassword: syncPassword,
                uid: embedded.uid
            )
            return embedded
        }

        guard var components = URLComponents(string: "https://identitytoolkit.googleapis.com/v1/accounts:\(action)") else {
            throw URLError(.badURL)
        }
        components.queryItems = [URLQueryItem(name: "key", value: cleanApiKey)]
        guard let url = components.url else { throw URLError(.badURL) }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 15
        let payload: [String: Any] = [
            "email": cleanEmail,
            "password": zkPassword,
            "returnSecureToken": true
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, resp) = try await URLSession.shared.data(for: req)
        let jsonObj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let rawCode = ((jsonObj["error"] as? [String: Any])?["message"] as? String)
                ?? "Firebase Auth \(action) failed (HTTP \(http.statusCode))"
            let friendly = mapFirebaseErrorMessage(rawCode)
            throw NSError(domain: "SafePocketCloudAuth", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: friendly])
        }
        guard let uid = jsonObj["localId"] as? String,
              let resolvedEmail = jsonObj["email"] as? String,
              let idToken = jsonObj["idToken"] as? String else {
            throw NSError(domain: "SafePocketCloudAuth", code: -2, userInfo: [NSLocalizedDescriptionKey: "Incomplete Firebase Auth response"])
        }
        let derivedCloudKeyHex = MacZeroKnowledgeCrypto.deriveCloudSyncKeyHex(
            email: cleanEmail,
            syncPassword: syncPassword,
            uid: uid
        )
        return MacCloudUserSession(
            uid: uid,
            email: resolvedEmail,
            idToken: idToken,
            refreshToken: jsonObj["refreshToken"] as? String,
            cloudKeyHex: derivedCloudKeyHex
        )
    }

    private static func mapFirebaseErrorMessage(_ raw: String) -> String {
        if raw.contains("EMAIL_EXISTS") {
            return "EMAIL_EXISTS: An account with this email already exists — click Sign In instead."
        }
        if raw.contains("EMAIL_NOT_FOUND") {
            return "EMAIL_NOT_FOUND: No account found for this email — click + Create Account first."
        }
        if raw.contains("INVALID_LOGIN_CREDENTIALS") || raw.contains("INVALID_PASSWORD") {
            return "INVALID_LOGIN_CREDENTIALS: Incorrect email or Cloud Sync password (or click + Create Account if new)."
        }
        if raw.contains("TOO_MANY_ATTEMPTS_TRY_LATER") {
            return "TOO_MANY_ATTEMPTS_TRY_LATER: Too many failed attempts. Please wait a moment and try again."
        }
        if raw.contains("CONFIGURATION_NOT_FOUND") || raw.contains("OPERATION_NOT_ALLOWED") {
            return "Enable Email/Password Sign-In once in Firebase Console (Authentication → Sign-in method)."
        }
        return raw
    }

    private static func authenticateEmbeddedFallback(
        action: String,
        cleanEmail: String,
        zkPassword: String
    ) throws -> MacCloudUserSession {
        let accountKey = "embedded_cloud_users_v1"
        var users: [String: EmbeddedCloudAccountRecord] = [:]
        if let raw = MacKeychainStore.loadData(account: accountKey),
           let decoded = try? JSONDecoder().decode([String: EmbeddedCloudAccountRecord].self, from: raw) {
            users = decoded
        }

        if action == "signUp" {
            var saltBytes = [UInt8](repeating: 0, count: 16)
            _ = SecRandomCopyBytes(kSecRandomDefault, saltBytes.count, &saltBytes)
            let saltHex = saltBytes.map { String(format: "%02x", $0) }.joined()
            let verifierInput = Data("embedded-fb-verifier:\(saltHex):\(zkPassword)".utf8)
            let verifierHex = SHA256.hash(data: verifierInput).map { String(format: "%02x", $0) }.joined()
            let uid = "fb-uid-" + String(saltHex.prefix(16))
            let record = EmbeddedCloudAccountRecord(uid: uid, email: cleanEmail, saltHex: saltHex, verifierHex: verifierHex)
            users[cleanEmail] = record
            if let encoded = try? JSONEncoder().encode(users) {
                MacKeychainStore.saveData(encoded, account: accountKey)
            }
            return MacCloudUserSession(uid: uid, email: cleanEmail, idToken: "local-fb-jwt-\(uid)", refreshToken: nil)
        } else {
            guard let existing = users[cleanEmail] else {
                throw NSError(
                    domain: "SafePocketCloudAuth",
                    code: 404,
                    userInfo: [NSLocalizedDescriptionKey: "USER_NOT_FOUND: No Cloud Sync account found for email — click + Create Account first."]
                )
            }
            let verifierInput = Data("embedded-fb-verifier:\(existing.saltHex):\(zkPassword)".utf8)
            let candidateHex = SHA256.hash(data: verifierInput).map { String(format: "%02x", $0) }.joined()
            guard candidateHex == existing.verifierHex else {
                throw NSError(
                    domain: "SafePocketCloudAuth",
                    code: 401,
                    userInfo: [NSLocalizedDescriptionKey: "INVALID_PASSWORD: Cloud Sync authentication failed."]
                )
            }
            return MacCloudUserSession(uid: existing.uid, email: existing.email, idToken: "local-fb-jwt-\(existing.uid)", refreshToken: nil)
        }
    }

    private static func inspectJwtClaims(_ idToken: String) -> (exp: TimeInterval?, aud: String?) {
        let parts = idToken.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ".")
        guard parts.count >= 2 else { return (nil, nil) }
        var base64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 {
            base64.append("=")
        }
        guard let data = Data(base64Encoded: base64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return (nil, nil)
        }
        let exp: TimeInterval? = (obj["exp"] as? NSNumber)?.doubleValue
        let aud: String? = obj["aud"] as? String
        return (exp, aud)
    }

    private static func sanitizeBearerToken(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("bearer ") {
            return String(trimmed.dropFirst("bearer ".count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return trimmed
    }

    static func refreshFirebaseTokenIfPossible(
        session: MacCloudUserSession,
        apiKey: String,
        expectedProjectId: String? = nil,
        force: Bool = false
    ) async throws -> MacCloudUserSession {
        if session.idToken.hasPrefix("local-fb-jwt-") {
            return session
        }

        let claims = inspectJwtClaims(session.idToken)
        let nowSec = Date().timeIntervalSince1970
        let cleanProject = expectedProjectId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let matchesProject = cleanProject == nil || cleanProject!.isEmpty || claims.aud == nil || claims.aud == cleanProject
        let isFresh = (claims.exp != nil) && ((claims.exp ?? 0) - nowSec > 300) && matchesProject

        if !force && isFresh {
            return session
        }

        let cleanKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanRefresh = session.refreshToken?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !cleanKey.isEmpty, !cleanRefresh.isEmpty, matchesProject,
           var components = URLComponents(string: "https://securetoken.googleapis.com/v1/token") {
            components.queryItems = [URLQueryItem(name: "key", value: cleanKey)]
            if let url = components.url {
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.timeoutInterval = 15
                req.httpBody = try? JSONSerialization.data(withJSONObject: [
                    "grant_type": "refresh_token",
                    "refresh_token": cleanRefresh
                ])
                if let (data, resp) = try? await URLSession.shared.data(for: req),
                   let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let nextToken = (obj["id_token"] as? String) ?? (obj["idToken"] as? String),
                   !nextToken.isEmpty {
                    var updated = session
                    updated.idToken = nextToken
                    if let nextRefresh = (obj["refresh_token"] as? String) ?? (obj["refreshToken"] as? String),
                       !nextRefresh.isEmpty {
                        updated.refreshToken = nextRefresh
                    }
                    if let nextUid = obj["user_id"] as? String, !nextUid.isEmpty {
                        updated = MacCloudUserSession(
                            uid: nextUid,
                            email: updated.email,
                            idToken: updated.idToken,
                            refreshToken: updated.refreshToken,
                            cloudKeyHex: session.cloudKeyHex
                        )
                    }
                    return updated
                }
            }
        }

        if !matchesProject || claims.exp == nil || (claims.exp ?? 0) <= nowSec + 10 {
            throw NSError(
                domain: "SafePocketCloudAuth",
                code: 401,
                userInfo: [NSLocalizedDescriptionKey: "Cloud Sync session expired — please sign in again in Settings."]
            )
        }
        return session
    }

    static func pushDeltasToFirestore(
        deltas: [MacEncryptedSyncDelta],
        session: MacCloudUserSession,
        config: MacCloudEndpointConfig
    ) async throws -> Int {
        guard !deltas.isEmpty else { return 0 }
        let cleanProjectId = config.firebaseProjectId.trimmingCharacters(in: .whitespacesAndNewlines)
        if session.idToken.hasPrefix("local-fb-jwt-") || cleanProjectId.isEmpty {
            return deltas.count
        }
        let urlStr = "https://firestore.googleapis.com/v1/projects/\(cleanProjectId)/databases/(default)/documents:commit"
        guard let url = URL(string: urlStr) else { throw URLError(.badURL) }

        let writes: [[String: Any]] = deltas.map { d in
            let docName = "projects/\(cleanProjectId)/databases/(default)/documents/users/\(session.uid)/vaults/\(config.vaultCollectionId)/deltas/\(d.entityId)"
            return [
                "update": [
                    "name": docName,
                    "fields": [
                        "entityId": ["stringValue": d.entityId],
                        "entityType": ["stringValue": d.entityType],
                        "hlcPacked": ["stringValue": d.hlcPacked],
                        "versionVector": ["stringValue": d.versionVectorJson],
                        "ivHex": ["stringValue": d.ivHex],
                        "ciphertextHex": ["stringValue": d.ciphertextHex],
                        "aadContext": ["stringValue": d.aadContext],
                        "isTombstone": ["booleanValue": d.isTombstone]
                    ]
                ]
            ]
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(sanitizeBearerToken(session.idToken))", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 20
        req.httpBody = try JSONSerialization.data(withJSONObject: ["writes": writes])

        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let raw = String(data: data, encoding: .utf8) ?? ""
            throw NSError(domain: "SafePocketFirestore", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Firestore push failed (\(http.statusCode)): \(raw.prefix(160))"])
        }
        return deltas.count
    }

    static func pullDeltasFromFirestore(
        sinceHlc: String?,
        session: MacCloudUserSession,
        config: MacCloudEndpointConfig
    ) async throws -> [MacEncryptedSyncDelta] {
        let cleanProjectId = config.firebaseProjectId.trimmingCharacters(in: .whitespacesAndNewlines)
        if session.idToken.hasPrefix("local-fb-jwt-") || cleanProjectId.isEmpty {
            return []
        }
        let urlStr = "https://firestore.googleapis.com/v1/projects/\(cleanProjectId)/databases/(default)/documents/users/\(session.uid)/vaults/\(config.vaultCollectionId):runQuery"
        guard let url = URL(string: urlStr) else { throw URLError(.badURL) }

        var structuredQuery: [String: Any] = [
            "from": [["collectionId": "deltas"]],
            "orderBy": [["field": ["fieldPath": "hlcPacked"], "direction": "ASCENDING"]]
        ]
        if let since = sinceHlc, !since.isEmpty {
            structuredQuery["where"] = [
                "fieldFilter": [
                    "field": ["fieldPath": "hlcPacked"],
                    "op": "GREATER_THAN",
                    "value": ["stringValue": since]
                ]
            ]
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(sanitizeBearerToken(session.idToken))", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 20
        req.httpBody = try JSONSerialization.data(withJSONObject: ["structuredQuery": structuredQuery])

        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let raw = String(data: data, encoding: .utf8) ?? ""
            throw NSError(domain: "SafePocketFirestore", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Firestore pull failed (\(http.statusCode)): \(raw.prefix(160))"])
        }
        guard let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }

        var out: [MacEncryptedSyncDelta] = []
        for elem in arr {
            guard let doc = elem["document"] as? [String: Any],
                  let f = doc["fields"] as? [String: Any],
                  let entityId = (f["entityId"] as? [String: Any])?["stringValue"] as? String,
                  let hlc = (f["hlcPacked"] as? [String: Any])?["stringValue"] as? String,
                  let ivHex = (f["ivHex"] as? [String: Any])?["stringValue"] as? String,
                  let ciphertextHex = (f["ciphertextHex"] as? [String: Any])?["stringValue"] as? String else {
                continue
            }
            let entityType = ((f["entityType"] as? [String: Any])?["stringValue"] as? String) ?? "ITEM"
            let vv = ((f["versionVector"] as? [String: Any])?["stringValue"] as? String) ?? "{}"
            let aad = ((f["aadContext"] as? [String: Any])?["stringValue"] as? String) ?? ""
            let tomb = ((f["isTombstone"] as? [String: Any])?["booleanValue"] as? Bool) ?? false
            out.append(
                MacEncryptedSyncDelta(
                    entityId: entityId,
                    entityType: entityType,
                    hlcPacked: hlc,
                    versionVectorJson: vv,
                    ivHex: ivHex,
                    ciphertextHex: ciphertextHex,
                    aadContext: aad,
                    isTombstone: tomb
                )
            )
        }
        return out
    }

    static func uploadEncryptedBlobToFirebaseStorage(
        attachmentId: String,
        encryptedBytes: Data,
        sha256Hex: String,
        session: MacCloudUserSession,
        config: MacCloudEndpointConfig
    ) async throws -> String {
        guard MacEncryptedBlobStore.isSafeAttachmentId(attachmentId) else {
            throw NSError(domain: "SafePocketCloudStorage", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid attachmentId"])
        }
        guard encryptedBytes.count > 0 && encryptedBytes.count < 25 * 1024 * 1024 else {
            throw NSError(domain: "SafePocketCloudStorage", code: -2, userInfo: [NSLocalizedDescriptionKey: "Encrypted blob exceeds 25 MiB limit"])
        }
        let cleanBucket = config.firebaseStorageBucket.trimmingCharacters(in: .whitespacesAndNewlines)
        let objectPath = "users/\(session.uid)/vaults/\(config.vaultCollectionId)/blobs/\(attachmentId).enc"
        if session.idToken.hasPrefix("local-fb-jwt-") || cleanBucket.isEmpty {
            return objectPath
        }
        let encodedBucket = cleanBucket.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? cleanBucket
        var allowedObj = CharacterSet.urlQueryAllowed
        allowedObj.remove(charactersIn: "/?&=+")
        let encodedObj = objectPath.addingPercentEncoding(withAllowedCharacters: allowedObj) ?? objectPath
        guard let url = URL(string: "https://firebasestorage.googleapis.com/v0/b/\(encodedBucket)/o?uploadType=multipart&name=\(encodedObj)") else {
            throw URLError(.badURL)
        }

        let boundary = "safepocket_zk_storage_blob_v1"
        let metaObj: [String: Any] = [
            "name": objectPath,
            "contentType": "application/octet-stream",
            "metadata": [
                "ciphertextSha256": sha256Hex.lowercased()
            ]
        ]
        let metaData = try JSONSerialization.data(withJSONObject: metaObj)
        var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body.append(metaData)
        body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/octet-stream\r\n\r\n".utf8))
        body.append(encryptedBytes)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(sanitizeBearerToken(session.idToken))", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 45
        req.httpBody = body

        let (respData, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let raw = String(data: respData, encoding: .utf8) ?? ""
            throw NSError(domain: "SafePocketCloudStorage", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Firebase Storage upload failed (\(http.statusCode)): \(raw.prefix(140))"])
        }
        return objectPath
    }

    static func downloadEncryptedBlobFromFirebaseStorage(
        attachmentId: String,
        expectedSha256Hex: String,
        session: MacCloudUserSession,
        config: MacCloudEndpointConfig
    ) async throws -> Data? {
        guard MacEncryptedBlobStore.isSafeAttachmentId(attachmentId) else { return nil }
        let cleanBucket = config.firebaseStorageBucket.trimmingCharacters(in: .whitespacesAndNewlines)
        if session.idToken.hasPrefix("local-fb-jwt-") || cleanBucket.isEmpty {
            return nil
        }
        let objectPath = "users/\(session.uid)/vaults/\(config.vaultCollectionId)/blobs/\(attachmentId).enc"
        let encodedBucket = cleanBucket.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? cleanBucket
        var allowedObj = CharacterSet.urlPathAllowed
        allowedObj.remove(charactersIn: "/")
        let encodedObj = objectPath.addingPercentEncoding(withAllowedCharacters: allowedObj) ?? objectPath
        guard let url = URL(string: "https://firebasestorage.googleapis.com/v0/b/\(encodedBucket)/o/\(encodedObj)?alt=media") else {
            throw URLError(.badURL)
        }

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(sanitizeBearerToken(session.idToken))", forHTTPHeaderField: "Authorization")
        req.timeoutInterval = 45

        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse {
            if http.statusCode == 404 { return nil }
            if !(200..<300).contains(http.statusCode) {
                throw NSError(domain: "SafePocketCloudStorage", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Firebase Storage download failed (\(http.statusCode))"])
            }
        }
        let actualSha = MacZeroKnowledgeCrypto.sha256Hex(data)
        guard MacZeroKnowledgeCrypto.constantTimeHexEquals(actualSha, expectedSha256Hex) else {
            throw NSError(domain: "SafePocketCloudStorage", code: -99, userInfo: [NSLocalizedDescriptionKey: "Firebase Storage .enc SHA-256 integrity check failed"])
        }
        return data
    }
}

// MARK: - Encrypted Backup (.safepocket), 1Password (.1pux / .csv), CSV & Emergency Kit Portability

struct MacEncryptedBackupContainer: Codable {
    var format: String = "safepocket-encrypted-backup-v2"
    var createdAtIso: String
    var ivHex: String
    var ciphertextHex: String
}

enum MacPortabilityEngine {
    static func exportEncryptedContainer(entries: [MacVaultEntry], backupPassword: String, secretKey: String) throws -> Data {
        let plainJson = try JSONEncoder().encode(entries)
        let key = MacZeroKnowledgeCrypto.deriveVaultSymmetricKey(masterPassword: backupPassword, secretKey: secretKey)
        let aad = Data("safepocket-backup-v2".utf8)
        let enc = try MacZeroKnowledgeCrypto.encryptData(plainJson, key: key, aad: aad)
        let container = MacEncryptedBackupContainer(
            createdAtIso: ISO8601DateFormatter().string(from: Date()),
            ivHex: enc.ivHex,
            ciphertextHex: enc.ciphertextHex
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(container)
    }

    static func restoreEncryptedContainer(_ data: Data, backupPassword: String, secretKey: String) throws -> [MacVaultEntry] {
        let container = try JSONDecoder().decode(MacEncryptedBackupContainer.self, from: data)
        let key = MacZeroKnowledgeCrypto.deriveVaultSymmetricKey(masterPassword: backupPassword, secretKey: secretKey)
        let aad = Data("safepocket-backup-v2".utf8)
        let plain = try MacZeroKnowledgeCrypto.decryptData(
            ivHex: container.ivHex,
            ciphertextHex: container.ciphertextHex,
            key: key,
            aad: aad
        )
        return try JSONDecoder().decode([MacVaultEntry].self, from: plain)
    }

    static func exportSafePocketCsv(entries: [MacVaultEntry]) -> Data {
        var rows = ["id,category,title,subtitle,primary_label,primary_value,website_url,tags"]
        for e in entries {
            rows.append([
                csvEscape(e.id),
                csvEscape(e.category),
                csvEscape(e.title),
                csvEscape(e.subtitle),
                csvEscape(e.primaryLabel),
                csvEscape(e.primaryValue),
                csvEscape(e.websiteUrl),
                csvEscape(e.tags.joined(separator: ";"))
            ].joined(separator: ","))
        }
        return Data(rows.joined(separator: "\n").utf8)
    }

    static func export1PasswordCsv(entries: [MacVaultEntry]) -> Data {
        var rows = ["Title,Category,Cardholder Name,Number,Website,Notes"]
        for e in entries {
            let holder = e.fields.first(where: { $0.labelKey == "field_cardholder" || $0.labelKey == "field_account_holder" })?.value ?? ""
            let notes = e.fields.first(where: { $0.labelKey == "field_markdown_note" })?.value ?? e.subtitle
            rows.append([
                csvEscape(e.title),
                csvEscape(e.category),
                csvEscape(holder),
                csvEscape(e.primaryValue),
                csvEscape(e.websiteUrl),
                csvEscape(notes)
            ].joined(separator: ","))
        }
        return Data(rows.joined(separator: "\n").utf8)
    }

    static func export1Password1PuxJson(entries: [MacVaultEntry]) throws -> Data {
        let items = entries.map { e -> [String: Any] in
            [
                "uuid": e.id,
                "categoryUuid": e.category,
                "overview": [
                    "title": e.title,
                    "subtitle": e.subtitle,
                    "url": e.websiteUrl
                ],
                "details": [
                    "fields": e.fields.map { f in
                        ["id": f.id, "title": f.labelKey, "value": f.value]
                    }
                ]
            ]
        }
        let root: [String: Any] = [
            "format": "safepocket-1pux-export",
            "accounts": [["vaults": [["attrs": ["name": "SafePocket"], "items": items]]]]
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted])
    }

    static func exportPlainJson(entries: [MacVaultEntry]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(entries)
    }

    static func importFromCsvOr1Pux(data: Data, fileName: String, includeLoginsAsNotes: Bool) throws -> [MacVaultEntry] {
        if let jsonObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let accounts = jsonObj["accounts"] as? [[String: Any]] {
            var imported: [MacVaultEntry] = []
            for acc in accounts {
                for vault in (acc["vaults"] as? [[String: Any]]) ?? [] {
                    for rawItem in (vault["items"] as? [[String: Any]]) ?? [] {
                        let catRaw = ((rawItem["categoryUuid"] as? String) ?? "SECURE_NOTE").uppercased()
                        if catRaw.contains("LOGIN") || catRaw == "001" {
                            if !includeLoginsAsNotes { continue }
                        }
                        let overview = (rawItem["overview"] as? [String: Any]) ?? [:]
                        let title = (overview["title"] as? String) ?? "Imported 1Password Item"
                        let subtitle = (overview["subtitle"] as? String) ?? ""
                        let url = (overview["url"] as? String) ?? ""
                        var fields: [MacVaultField] = []
                        if !url.isEmpty {
                            fields.append(.init(id: UUID().uuidString, labelKey: "field_institution_domain", value: url, valueType: .url))
                        }
                        if let details = rawItem["details"] as? [String: Any],
                           let fList = details["fields"] as? [[String: Any]] {
                            for rf in fList {
                                let k = (rf["title"] as? String) ?? "Field"
                                let v = (rf["value"] as? String) ?? ""
                                if !v.isEmpty {
                                    fields.append(.init(id: UUID().uuidString, labelKey: k, isCustomLabel: !k.hasPrefix("field_"), value: v))
                                }
                            }
                        }
                        imported.append(
                            MacVaultEntry(
                                id: "imp-\(UUID().uuidString.prefix(10))",
                                category: MacCategoryTemplates.categories.contains(catRaw) ? catRaw : "SECURE_NOTE",
                                title: title,
                                subtitle: subtitle,
                                fields: fields,
                                pinned: false,
                                favorite: false,
                                isSampleItem: false,
                                updatedAt: Date()
                            )
                        )
                    }
                }
            }
            return imported
        }

        let text = String(data: data, encoding: .utf8) ?? ""
        let lines = text.components(separatedBy: .newlines).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard lines.count >= 2 else { return [] }
        let headers = parseCsvRow(lines[0]).map { $0.lowercased().trimmingCharacters(in: .whitespaces) }
        var imported: [MacVaultEntry] = []

        for line in lines.dropFirst() {
            let cols = parseCsvRow(line)
            func col(_ names: [String]) -> String {
                for n in names {
                    if let i = headers.firstIndex(of: n), i < cols.count { return cols[i] }
                }
                return ""
            }
            let rawCat = col(["category", "type"]).uppercased()
            if rawCat.contains("LOGIN") && !includeLoginsAsNotes { continue }
            let resolvedCat: String
            if MacCategoryTemplates.categories.contains(rawCat) {
                resolvedCat = rawCat
            } else if rawCat.contains("CARD") || rawCat.contains("CREDIT") {
                resolvedCat = "PAYMENT_CARD"
            } else if rawCat.contains("BANK") {
                resolvedCat = "BANK_ACCOUNT"
            } else if rawCat.contains("IDENT") {
                resolvedCat = "IDENTITY"
            } else {
                resolvedCat = "SECURE_NOTE"
            }

            let title = col(["title", "name"]).isEmpty ? "Imported Entry" : col(["title", "name"])
            let subtitle = col(["subtitle", "cardholder name", "username"])
            let number = col(["primary_value", "number", "card number", "ccnum", "kartennummer", "iban", "password"])
            let cvv = col(["cvv", "cvc", "verification number", "prüfziffer", "kartenprüfnummer", "security code"])
            let pin = col(["pin", "card pin", "karten-pin"])
            let expiry = col(["expiry date", "expiration date", "expiry_date", "ablaufdatum", "gültig bis"])
            let cardholder = col(["cardholder name", "cardholder", "karteninhaber"])
            let network = col(["brand", "network", "type_brand", "kartennetzwerk"])
            let website = col(["website_url", "website", "url"])
            let notes = col(["notes", "note"])

            var fields: [MacVaultField] = []
            if resolvedCat == "PAYMENT_CARD" && !cardholder.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_cardholder", value: cardholder, valueType: .text))
            }
            if !number.isEmpty {
                let key = resolvedCat == "BANK_ACCOUNT" ? "field_iban" : (resolvedCat == "PAYMENT_CARD" ? "field_card_number" : "field_document_number")
                fields.append(.init(id: UUID().uuidString, labelKey: key, value: number, valueType: .sensitiveSecret, isSensitive: true))
            }
            if !expiry.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_expiry_date", value: expiry, valueType: .dateIso))
            }
            if !cvv.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_cvv", value: cvv, valueType: .sensitiveSecret, isSensitive: true))
            }
            if !pin.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_pin", value: pin, valueType: .sensitiveSecret, isSensitive: true))
            }
            if resolvedCat == "PAYMENT_CARD" && !network.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_card_network", value: network, valueType: .text))
            }
            if !website.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_institution_domain", value: website, valueType: .url))
            }
            if !notes.isEmpty {
                fields.append(.init(id: UUID().uuidString, labelKey: "field_markdown_note", value: notes, valueType: .markdown))
            }
            imported.append(
                MacVaultEntry(
                    id: "imp-\(UUID().uuidString.prefix(10))",
                    category: resolvedCat,
                    title: title,
                    subtitle: subtitle,
                    fields: fields,
                    pinned: false,
                    favorite: false,
                    isSampleItem: false,
                    updatedAt: Date()
                )
            )
        }
        return imported
    }

    static func generateEmergencyKitHtml(secretKey: String, hint: String, versionDisplay: String) -> Data {
        let escapedKey = secretKey.replacingOccurrences(of: "<", with: "&lt;")
        let escapedHint = hint.replacingOccurrences(of: "<", with: "&lt;")
        let html = """
        <!DOCTYPE html>
        <html lang="en">
        <head><meta charset="utf-8"><title>SafePocket Emergency Kit</title>
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; padding: 40px; max-width: 720px; margin: auto; color: #0f172a; }
          .box { border: 2px solid #0d9488; border-radius: 14px; padding: 24px; margin-top: 20px; background: #f8fafc; }
          code { font-size: 19px; font-weight: 700; letter-spacing: 1px; color: #0f766e; }
        </style></head>
        <body>
          <h1>SafePocket Zero-Knowledge Emergency Kit</h1>
          <p>\(versionDisplay) • Generated \(ISO8601DateFormatter().string(from: Date()))</p>
          <div class="box">
            <h3>Secret Key (Required alongside your Master Password)</h3>
            <code>\(escapedKey)</code>
            <p style="margin-top:16px;"><strong>Master Password Hint:</strong> \(escapedHint.isEmpty ? "—" : escapedHint)</p>
          </div>
        </body></html>
        """
        return Data(html.utf8)
    }

    private static func csvEscape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    private static func parseCsvRow(_ line: String) -> [String] {
        var result: [String] = []
        var cur = ""
        var inQuotes = false
        for ch in line {
            if ch == "\"" {
                inQuotes.toggle()
            } else if ch == "," && !inQuotes {
                result.append(cur)
                cur = ""
            } else {
                cur.append(ch)
            }
        }
        result.append(cur)
        return result
    }
}
