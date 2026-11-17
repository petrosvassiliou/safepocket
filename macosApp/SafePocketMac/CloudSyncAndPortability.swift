import Foundation
import CryptoKit
import Security

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

enum MacKeychainStore {
    private static let service = "com.safepocket.mac.zkvault"

    static func saveData(_ data: Data, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
        var attrs = query
        attrs[kSecValueData as String] = data
        SecItemAdd(attrs as CFDictionary, nil)
    }

    static func loadData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
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

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
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
}

enum MacCloudSyncClient {
    static func authenticateFirebase(
        action: String, // "signUp" or "signInWithPassword"
        email: String,
        syncPassword: String,
        apiKey: String
    ) async throws -> MacCloudUserSession {
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let zkPassword = MacZeroKnowledgeCrypto.deriveZeroKnowledgeCloudAuthPassword(
            email: cleanEmail,
            syncPassword: syncPassword
        )
        guard var components = URLComponents(string: "https://identitytoolkit.googleapis.com/v1/accounts:\(action)") else {
            throw URLError(.badURL)
        }
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
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
            let errMsg = ((jsonObj["error"] as? [String: Any])?["message"] as? String)
                ?? "Firebase Auth \(action) failed (HTTP \(http.statusCode))"
            throw NSError(domain: "SafePocketCloudAuth", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: errMsg])
        }
        guard let uid = jsonObj["localId"] as? String,
              let resolvedEmail = jsonObj["email"] as? String,
              let idToken = jsonObj["idToken"] as? String else {
            throw NSError(domain: "SafePocketCloudAuth", code: -2, userInfo: [NSLocalizedDescriptionKey: "Incomplete Firebase Auth response"])
        }
        return MacCloudUserSession(
            uid: uid,
            email: resolvedEmail,
            idToken: idToken,
            refreshToken: jsonObj["refreshToken"] as? String
        )
    }

    static func refreshFirebaseTokenIfPossible(
        session: MacCloudUserSession,
        apiKey: String
    ) async -> MacCloudUserSession {
        guard let refresh = session.refreshToken, !refresh.isEmpty,
              let url = URL(string: "https://securetoken.googleapis.com/v1/token?key=\(apiKey)") else {
            return session
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data("grant_type=refresh_token&refresh_token=\(refresh)".utf8)
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let nextToken = obj["id_token"] as? String else {
            return session
        }
        var updated = session
        updated.idToken = nextToken
        if let nextRefresh = obj["refresh_token"] as? String {
            updated.refreshToken = nextRefresh
        }
        return updated
    }

    static func pushDeltasToFirestore(
        deltas: [MacEncryptedSyncDelta],
        session: MacCloudUserSession,
        config: MacCloudEndpointConfig
    ) async throws -> Int {
        guard !deltas.isEmpty else { return 0 }
        let urlStr = "https://firestore.googleapis.com/v1/projects/\(config.firebaseProjectId)/databases/(default)/documents:commit"
        guard let url = URL(string: urlStr) else { throw URLError(.badURL) }

        let writes: [[String: Any]] = deltas.map { d in
            let docName = "projects/\(config.firebaseProjectId)/databases/(default)/documents/users/\(session.uid)/vaults/\(config.vaultCollectionId)/deltas/\(d.entityId)"
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
        req.setValue("Bearer \(session.idToken)", forHTTPHeaderField: "Authorization")
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
        let urlStr = "https://firestore.googleapis.com/v1/projects/\(config.firebaseProjectId)/databases/(default)/documents/users/\(session.uid)/vaults/\(config.vaultCollectionId):runQuery"
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
        req.setValue("Bearer \(session.idToken)", forHTTPHeaderField: "Authorization")
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
            let number = col(["primary_value", "number", "iban", "password"])
            let website = col(["website_url", "website", "url"])
            let notes = col(["notes", "note"])

            var fields: [MacVaultField] = []
            if !number.isEmpty {
                let key = resolvedCat == "BANK_ACCOUNT" ? "field_iban" : (resolvedCat == "PAYMENT_CARD" ? "field_card_number" : "field_document_number")
                fields.append(.init(id: UUID().uuidString, labelKey: key, value: number, valueType: .sensitiveSecret, isSensitive: true))
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
