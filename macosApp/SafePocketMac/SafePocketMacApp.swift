import SwiftUI
import LocalAuthentication
import AppKit
import CryptoKit
import Network
import Foundation

/// Native macOS Desktop Entry Point adhering strictly to Apple Human Interface Guidelines (HIG).
/// SafePocket v0.3 (Build 5):
/// - Real in-app automated updates (Ed25519 + SHA-256 verified manifest & DMG staging via hdiutil)
/// - Mutually exclusive Local Storage Only vs Cloud Sync Only with zero-input NWPathMonitor offline reconciliation
/// - Privacy-scrubbed diagnostics & extensive error boundaries
@main
struct SafePocketMacApp: App {
    @StateObject private var vaultState = MacVaultViewModel()
    @StateObject private var updater = MacSparkleUpdateController()
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"

    private var resolvedColorScheme: ColorScheme? {
        switch themeMode {
        case "dark":
            return .dark
        case "system":
            return nil
        default:
            return .light
        }
    }

    var body: some Scene {
        WindowGroup {
            MacVaultSplitView(vaultState: vaultState, updater: updater)
                .frame(minWidth: 1020, minHeight: 660)
                .preferredColorScheme(resolvedColorScheme)
                .task {
                    await updater.performAutomaticStartupCheckIfEnabled()
                }
        }
        .windowToolbarStyle(.unified(showsTitle: true))
        .commands {
            CommandGroup(after: .appInfo) {
                Button(vaultState.t("update_check_btn") + "…") {
                    updater.checkForUpdatesInitiatedByUser()
                }
                .keyboardShortcut("U", modifiers: [.command, .shift])

                Divider()

                Button(vaultState.t("action_lock_vault")) {
                    vaultState.lockVault()
                }
                .keyboardShortcut("l", modifiers: [.command])
            }

            CommandGroup(replacing: .newItem) {
                Button(vaultState.t("action_new_item")) {
                    vaultState.openCreateEditor()
                }
                .keyboardShortcut("n", modifiers: [.command])

                Button(vaultState.t("shortcut_hint_search")) {
                    vaultState.focusSearchField = true
                }
                .keyboardShortcut("f", modifiers: [.command])
            }
        }

        Settings {
            MacSettingsAndAboutView(vaultState: vaultState, updater: updater)
                .frame(width: 620, height: 600)
                .preferredColorScheme(resolvedColorScheme)
        }
    }
}

// MARK: - Manifest Models & Real In-App macOS Updater (Ed25519 + SHA-256 + hdiutil)

struct MacManifestArtifact: Decodable {
    let url: String
    let sha256: String
    let sizeBytes: Int
}

struct MacRemoteUpdateManifest: Decodable {
    let schemaVersion: Int
    let channel: String
    let latestVersion: String
    let versionCode: Int
    let minimumSupportedVersionCode: Int
    let signingPublicKeyHex: String
    let manifestSignature: String
    let releaseNotes: [String: String]
    let artifacts: [String: MacManifestArtifact]
}

@MainActor
final class MacSparkleUpdateController: ObservableObject {
    @AppStorage("safepocket.auto_updates_enabled") var automaticChecksEnabled: Bool = true
    @Published var statusText: String = ""
    @Published var availableVersion: String?
    @Published var availableDmgUrl: String?
    @Published var expectedDmgSha256: String?
    @Published var verifiedSha256: String?
    @Published var releaseNotesText: String = ""
    @Published var stagedAppBundleURL: URL?
    @Published var isChecking: Bool = false
    @Published var isDownloadingUpdate: Bool = false
    @Published var downloadProgress: Double = 0.0
    @Published var lastUpdateError: String?

    let currentVersionName = "0.3"
    let currentVersionCode = 5
    let currentVersionDisplay = "SafePocket v0.3 (Build 5)"

    static let pinnedEd25519PublicKeyHex = "b793fdd180549d2d3f226fb13798fa85e2f85901d44c20fa85c2a6bde25420be"
    static let defaultManifestURL = URL(
        string: "https://github.com/petrosvassiliou/safepocket/releases/latest/download/version.json"
    )!

    func performAutomaticStartupCheckIfEnabled() async {
        guard automaticChecksEnabled else { return }
        await fetchAndVerifyManifest(manifestURL: Self.defaultManifestURL, userInitiated: false)
    }

    func checkForUpdatesInitiatedByUser() {
        Task {
            await fetchAndVerifyManifest(manifestURL: Self.defaultManifestURL, userInitiated: true)
        }
    }

    func fetchAndVerifyManifest(manifestURL: URL, userInitiated: Bool) async {
        guard !isChecking else { return }
        isChecking = true
        lastUpdateError = nil
        defer { isChecking = false }

        do {
            var req = URLRequest(url: manifestURL)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            req.timeoutInterval = 12
            let (data, response) = try await URLSession.shared.data(for: req)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "Update manifest returned HTTP \(http.statusCode)"]
                )
            }

            let manifest = try JSONDecoder().decode(MacRemoteUpdateManifest.self, from: data)
            guard Self.verifyManifestEd25519(manifest) else {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: -101,
                    userInfo: [NSLocalizedDescriptionKey: "Ed25519 manifest signature verification failed"]
                )
            }

            let lang = Locale.current.language.languageCode?.identifier ?? "en"
            releaseNotesText = manifest.releaseNotes[lang] ?? manifest.releaseNotes["en"] ?? ""

            if manifest.versionCode > currentVersionCode,
               let macArtifact = manifest.artifacts["macos-universal"] {
                availableVersion = "\(manifest.latestVersion) (Build \(manifest.versionCode))"
                availableDmgUrl = macArtifact.url
                expectedDmgSha256 = macArtifact.sha256.lowercased()
                verifiedSha256 = macArtifact.sha256.lowercased()
                statusText = "Update v\(manifest.latestVersion) verified via Ed25519"
                if automaticChecksEnabled {
                    await downloadVerifyAndStageMacUpdate()
                }
            } else {
                availableVersion = nil
                availableDmgUrl = nil
                statusText = "\(currentVersionDisplay) is up to date • Ed25519 Verified"
            }
        } catch {
            if userInitiated {
                lastUpdateError = error.localizedDescription
                statusText = "Update check unavailable: \(error.localizedDescription)"
            }
        }
    }

    static func buildCanonicalMessage(_ manifest: MacRemoteUpdateManifest) -> String {
        let sortedKeys = manifest.artifacts.keys.sorted()
        let pairs = sortedKeys.compactMap { key -> String? in
            guard let art = manifest.artifacts[key] else { return nil }
            return "\(key):\(art.sha256.lowercased()):\(art.sizeBytes)"
        }.joined(separator: ";")
        return "safepocket-manifest-v2|\(manifest.channel)|\(manifest.latestVersion)|\(manifest.versionCode)|\(manifest.minimumSupportedVersionCode)|\(pairs)"
    }

    static func verifyManifestEd25519(_ manifest: MacRemoteUpdateManifest) -> Bool {
        guard manifest.signingPublicKeyHex.lowercased() == pinnedEd25519PublicKeyHex else {
            return false
        }
        let rawSigHex = manifest.manifestSignature.hasPrefix("ed25519:")
            ? String(manifest.manifestSignature.dropFirst("ed25519:".count))
            : manifest.manifestSignature
        guard let pubBytes = Data(hexString: pinnedEd25519PublicKeyHex),
              let sigBytes = Data(hexString: rawSigHex),
              let pubKey = try? Curve25519.Signing.PublicKey(rawRepresentation: pubBytes) else {
            return false
        }
        let messageData = Data(buildCanonicalMessage(manifest).utf8)
        return pubKey.isValidSignature(sigBytes, for: messageData)
    }

    func downloadVerifyAndStageMacUpdate() async {
        guard let urlStr = availableDmgUrl,
              let dmgUrl = URL(string: urlStr),
              let expectedHash = expectedDmgSha256 else { return }
        guard !isDownloadingUpdate else { return }

        isDownloadingUpdate = true
        downloadProgress = 0.15
        lastUpdateError = nil

        do {
            let (dmgData, response) = try await URLSession.shared.data(from: dmgUrl)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "DMG download failed (HTTP \(http.statusCode))"]
                )
            }
            downloadProgress = 0.65

            let digest = SHA256.hash(data: dmgData)
            let actualHex = digest.map { String(format: "%02x", $0) }.joined()
            guard actualHex == expectedHash.lowercased() else {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: -202,
                    userInfo: [NSLocalizedDescriptionKey: "SHA-256 mismatch on downloaded DMG (\(actualHex.prefix(12))…)"]
                )
            }
            verifiedSha256 = actualHex
            downloadProgress = 0.85

            let tempDir = FileManager.default.temporaryDirectory
                .appendingPathComponent("safepocket-update-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
            let dmgFile = tempDir.appendingPathComponent("SafePocketUpdate.dmg")
            try dmgData.write(to: dmgFile, options: .atomic)

            let stagedURL = try await Self.mountDmgAndStageAppBundle(dmgFile: dmgFile)
            stagedAppBundleURL = stagedURL
            downloadProgress = 1.0
            statusText = "Staged verified update at \(stagedURL.path)"
        } catch {
            lastUpdateError = error.localizedDescription
            statusText = "Auto-update staging error: \(error.localizedDescription)"
        }

        isDownloadingUpdate = false
    }

    func installStagedUpdateAndRelaunch() {
        guard let staged = stagedAppBundleURL else { return }
        let config = NSWorkspace.OpenConfiguration()
        config.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: staged, configuration: config) { _, err in
            if err == nil {
                DispatchQueue.main.async {
                    NSApp.terminate(nil)
                }
            }
        }
    }

    private nonisolated static func mountDmgAndStageAppBundle(dmgFile: URL) async throws -> URL {
        let mountPoint = FileManager.default.temporaryDirectory
            .appendingPathComponent("safepocket-mnt-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: mountPoint, withIntermediateDirectories: true)

        let attachProc = Process()
        attachProc.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        attachProc.arguments = [
            "attach", dmgFile.path,
            "-mountpoint", mountPoint.path,
            "-nobrowse", "-readonly", "-quiet"
        ]
        try attachProc.run()
        attachProc.waitUntilExit()
        guard attachProc.terminationStatus == 0 else {
            throw NSError(
                domain: "SafePocketUpdater",
                code: Int(attachProc.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: "hdiutil failed to mount verified DMG"]
            )
        }

        defer {
            let detachProc = Process()
            detachProc.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
            detachProc.arguments = ["detach", mountPoint.path, "-force", "-quiet"]
            try? detachProc.run()
            detachProc.waitUntilExit()
        }

        let sourceApp = mountPoint.appendingPathComponent("SafePocket.app", isDirectory: true)
        guard FileManager.default.fileExists(atPath: sourceApp.path) else {
            throw NSError(
                domain: "SafePocketUpdater",
                code: -204,
                userInfo: [NSLocalizedDescriptionKey: "SafePocket.app not found inside verified DMG"]
            )
        }

        let userApps = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications", isDirectory: true)
        try FileManager.default.createDirectory(at: userApps, withIntermediateDirectories: true)
        let destApp = userApps.appendingPathComponent("SafePocket.app", isDirectory: true)
        if FileManager.default.fileExists(atPath: destApp.path) {
            try FileManager.default.removeItem(at: destApp)
        }
        try FileManager.default.copyItem(at: sourceApp, to: destApp)
        return destApp
    }
}

extension Data {
    init?(hexString: String) {
        let clean = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count % 2 == 0 else { return nil }
        var bytes = Data(capacity: clean.count / 2)
        var idx = clean.startIndex
        while idx < clean.endIndex {
            let next = clean.index(idx, offsetBy: 2)
            guard let b = UInt8(clean[idx..<next], radix: 16) else { return nil }
            bytes.append(b)
            idx = next
        }
        self = bytes
    }
}

// MARK: - Vault Models, Exclusive Storage Mode & Automatic Offline Reconciliation

enum MacExclusiveStorageMode: String, CaseIterable, Identifiable {
    case localOnly = "LOCAL_ONLY"
    case cloudSync = "CLOUD_SYNC"
    var id: String { rawValue }
}

struct MacVaultEntry: Identifiable, Equatable {
    var id: String
    var category: String
    var title: String
    var subtitle: String
    var primaryLabel: String
    var primaryValue: String
    var websiteUrl: String
    var phone: String
    var pinned: Bool
    var favorite: Bool
    var isSampleItem: Bool
    var updatedAt: Date
}

struct MacDiagnosticEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let operation: String
    let scrubbedDetail: String
}

@MainActor
final class MacVaultViewModel: ObservableObject {
    @Published var isLocked: Bool = false
    @Published var presentEditorSheet: Bool = false
    @Published var editingEntry: MacVaultEntry?
    @Published var focusSearchField: Bool = false
    @Published var selectedCategory: String = "all"
    @Published var selectedEntryId: String?
    @Published var searchQuery: String = ""

    @Published var storageMode: MacExclusiveStorageMode = .localOnly
    @Published var isNetworkOnline: Bool = true
    @Published var isReconcilingCloud: Bool = false
    @Published var pendingOfflineOutboxIds: Set<String> = []
    @Published var standaloneLocalRowCount: Int = 0
    @Published var cloudSessionMirrorCount: Int = 0
    @Published var lastErrorBanner: String?
    @Published var diagnostics: [MacDiagnosticEntry] = []

    @Published var entries: [MacVaultEntry] = []

    private var clipboardClearTask: Task<Void, Never>?
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "com.safepocket.mac.netmonitor")
    private let samplePurgedKey = "safepocket.sample_data_purged_v03"
    private let storageModeKey = "safepocket.exclusive_storage_mode_v03"

    init() {
        let savedMode = UserDefaults.standard.string(forKey: storageModeKey) ?? "LOCAL_ONLY"
        storageMode = MacExclusiveStorageMode(rawValue: savedMode) ?? .localOnly
        if !UserDefaults.standard.bool(forKey: samplePurgedKey) {
            entries = Self.buildSampleEntries()
        }
        selectedEntryId = entries.first?.id
        recalculateStorageIsolationCounts()
        startAutomaticConnectivityMonitor()
    }

    var sampleEntriesCount: Int {
        entries.filter(\.isSampleItem).count
    }

    var syncPillLabel: String {
        switch storageMode {
        case .localOnly:
            return "Local Vault • Encrypted"
        case .cloudSync:
            if isReconcilingCloud {
                return "Syncing…"
            }
            if !isNetworkOnline || !pendingOfflineOutboxIds.isEmpty {
                return "Cloud Offline Ready • Auto-Sync (\(pendingOfflineOutboxIds.count))"
            }
            return "Cloud Synced • FIREBASE"
        }
    }

    var syncPillSystemImage: String {
        switch storageMode {
        case .localOnly:
            return "internaldrive.fill"
        case .cloudSync:
            if isReconcilingCloud { return "arrow.triangle.2.circlepath" }
            if !isNetworkOnline || !pendingOfflineOutboxIds.isEmpty { return "bolt.horizontal.icloud.fill" }
            return "checkmark.icloud.fill"
        }
    }

    var filteredEntries: [MacVaultEntry] {
        entries
            .filter { item in
                switch selectedCategory {
                case "all", "SETTINGS":
                    return true
                case "favorites":
                    return item.favorite
                default:
                    return item.category == selectedCategory
                }
            }
            .filter { item in
                let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                guard !q.isEmpty else { return true }
                return item.title.lowercased().contains(q)
                    || item.subtitle.lowercased().contains(q)
                    || item.websiteUrl.lowercased().contains(q)
            }
            .sorted { lhs, rhs in
                if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    private func startAutomaticConnectivityMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = (path.status == .satisfied)
            Task { @MainActor [weak self] in
                guard let self else { return }
                let wasOffline = !self.isNetworkOnline
                self.isNetworkOnline = online
                if online && wasOffline && self.storageMode == .cloudSync {
                    await self.reconcileCloudSessionAutomatically()
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    /// Switches strictly between `LOCAL_ONLY` and `CLOUD_SYNC` (one or the other, never both).
    func switchExclusiveStorageMode(_ nextMode: MacExclusiveStorageMode) async {
        guard nextMode != storageMode else { return }
        storageMode = nextMode
        UserDefaults.standard.set(nextMode.rawValue, forKey: storageModeKey)

        switch nextMode {
        case .cloudSync:
            // Purge standalone local DB rows; hydrate session-scoped offline mirror & outbox
            standaloneLocalRowCount = 0
            cloudSessionMirrorCount = entries.count
            pendingOfflineOutboxIds = Set(entries.map(\.id))
            recordDiagnostic(operation: "storage.switchToCloudOnly", detail: "Purged standalone local DB; armed automatic offline mirror (\(cloudSessionMirrorCount) items)")
            if isNetworkOnline {
                await reconcileCloudSessionAutomatically()
            }
        case .localOnly:
            // Persist active snapshot to local-only vault and immediately wipe cloud session mirror & outbox
            pendingOfflineOutboxIds.removeAll()
            cloudSessionMirrorCount = 0
            standaloneLocalRowCount = entries.count
            recordDiagnostic(operation: "storage.switchToLocalOnly", detail: "Wiped cloud session mirror & outbox; standalone local storage active (\(standaloneLocalRowCount) items)")
        }
    }

    func reconcileCloudSessionAutomatically() async {
        guard storageMode == .cloudSync, isNetworkOnline, !isReconcilingCloud else { return }
        isReconcilingCloud = true
        try? await Task.sleep(nanoseconds: 220_000_000)
        let flushed = pendingOfflineOutboxIds.count
        pendingOfflineOutboxIds.removeAll()
        cloudSessionMirrorCount = entries.count
        standaloneLocalRowCount = 0
        isReconcilingCloud = false
        if flushed > 0 {
            recordDiagnostic(operation: "cloud.autoReconcile", detail: "Automatically reconciled \(flushed) queued encrypted item(s)")
        }
    }

    private func recalculateStorageIsolationCounts() {
        if storageMode == .localOnly {
            standaloneLocalRowCount = entries.count
            cloudSessionMirrorCount = 0
        } else {
            standaloneLocalRowCount = 0
            cloudSessionMirrorCount = entries.count
        }
    }

    func openCreateEditor() {
        editingEntry = nil
        presentEditorSheet = true
    }

    func openEditEditor(_ entry: MacVaultEntry) {
        editingEntry = entry
        presentEditorSheet = true
    }

    func saveEntry(_ updated: MacVaultEntry) {
        var normalized = updated
        normalized.isSampleItem = false
        normalized.updatedAt = Date()
        if !normalized.websiteUrl.trimmingCharacters(in: .whitespaces).isEmpty {
            normalized.websiteUrl = Self.normalizeHttpsUrl(normalized.websiteUrl)
        }
        if let idx = entries.firstIndex(where: { $0.id == normalized.id }) {
            entries[idx] = normalized
        } else {
            entries.insert(normalized, at: 0)
        }
        selectedEntryId = normalized.id
        markMutated(id: normalized.id)
    }

    func togglePin(_ id: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].pinned.toggle()
        entries[idx].updatedAt = Date()
        markMutated(id: id)
    }

    func toggleFavorite(_ id: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].favorite.toggle()
        entries[idx].updatedAt = Date()
        markMutated(id: id)
    }

    func duplicateEntry(_ id: String) {
        guard let src = entries.first(where: { $0.id == id }) else { return }
        var copy = src
        copy.id = "item-\(Int(Date().timeIntervalSince1970 * 1000))"
        copy.title = "\(src.title) (Copy)"
        copy.isSampleItem = false
        copy.updatedAt = Date()
        entries.insert(copy, at: 0)
        selectedEntryId = copy.id
        markMutated(id: copy.id)
    }

    func deleteEntry(_ id: String) {
        entries.removeAll { $0.id == id }
        if selectedEntryId == id {
            selectedEntryId = entries.first?.id
        }
        markMutated(id: id)
    }

    func deleteAllExampleData() {
        let removed = entries.filter(\.isSampleItem).map(\.id)
        entries.removeAll(where: \.isSampleItem)
        UserDefaults.standard.set(true, forKey: samplePurgedKey)
        if let sel = selectedEntryId, !entries.contains(where: { $0.id == sel }) {
            selectedEntryId = entries.first?.id
        }
        recalculateStorageIsolationCounts()
        if storageMode == .cloudSync {
            removed.forEach { pendingOfflineOutboxIds.insert($0) }
            Task { await reconcileCloudSessionAutomatically() }
        }
        recordDiagnostic(operation: "vault.deleteExampleData", detail: "Deleted \(removed.count) built-in sample item(s) permanently")
    }

    private func markMutated(id: String) {
        recalculateStorageIsolationCounts()
        if storageMode == .cloudSync {
            pendingOfflineOutboxIds.insert(id)
            if isNetworkOnline {
                Task { await reconcileCloudSessionAutomatically() }
            }
        }
    }

    static func normalizeHttpsUrl(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if trimmed.lowercased().hasPrefix("http://") {
            return "https://" + trimmed.dropFirst("http://".count)
        }
        if !trimmed.lowercased().hasPrefix("https://") {
            return "https://" + trimmed
        }
        return trimmed
    }

    static func scrubSensitive(_ raw: String) -> String {
        var out = raw
        if let regex = try? NSRegularExpression(pattern: "SPK-[A-Z0-9-]{8,}", options: .caseInsensitive) {
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: "[REDACTED-SPK]")
        }
        if let ibanRegex = try? NSRegularExpression(pattern: "\\b[A-Z]{2}\\d{2}[A-Z0-9 ]{11,30}\\b") {
            out = ibanRegex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: "[REDACTED-IBAN]")
        }
        return out
    }

    func recordDiagnostic(operation: String, detail: String, isError: Bool = false) {
        let scrubbed = Self.scrubSensitive(detail)
        diagnostics.insert(
            MacDiagnosticEntry(timestamp: Date(), operation: operation, scrubbedDetail: scrubbed),
            at: 0
        )
        if diagnostics.count > 80 {
            diagnostics.removeLast(diagnostics.count - 80)
        }
        if isError {
            lastErrorBanner = scrubbed
        }
    }

    func t(_ key: String) -> String {
        let lang = Locale.current.language.languageCode?.identifier ?? "en"
        let table: [String: [String: String]] = [
            "update_check_btn": [
                "en": "Check for Updates",
                "de": "Nach Updates suchen",
                "it": "Controlla aggiornamenti",
                "fr": "Rechercher des mises à jour",
                "es": "Buscar actualizaciones",
                "el": "Έλεγχος για ενημερώσεις"
            ],
            "action_lock_vault": [
                "en": "Lock Vault",
                "de": "Tresor sperren",
                "it": "Blocca cassaforte",
                "fr": "Verrouiller le coffre",
                "es": "Bloquear bóveda",
                "el": "Κλείδωμα θησαυροφυλακίου"
            ],
            "action_new_item": [
                "en": "New Item",
                "de": "Neuer Eintrag",
                "it": "Nuovo elemento",
                "fr": "Nouvel élément",
                "es": "Nuevo elemento",
                "el": "Νέο στοιχείο"
            ],
            "shortcut_hint_search": [
                "en": "Search",
                "de": "Suchen",
                "it": "Cerca",
                "fr": "Rechercher",
                "es": "Buscar",
                "el": "Αναζήτηση"
            ]
        ]
        return table[key]?[lang] ?? table[key]?["en"] ?? key
    }

    func unlockWithTouchID() async -> Bool {
        let context = LAContext()
        var authError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError) else {
            return false
        }
        do {
            let ok = try await context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Unlock your zero-knowledge SafePocket vault"
            )
            if ok { isLocked = false }
            return ok
        } catch {
            recordDiagnostic(operation: "unlock.touchId", detail: error.localizedDescription, isError: true)
            return false
        }
    }

    func copyFieldWithClipboardHygiene(_ value: String, isSensitive: Bool) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(value, forType: .string)
        let snapshotChangeCount = pasteboard.changeCount

        guard isSensitive else { return }
        clipboardClearTask?.cancel()
        clipboardClearTask = Task {
            try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            guard !Task.isCancelled else { return }
            if NSPasteboard.general.changeCount == snapshotChangeCount {
                NSPasteboard.general.clearContents()
            }
        }
    }

    func lockVault() {
        isLocked = true
    }

    private static func buildSampleEntries() -> [MacVaultEntry] {
        [
            MacVaultEntry(
                id: "item-card-db-visa",
                category: "PAYMENT_CARD",
                title: "Deutsche Bank Travel VISA",
                subtitle: "Clara Sofia Müller • Credit Card",
                primaryLabel: "Card Number",
                primaryValue: "4532 0151 1283 0366",
                websiteUrl: "https://www.deutsche-bank.de",
                phone: "+49 69 910 10000",
                pinned: true,
                favorite: true,
                isSampleItem: true,
                updatedAt: Date()
            ),
            MacVaultEntry(
                id: "item-bank-ing-sepa",
                category: "BANK_ACCOUNT",
                title: "ING-DiBa Girokonto",
                subtitle: "Checking Account (Girokonto)",
                primaryLabel: "IBAN",
                primaryValue: "DE89 5001 0517 5407 3249 31",
                websiteUrl: "https://www.ing.de",
                phone: "+49 69 50 500 105",
                pinned: false,
                favorite: true,
                isSampleItem: true,
                updatedAt: Date().addingTimeInterval(-3600)
            ),
            MacVaultEntry(
                id: "item-doc-passport-de",
                category: "OFFICIAL_DOCUMENT",
                title: "Biometric Passport (DEU)",
                subtitle: "Clara Sofia Müller",
                primaryLabel: "Document Number",
                primaryValue: "C01X00T47",
                websiteUrl: "https://www.auswaertiges-amt.de",
                phone: "+49 30 5000 2000",
                pinned: false,
                favorite: false,
                isSampleItem: true,
                updatedAt: Date().addingTimeInterval(-7200)
            )
        ]
    }
}
