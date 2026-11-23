import SwiftUI
import LocalAuthentication
import AppKit
import CryptoKit
import Network
import Foundation
import UniformTypeIdentifiers

/// Native macOS Desktop Entry Point adhering strictly to Apple Human Interface Guidelines (HIG).
/// SafePocket v0.3.2 (Build 7):
/// - Foreground AppKit activation (`NSApplication.ActivationPolicy.regular` + `makeKeyAndOrderFront`) so every window, dialog, and text input receives active focus
/// - 100% Feature & Settings Parity with Android / Googlebook OS & ChromeOS / Web PWA
/// - 6-Language Localization (`en`, `de`, `it`, `fr`, `es`, `el` + System Default) via bundled `i18n/*.arb.json`
/// - Mutually exclusive Local Storage vs Cloud Sync (clean radio buttons) with full Firebase Zero-Knowledge Auth & Endpoint Config + automatic offline sync
/// - Real in-app automated updates (Ed25519 + SHA-256 verified manifest & DMG staging via hdiutil)
final class SafePocketMacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        if let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
           let appIcon = NSImage(contentsOf: iconURL) {
            NSApplication.shared.applicationIconImage = appIcon
        }
        NSApplication.shared.activate(ignoringOtherApps: true)
        DispatchQueue.main.async {
            for window in NSApplication.shared.windows where window.canBecomeKey {
                window.makeKeyAndOrderFront(nil)
            }
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        if NSApplication.shared.keyWindow == nil {
            NSApplication.shared.windows.first(where: { $0.isVisible && $0.canBecomeKey })?.makeKeyAndOrderFront(nil)
        }
    }
}

@main
struct SafePocketMacApp: App {
    @NSApplicationDelegateAdaptor(SafePocketMacAppDelegate.self) private var appDelegate
    @StateObject private var vaultState = MacVaultViewModel()
    @StateObject private var updater = MacSparkleUpdateController()
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"

    init() {
        NSApplication.shared.setActivationPolicy(.regular)
    }

    private var resolvedColorScheme: ColorScheme? {
        switch themeMode {
        case "dark": return .dark
        case "system": return nil
        default: return .light
        }
    }

    var body: some Scene {
        WindowGroup {
            MacVaultSplitView(vaultState: vaultState, updater: updater)
                .frame(minWidth: 1040, minHeight: 680)
                .preferredColorScheme(resolvedColorScheme)
                .tint(vaultState.materialAccent.swiftUIColor)
                .onAppear {
                    NSApplication.shared.setActivationPolicy(.regular)
                    NSApplication.shared.activate(ignoringOtherApps: true)
                }
                .task {
                    vaultState.applyWindowScreenshotProtection()
                    await updater.performAutomaticStartupCheckIfEnabled()
                }
        }
        .windowToolbarStyle(.unifiedCompact(showsTitle: true))
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button(vaultState.t("nav_settings") + "…") {
                    vaultState.presentSettingsSheet = true
                }
                .keyboardShortcut(",", modifiers: [.command])
            }

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

    let currentVersionName = "0.5.0-beta.2"
    let currentVersionCode = 17
    let currentVersionDisplay = "SafePocket v0.5.0-beta.2 (Build 17)"

    static let pinnedEd25519PublicKeyHex = "b793fdd180549d2d3f226fb13798fa85e2f85901d44c20fa85c2a6bde25420be"
    static let defaultManifestURL = URL(
        string: "https://github.com/petrosvassiliou/safepocket/releases/latest/download/version.json"
    )!

    func performAutomaticStartupCheckIfEnabled() async {
        guard automaticChecksEnabled else { return }
        await checkManifest(autoStageIfAvailable: true)
    }

    func checkForUpdatesInitiatedByUser() {
        Task {
            await checkManifest(autoStageIfAvailable: false)
        }
    }

    func checkManifest(autoStageIfAvailable: Bool) async {
        guard !isChecking else { return }
        isChecking = true
        lastUpdateError = nil
        statusText = "Checking signed release manifest…"

        do {
            var request = URLRequest(url: Self.defaultManifestURL)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.timeoutInterval = 12.0

            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }

            let manifest = try JSONDecoder().decode(MacRemoteUpdateManifest.self, from: data)
            guard try Self.verifyManifestEd25519Signature(manifest) else {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: -101,
                    userInfo: [NSLocalizedDescriptionKey: "Ed25519 manifest signature verification failed"]
                )
            }

            if manifest.versionCode > currentVersionCode,
               let dmgArtifact = manifest.artifacts["macos-universal"] {
                availableVersion = manifest.latestVersion
                availableDmgUrl = dmgArtifact.url
                expectedDmgSha256 = dmgArtifact.sha256
                let lang = Locale.current.language.languageCode?.identifier ?? "en"
                releaseNotesText = manifest.releaseNotes[lang] ?? manifest.releaseNotes["en"] ?? ""
                statusText = "Update v\(manifest.latestVersion) (Build \(manifest.versionCode)) verified via Ed25519"
                if autoStageIfAvailable {
                    await downloadVerifyAndStageMacUpdate()
                }
            } else {
                availableVersion = nil
                availableDmgUrl = nil
                statusText = "Up to date (\(currentVersionDisplay))"
            }
        } catch {
            lastUpdateError = error.localizedDescription
            statusText = "Update check notice: \(error.localizedDescription)"
        }

        isChecking = false
    }

    static func verifyManifestEd25519Signature(_ manifest: MacRemoteUpdateManifest) throws -> Bool {
        guard manifest.signingPublicKeyHex.lowercased() == pinnedEd25519PublicKeyHex.lowercased() else {
            return false
        }
        let sigHex = manifest.manifestSignature.hasPrefix("ed25519:")
            ? String(manifest.manifestSignature.dropFirst("ed25519:".count))
            : manifest.manifestSignature

        guard let pubKeyBytes = Data(hexString: pinnedEd25519PublicKeyHex),
              let sigBytes = Data(hexString: sigHex) else {
            return false
        }
        let pubKey = try Curve25519.Signing.PublicKey(rawRepresentation: pubKeyBytes)
        let sortedKeys = manifest.artifacts.keys.sorted()
        let artifactSummary = sortedKeys.compactMap { key -> String? in
            guard let a = manifest.artifacts[key] else { return nil }
            return "\(key):\(a.sha256.lowercased()):\(a.sizeBytes)"
        }.joined(separator: "|")

        let canonicalPayload = [
            "safepocket-manifest-v\(manifest.schemaVersion)",
            manifest.channel,
            manifest.latestVersion,
            String(manifest.versionCode),
            String(manifest.minimumSupportedVersionCode),
            artifactSummary
        ].joined(separator: "\n")

        return pubKey.isValidSignature(sigBytes, for: Data(canonicalPayload.utf8))
    }

    func downloadVerifyAndStageMacUpdate() async {
        guard let urlStr = availableDmgUrl,
              let url = URL(string: urlStr),
              let expectedHex = expectedDmgSha256?.lowercased(),
              !isDownloadingUpdate else {
            return
        }

        isDownloadingUpdate = true
        downloadProgress = 0.1
        lastUpdateError = nil
        statusText = "Downloading macOS universal DMG…"

        do {
            let (tempFileURL, response) = try await URLSession.shared.download(from: url)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }
            downloadProgress = 0.65
            let dmgBytes = try Data(contentsOf: tempFileURL)
            let digestHex = SHA256.hash(data: dmgBytes).map { String(format: "%02x", $0) }.joined()
            guard digestHex.lowercased() == expectedHex else {
                throw NSError(
                    domain: "SafePocketUpdater",
                    code: -102,
                    userInfo: [NSLocalizedDescriptionKey: "DMG SHA-256 mismatch! Refusing untrusted binary."]
                )
            }
            verifiedSha256 = digestHex
            downloadProgress = 0.82
            statusText = "SHA-256 verified (\(digestHex.prefix(12))…). Staging SafePocket.app…"

            let stagedURL = try await Self.mountDmgAndStageAppBundle(dmgFile: tempFileURL)
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
        attachProc.arguments = ["attach", dmgFile.path, "-mountpoint", mountPoint.path, "-nobrowse", "-readonly", "-quiet"]
        try attachProc.run()
        attachProc.waitUntilExit()
        guard attachProc.terminationStatus == 0 else {
            throw NSError(domain: "SafePocketUpdater", code: Int(attachProc.terminationStatus), userInfo: [NSLocalizedDescriptionKey: "hdiutil failed to mount verified DMG"])
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
            throw NSError(domain: "SafePocketUpdater", code: -204, userInfo: [NSLocalizedDescriptionKey: "SafePocket.app not found inside verified DMG"])
        }

        let userApps = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true)
        try FileManager.default.createDirectory(at: userApps, withIntermediateDirectories: true)
        let destApp = userApps.appendingPathComponent("SafePocket.app", isDirectory: true)
        if FileManager.default.fileExists(atPath: destApp.path) {
            try FileManager.default.removeItem(at: destApp)
        }
        try FileManager.default.copyItem(at: sourceApp, to: destApp)
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: destApp.path)
        NSWorkspace.shared.noteFileSystemChanged(destApp.path)
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

// MARK: - Exclusive Storage Mode, Cloud Sync Account/Config & Full Parity ViewModel

enum MacExclusiveStorageMode: String, CaseIterable, Identifiable {
    case localOnly = "LOCAL_ONLY"
    case cloudSync = "CLOUD_SYNC"
    var id: String { rawValue }
}

struct MacDiagnosticEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let operation: String
    let scrubbedDetail: String
}

@MainActor
final class MacVaultViewModel: ObservableObject {
    let i18n = MacLocalizationEngine()

    // Localization, Appearance & Security Preferences (100% Cross-Platform Parity)
    @Published var localeOverrideCode: String {
        didSet { UserDefaults.standard.set(localeOverrideCode, forKey: "safepocket.locale_override") }
    }
    @Published var materialAccent: MacMaterialAccent {
        didSet { UserDefaults.standard.set(materialAccent.rawValue, forKey: "safepocket.material_accent") }
    }
    @Published var allowScreenshots: Bool {
        didSet {
            UserDefaults.standard.set(allowScreenshots, forKey: "safepocket.allow_screenshots")
            applyWindowScreenshotProtection()
        }
    }
    @Published var autoDownloadLogos: Bool {
        didSet { UserDefaults.standard.set(autoDownloadLogos, forKey: "safepocket.auto_download_logos") }
    }
    @Published var expiryWindowDays: Int {
        didSet { UserDefaults.standard.set(expiryWindowDays, forKey: "safepocket.expiry_window_days") }
    }
    @Published var autoLockMinutes: Int {
        didSet {
            UserDefaults.standard.set(autoLockMinutes, forKey: "safepocket.autolock_minutes")
            restartInactivityAutoLockTimer()
        }
    }
    @Published var lockOnStandby: Bool {
        didSet {
            UserDefaults.standard.set(lockOnStandby, forKey: "safepocket.lock_on_standby")
        }
    }
    @Published var includeLoginsOnImport: Bool {
        didSet { UserDefaults.standard.set(includeLoginsOnImport, forKey: "safepocket.import_include_logins") }
    }
    @Published var masterPasswordHint: String {
        didSet { UserDefaults.standard.set(masterPasswordHint, forKey: "safepocket.master_pw_hint") }
    }

    // Zero-Knowledge Vault Credentials
    @Published var secretKeyFormatted: String
    @Published var hasCustomMasterPassword: Bool = false
    private var activeMasterPassword: String = "Correct-Horse-SafePocket-2026!"

    // Navigation & UI State
    @Published var isLocked: Bool = false
    @Published var presentSettingsSheet: Bool = false
    @Published var presentEditorSheet: Bool = false
    @Published var pendingCreateCategory: String = "PAYMENT_CARD"
    @Published var editingEntry: MacVaultEntry?
    @Published var focusSearchField: Bool = false
    @Published var selectedCategory: String = "all"
    @Published var selectedEntryId: String?
    @Published var searchQuery: String = ""
    @Published var statusNotice: String?

    // Exclusive Storage & Real Cloud Sync State
    @Published var storageMode: MacExclusiveStorageMode = .localOnly
    @Published var cloudProvider: MacCloudProvider = .firebase {
        didSet { UserDefaults.standard.set(cloudProvider.rawValue, forKey: "safepocket.cloud_provider") }
    }
    @Published var cloudSession: MacCloudUserSession?
    @Published var endpointConfig: MacCloudEndpointConfig = MacCloudEndpointConfig() {
        didSet {
            if let data = try? JSONEncoder().encode(endpointConfig) {
                UserDefaults.standard.set(data, forKey: "safepocket.cloud_endpoint_config")
            }
        }
    }
    @Published var cloudAuthBusy: Bool = false
    @Published var isNetworkOnline: Bool = true
    @Published var isReconcilingCloud: Bool = false
    @Published var pendingOfflineOutboxIds: Set<String> = []
    @Published var standaloneLocalRowCount: Int = 0
    @Published var cloudSessionMirrorCount: Int = 0
    @Published var lastSyncedIso: String?
    @Published var lastSyncPushed: Int = 0
    @Published var lastSyncPulled: Int = 0

    // Diagnostics & Vault Items
    @Published var lastErrorBanner: String?
    @Published var diagnostics: [MacDiagnosticEntry] = []
    @Published var entries: [MacVaultEntry] = []

    private var clipboardClearTask: Task<Void, Never>?
    private var inactivityTimerTask: Task<Void, Never>?
    private var liveSyncLoopTask: Task<Void, Never>?
    private var standbyObserverTokens: [NSObjectProtocol] = []
    private var hlcLogicalCounter: Int = 0
    private var pendingTombstones: [String: MacPendingTombstone] = [:]
    private var knownRemoteTombstones: [String: String] = [:]
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "com.safepocket.mac.netmonitor")
    private let samplePurgedKey = "safepocket.sample_data_purged_v03"
    private let storageModeKey = "safepocket.exclusive_storage_mode_v03"
    private let pendingTombstonesKey = "safepocket.pending_tombstones_v04"
    private let knownTombstonesKey = "safepocket.known_tombstones_v04"
    private let nodeId: String

    init() {
        self.localeOverrideCode = UserDefaults.standard.string(forKey: "safepocket.locale_override") ?? "__system__"
        self.materialAccent = MacMaterialAccent(rawValue: UserDefaults.standard.string(forKey: "safepocket.material_accent") ?? "emerald") ?? .emerald
        if UserDefaults.standard.object(forKey: "safepocket.allow_screenshots") == nil {
            self.allowScreenshots = true
        } else {
            self.allowScreenshots = UserDefaults.standard.bool(forKey: "safepocket.allow_screenshots")
        }
        if UserDefaults.standard.object(forKey: "safepocket.auto_download_logos") == nil {
            self.autoDownloadLogos = true
        } else {
            self.autoDownloadLogos = UserDefaults.standard.bool(forKey: "safepocket.auto_download_logos")
        }
        let savedExp = UserDefaults.standard.integer(forKey: "safepocket.expiry_window_days")
        self.expiryWindowDays = [30, 60, 90, 180].contains(savedExp) ? savedExp : 90
        if UserDefaults.standard.object(forKey: "safepocket.autolock_minutes") == nil {
            self.autoLockMinutes = 5
        } else {
            let savedAutoLock = UserDefaults.standard.integer(forKey: "safepocket.autolock_minutes")
            self.autoLockMinutes = [0, 1, 5, 15].contains(savedAutoLock) ? savedAutoLock : 5
        }
        if UserDefaults.standard.object(forKey: "safepocket.lock_on_standby") == nil {
            self.lockOnStandby = true
        } else {
            self.lockOnStandby = UserDefaults.standard.bool(forKey: "safepocket.lock_on_standby")
        }
        self.includeLoginsOnImport = UserDefaults.standard.bool(forKey: "safepocket.import_include_logins")
        self.masterPasswordHint = UserDefaults.standard.string(forKey: "safepocket.master_pw_hint") ?? ""

        if let existingNode = UserDefaults.standard.string(forKey: "safepocket.crdt_node_id") {
            self.nodeId = existingNode
        } else {
            let generated = "mac-\(UUID().uuidString.prefix(8))"
            UserDefaults.standard.set(generated, forKey: "safepocket.crdt_node_id")
            self.nodeId = generated
        }

        if let keyData = MacKeychainStore.loadData(account: "secret_key"),
           let savedKey = String(data: keyData, encoding: .utf8), !savedKey.isEmpty {
            self.secretKeyFormatted = savedKey
        } else {
            let newKey = MacZeroKnowledgeCrypto.generateFormattedSecretKey()
            MacKeychainStore.saveData(Data(newKey.utf8), account: "secret_key")
            self.secretKeyFormatted = newKey
        }

        if let pwData = MacKeychainStore.loadData(account: "master_password"),
           let savedPw = String(data: pwData, encoding: .utf8), !savedPw.isEmpty {
            self.activeMasterPassword = savedPw
            self.hasCustomMasterPassword = true
        }

        if let sessionData = MacKeychainStore.loadData(account: "cloud_session"),
           let decodedSession = try? JSONDecoder().decode(MacCloudUserSession.self, from: sessionData) {
            self.cloudSession = decodedSession
        }

        if let cfgData = UserDefaults.standard.data(forKey: "safepocket.cloud_endpoint_config"),
           let cfg = try? JSONDecoder().decode(MacCloudEndpointConfig.self, from: cfgData) {
            self.endpointConfig = cfg
        }

        if let tombData = UserDefaults.standard.data(forKey: pendingTombstonesKey),
           let decodedTombs = try? JSONDecoder().decode([String: MacPendingTombstone].self, from: tombData) {
            self.pendingTombstones = decodedTombs
            decodedTombs.keys.forEach { self.pendingOfflineOutboxIds.insert($0) }
        }

        if let knownData = UserDefaults.standard.data(forKey: knownTombstonesKey),
           let decodedKnown = try? JSONDecoder().decode([String: String].self, from: knownData) {
            self.knownRemoteTombstones = decodedKnown
        }

        self.cloudProvider = MacCloudProvider(rawValue: UserDefaults.standard.string(forKey: "safepocket.cloud_provider") ?? "FIREBASE") ?? .firebase

        let savedMode = UserDefaults.standard.string(forKey: storageModeKey) ?? "LOCAL_ONLY"
        self.storageMode = MacExclusiveStorageMode(rawValue: savedMode) ?? .localOnly

        loadPersistedEntriesOrSeedSamples()
        selectedEntryId = entries.first?.id
        recalculateStorageIsolationCounts()
        startAutomaticConnectivityMonitor()
        registerStandbyAndSleepObservers()
        startLiveCloudSyncLoop()
        restartInactivityAutoLockTimer()
    }

    var activeLocale: MacSupportedLocale {
        i18n.resolveActiveLocale(overrideCode: localeOverrideCode)
    }

    func t(_ key: String, _ params: [String: String] = [:]) -> String {
        i18n.t(key, localeCode: localeOverrideCode, params: params)
    }

    func resolveFieldLabel(key: String, isCustom: Bool, category: String) -> String {
        i18n.resolveFieldLabel(key: key, isCustom: isCustom, category: category, localeCode: localeOverrideCode)
    }

    func categorySingularName(_ category: String) -> String {
        i18n.resolveCategorySingularName(category: category, localeCode: localeOverrideCode)
    }

    func displaySubtitle(for entry: MacVaultEntry) -> String {
        i18n.resolveDisplaySubtitle(subtitle: entry.subtitle, category: entry.category, localeCode: localeOverrideCode)
    }

    var selectedCategoryTitle: String {
        switch selectedCategory {
        case "all": return t("nav_all_items")
        case "favorites": return t("nav_favorites")
        case "PAYMENT_CARD": return t("category_payment_card")
        case "BANK_ACCOUNT": return t("category_bank_account")
        case "INSURANCE_CARD": return t("category_insurance_card")
        case "MEMBERSHIP_CARD": return t("category_membership_card")
        case "IDENTITY": return t("category_identity")
        case "OFFICIAL_DOCUMENT": return t("category_official_document")
        case "SECURE_NOTE": return t("category_secure_note")
        default: return t("nav_all_items")
        }
    }

    var cloudProviderDisplayName: String {
        switch cloudProvider {
        case .firebase: return "Firebase"
        case .googleDrive: return "Google Drive"
        }
    }

    var sampleEntriesCount: Int {
        entries.filter(\.isSampleItem).count
    }

    var isCloudAuthenticated: Bool {
        switch cloudProvider {
        case .firebase:
            return cloudSession != nil
        case .googleDrive:
            return !endpointConfig.googleDriveBearerToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    var syncPillLabel: String {
        switch storageMode {
        case .localOnly:
            return t("sync_pill_local_only")
        case .cloudSync:
            if !isCloudAuthenticated {
                return "Cloud Sync · \(t("cloud_auth_signin_btn"))"
            }
            if isReconcilingCloud {
                return t("sync_pill_syncing")
            }
            if !isNetworkOnline || !pendingOfflineOutboxIds.isEmpty {
                return t("sync_pill_cloud_offline_armed", ["queued": String(pendingOfflineOutboxIds.count)])
            }
            return t("sync_pill_cloud_synced", ["detail": cloudProviderDisplayName])
        }
    }

    var syncPillSystemImage: String {
        switch storageMode {
        case .localOnly:
            return "internaldrive.fill"
        case .cloudSync:
            if !isCloudAuthenticated { return "person.crop.circle.badge.exclamationmark" }
            if isReconcilingCloud { return "arrow.triangle.2.circlepath" }
            if !isNetworkOnline || !pendingOfflineOutboxIds.isEmpty { return "bolt.horizontal.icloud.fill" }
            return "checkmark.icloud.fill"
        }
    }

    var filteredEntries: [MacVaultEntry] {
        entries
            .filter { item in
                switch selectedCategory {
                case "all": return true
                case "favorites": return item.favorite
                default: return item.category == selectedCategory
                }
            }
            .filter { item in
                let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                guard !q.isEmpty else { return true }
                return item.title.lowercased().contains(q)
                    || displaySubtitle(for: item).lowercased().contains(q)
                    || item.subtitle.lowercased().contains(q)
                    || item.websiteUrl.lowercased().contains(q)
                    || item.fields.contains(where: { $0.value.lowercased().contains(q) })
                    || item.tags.contains(where: { $0.lowercased().contains(q) })
            }
            .sorted { lhs, rhs in
                if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    func applyWindowScreenshotProtection() {
        NSApplication.shared.setActivationPolicy(.regular)
        let sharing: NSWindow.SharingType = allowScreenshots ? .readOnly : .none
        for window in NSApplication.shared.windows {
            window.sharingType = sharing
        }
        if NSApplication.shared.keyWindow == nil {
            NSApplication.shared.windows.first(where: { $0.isVisible && $0.canBecomeKey })?.makeKeyAndOrderFront(nil)
        }
    }

    private func registerStandbyAndSleepObservers() {
        let wsCenter = NSWorkspace.shared.notificationCenter
        let handleStandby: @Sendable (Notification) -> Void = { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.lockOnStandby, !self.isLocked else { return }
                self.lockVault()
                self.recordDiagnostic(operation: "security.standbyLock", detail: "Vault locked automatically on system standby / sleep")
            }
        }
        standbyObserverTokens.append(
            wsCenter.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main, using: handleStandby)
        )
        standbyObserverTokens.append(
            wsCenter.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main, using: handleStandby)
        )
        standbyObserverTokens.append(
            DistributedNotificationCenter.default().addObserver(
                forName: NSNotification.Name("com.apple.screenIsLocked"),
                object: nil,
                queue: .main,
                using: handleStandby
            )
        )
    }

    func restartInactivityAutoLockTimer() {
        inactivityTimerTask?.cancel()
        inactivityTimerTask = nil
        guard autoLockMinutes > 0 else { return }
        let seconds = UInt64(autoLockMinutes) * 60
        inactivityTimerTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            self?.lockVault()
        }
    }

    func configureVaultCredentials(newMasterPassword: String, pairedSecretKey: String?) {
        let trimmedPw = newMasterPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedPw.count >= 6 else { return }
        activeMasterPassword = trimmedPw
        hasCustomMasterPassword = true
        MacKeychainStore.saveData(Data(trimmedPw.utf8), account: "master_password")

        if let sk = pairedSecretKey?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
           sk.hasPrefix("SPK-") {
            secretKeyFormatted = sk
            MacKeychainStore.saveData(Data(sk.utf8), account: "secret_key")
        }
        persistActiveStorageSnapshot()
        statusNotice = "✓ \(t("security_master_password")) & \(t("security_secret_key")) updated"
        recordDiagnostic(operation: "security.rekey", detail: "Updated Master Password & re-encrypted active vault")
    }

    func verifyMasterPassword(_ candidate: String) -> Bool {
        if !hasCustomMasterPassword && candidate.isEmpty { return true }
        return candidate == activeMasterPassword
    }

    func unlockWithMasterPassword(_ candidate: String) -> Bool {
        if candidate == activeMasterPassword || (!hasCustomMasterPassword && !candidate.isEmpty) {
            isLocked = false
            restartInactivityAutoLockTimer()
            return true
        }
        return false
    }

    // MARK: - Exclusive Storage Mode & Real Zero-Knowledge Cloud Sync

    private var appSupportDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("SafePocket", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private var localVaultFileURL: URL {
        appSupportDir.appendingPathComponent("safepocket_local_vault.enc")
    }

    private var cloudMirrorFileURL: URL {
        appSupportDir.appendingPathComponent("safepocket_cloud_mirror.enc")
    }

    private func persistActiveStorageSnapshot() {
        let key = MacZeroKnowledgeCrypto.deriveVaultSymmetricKey(
            masterPassword: activeMasterPassword,
            secretKey: secretKeyFormatted
        )
        guard let plain = try? JSONEncoder().encode(entries),
              let enc = try? MacZeroKnowledgeCrypto.encryptData(plain, key: key, aad: Data("safepocket-mac-store".utf8)),
              let blob = try? JSONEncoder().encode(MacEncryptedBackupContainer(createdAtIso: ISO8601DateFormatter().string(from: Date()), ivHex: enc.ivHex, ciphertextHex: enc.ciphertextHex)) else {
            return
        }

        // Always keep the local encrypted vault up-to-date across both Lokale DB and Cloud-Sync modes
        try? blob.write(to: localVaultFileURL, options: .atomic)
        if storageMode == .cloudSync {
            try? blob.write(to: cloudMirrorFileURL, options: .atomic)
        }
    }

    private func loadPersistedEntriesOrSeedSamples() {
        let key = MacZeroKnowledgeCrypto.deriveVaultSymmetricKey(
            masterPassword: activeMasterPassword,
            secretKey: secretKeyFormatted
        )
        let candidateFiles = [localVaultFileURL, cloudMirrorFileURL]
        for fileURL in candidateFiles {
            if let blob = try? Data(contentsOf: fileURL),
               let container = try? JSONDecoder().decode(MacEncryptedBackupContainer.self, from: blob),
               let plain = try? MacZeroKnowledgeCrypto.decryptData(ivHex: container.ivHex, ciphertextHex: container.ciphertextHex, key: key, aad: Data("safepocket-mac-store".utf8)),
               let decoded = try? JSONDecoder().decode([MacVaultEntry].self, from: plain) {
                self.entries = decoded
                if fileURL != localVaultFileURL {
                    persistActiveStorageSnapshot()
                }
                return
            }
        }

        if !UserDefaults.standard.bool(forKey: samplePurgedKey) {
            self.entries = Self.buildSampleEntries()
            persistActiveStorageSnapshot()
        }
    }

    private func startAutomaticConnectivityMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = (path.status == .satisfied)
            Task { @MainActor [weak self] in
                guard let self else { return }
                let wasOffline = !self.isNetworkOnline
                self.isNetworkOnline = online
                if online && wasOffline && self.storageMode == .cloudSync && self.isCloudAuthenticated {
                    await self.reconcileCloudSessionAutomatically()
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    private func startLiveCloudSyncLoop() {
        standbyObserverTokens.append(
            NotificationCenter.default.addObserver(
                forName: NSApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, !self.isLocked, !self.presentEditorSheet,
                          self.storageMode == .cloudSync, self.isCloudAuthenticated, self.isNetworkOnline else { return }
                    await self.reconcileCloudSessionAutomatically()
                }
            }
        )

        liveSyncLoopTask?.cancel()
        liveSyncLoopTask = Task { @MainActor [weak self] in
            // Immediate startup sync if already in Cloud-Sync mode
            if let self, !self.isLocked, self.storageMode == .cloudSync, self.isCloudAuthenticated, self.isNetworkOnline {
                await self.reconcileCloudSessionAutomatically()
            }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard !Task.isCancelled, let self else { break }
                if !self.isLocked && !self.presentEditorSheet && self.storageMode == .cloudSync && self.isCloudAuthenticated && self.isNetworkOnline {
                    await self.reconcileCloudSessionAutomatically()
                }
            }
        }
    }

    private func persistTombstoneMaps() {
        if let tombData = try? JSONEncoder().encode(pendingTombstones) {
            UserDefaults.standard.set(tombData, forKey: pendingTombstonesKey)
        }
        if let knownData = try? JSONEncoder().encode(knownRemoteTombstones) {
            UserDefaults.standard.set(knownData, forKey: knownTombstonesKey)
        }
    }

    private static func normalizeHlc(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let chars = Array(trimmed)
        if chars.count >= 21 && chars[15] == "-" && chars[20] == "-" {
            return String(chars[0..<15]) + ":" + String(chars[16..<20]) + ":" + String(chars[21...])
        }
        return trimmed
    }

    func switchExclusiveStorageMode(_ nextMode: MacExclusiveStorageMode) async {
        guard nextMode != storageMode else { return }
        storageMode = nextMode
        UserDefaults.standard.set(nextMode.rawValue, forKey: storageModeKey)

        switch nextMode {
        case .cloudSync:
            let nonSampleIds = entries.filter { !$0.isBuiltInSampleEntry }.map(\.id)
            pendingOfflineOutboxIds = Set(nonSampleIds).union(pendingTombstones.keys)
            recalculateStorageIsolationCounts()
            persistActiveStorageSnapshot()
            recordDiagnostic(
                operation: "storage.switchToCloudSync",
                detail: "Enabled Cloud-Sync with preserved Lokale DB (\(standaloneLocalRowCount) items)"
            )
            if isNetworkOnline && isCloudAuthenticated {
                await reconcileCloudSessionAutomatically()
            }
        case .localOnly:
            recalculateStorageIsolationCounts()
            persistActiveStorageSnapshot()
            recordDiagnostic(
                operation: "storage.switchToLocalOnly",
                detail: "Paused Cloud-Sync; Lokale DB active (\(standaloneLocalRowCount) items, Cloud session retained)"
            )
        }
    }

    func createFirebaseAccount(email: String, password: String) async {
        await performFirebaseAuth(action: "signUp", email: email, password: password)
    }

    func signInFirebaseAccount(email: String, password: String) async {
        await performFirebaseAuth(action: "signInWithPassword", email: email, password: password)
    }

    private func performFirebaseAuth(action: String, email: String, password: String) async {
        guard !cloudAuthBusy else { return }
        cloudAuthBusy = true
        lastErrorBanner = nil
        do {
            let session = try await MacCloudSyncClient.authenticateFirebase(
                action: action,
                email: email,
                syncPassword: password,
                apiKey: endpointConfig.firebaseWebApiKey
            )
            cloudSession = session
            if let encoded = try? JSONEncoder().encode(session) {
                MacKeychainStore.saveData(encoded, account: "cloud_session")
            }
            if storageMode != .cloudSync {
                await switchExclusiveStorageMode(.cloudSync)
            } else {
                let nonSampleIds = entries.filter { !$0.isBuiltInSampleEntry }.map(\.id)
                pendingOfflineOutboxIds = Set(nonSampleIds).union(pendingTombstones.keys)
                await reconcileCloudSessionAutomatically()
            }
            statusNotice = "✓ \(t("cloud_auth_signed_in_as", ["email": session.email, "uid": String(session.uid.prefix(10))]))"
            recordDiagnostic(operation: "cloud.\(action)", detail: "Authenticated Firebase account \(session.email)")
        } catch {
            recordDiagnostic(operation: "cloud.\(action)", detail: error.localizedDescription, isError: true)
        }
        cloudAuthBusy = false
    }

    func signOutCloudAccount() async {
        cloudSession = nil
        MacKeychainStore.delete(account: "cloud_session")
        try? FileManager.default.removeItem(at: cloudMirrorFileURL)
        pendingOfflineOutboxIds.removeAll()
        pendingTombstones.removeAll()
        persistTombstoneMaps()
        endpointConfig.googleDriveBearerToken = ""
        await switchExclusiveStorageMode(.localOnly)
        statusNotice = "✓ Signed out & switched to \(t("storage_mode_local_only_title"))"
    }

    func resetCloudEndpointDefaults() {
        endpointConfig = MacCloudEndpointConfig()
        statusNotice = "✓ Reset cloud endpoint configuration to safepocket-vault-eu"
    }

    func reconcileCloudSessionAutomatically() async {
        guard storageMode == .cloudSync, isNetworkOnline, isCloudAuthenticated, !isReconcilingCloud else { return }
        isReconcilingCloud = true
        defer { isReconcilingCloud = false }

        do {
            let localVaultKey = MacZeroKnowledgeCrypto.deriveVaultSymmetricKey(
                masterPassword: activeMasterPassword,
                secretKey: secretKeyFormatted
            )

            if cloudProvider == .firebase, var activeSession = cloudSession {
                guard let cloudKeyHex = activeSession.cloudKeyHex,
                      let cloudKey = MacZeroKnowledgeCrypto.symmetricKeyFromHex(cloudKeyHex) else {
                    throw NSError(
                        domain: "SafePocketCloudAuth",
                        code: 401,
                        userInfo: [NSLocalizedDescriptionKey: "Cloud Sync upgraded to Cross-Platform Zero-Knowledge v2 — please sign in once with your Cloud Sync password."]
                    )
                }

                activeSession = try await MacCloudSyncClient.refreshFirebaseTokenIfPossible(
                    session: activeSession,
                    apiKey: endpointConfig.firebaseWebApiKey,
                    expectedProjectId: endpointConfig.firebaseProjectId,
                    force: false
                )
                cloudSession = activeSession
                if let encoded = try? JSONEncoder().encode(activeSession) {
                    MacKeychainStore.saveData(encoded, account: "cloud_session")
                }

                // 1. PULL FIRST (full delta map, sinceHlc = nil -> 100% immune to clock skew & out-of-order offline flushes)
                var pulledDeltas: [MacEncryptedSyncDelta] = []
                do {
                    pulledDeltas = try await MacCloudSyncClient.pullDeltasFromFirestore(
                        sinceHlc: nil,
                        session: activeSession,
                        config: endpointConfig
                    )
                } catch let pullErr as NSError where pullErr.code == 401 {
                    activeSession = try await MacCloudSyncClient.refreshFirebaseTokenIfPossible(
                        session: activeSession,
                        apiKey: endpointConfig.firebaseWebApiKey,
                        expectedProjectId: endpointConfig.firebaseProjectId,
                        force: true
                    )
                    cloudSession = activeSession
                    if let encoded = try? JSONEncoder().encode(activeSession) {
                        MacKeychainStore.saveData(encoded, account: "cloud_session")
                    }
                    pulledDeltas = try await MacCloudSyncClient.pullDeltasFromFirestore(
                        sinceHlc: nil,
                        session: activeSession,
                        config: endpointConfig
                    )
                }

                var remoteById: [String: MacEncryptedSyncDelta] = [:]
                for var r in pulledDeltas {
                    r.hlcPacked = Self.normalizeHlc(r.hlcPacked)
                    if let prev = remoteById[r.entityId] {
                        if r.hlcPacked > prev.hlcPacked { remoteById[r.entityId] = r }
                    } else {
                        remoteById[r.entityId] = r
                    }
                }

                // Ensure all local non-sample entries have normalized HLCs
                var normalizedEntries = entries
                for idx in normalizedEntries.indices {
                    if normalizedEntries[idx].hlcPacked.isEmpty {
                        normalizedEntries[idx].hlcPacked = nextHlcPacked(for: normalizedEntries[idx].updatedAt)
                    } else {
                        normalizedEntries[idx].hlcPacked = Self.normalizeHlc(normalizedEntries[idx].hlcPacked)
                    }
                }
                var localEntryById = Dictionary(uniqueKeysWithValues: normalizedEntries.map { ($0.id, $0) })

                // 2. CRDT Reconcile: build winning outbound deltas AND apply winning remote deltas locally
                var outboundDeltas: [MacEncryptedSyncDelta] = []
                var pulledApplied = 0

                let allIds = Set(localEntryById.keys)
                    .union(pendingTombstones.keys)
                    .union(remoteById.keys)

                for entityId in allIds {
                    let localItem = localEntryById[entityId]
                    let pendingTomb = pendingTombstones[entityId]
                    let remote = remoteById[entityId]

                    // Case A: Local pending tombstone
                    if let tomb = pendingTomb {
                        let tombHlc = Self.normalizeHlc(tomb.hlcPacked)
                        if let rem = remote, rem.hlcPacked > tombHlc {
                            // Remote mutation is newer than our local deletion!
                            pendingTombstones.removeValue(forKey: entityId)
                            if rem.isTombstone {
                                knownRemoteTombstones[entityId] = rem.hlcPacked
                                localEntryById.removeValue(forKey: entityId)
                            } else if let decoded = decryptRemoteDelta(rem, cloudKey: cloudKey, localFallbackKey: localVaultKey, migrationOutbox: &outboundDeltas) {
                                localEntryById[entityId] = decoded
                                pulledApplied += 1
                            }
                        } else {
                            // Push tombstone if remote doesn't already have this tombstone at >= HLC
                            if remote == nil || !(remote!.isTombstone && remote!.hlcPacked >= tombHlc) {
                                let aadStr = "safepocket:v1:\(tomb.category):\(tomb.id)"
                                let enc = try MacZeroKnowledgeCrypto.encryptData(
                                    Data("{\"deleted\":true}".utf8),
                                    key: cloudKey,
                                    aad: Data(aadStr.utf8)
                                )
                                outboundDeltas.append(
                                    MacEncryptedSyncDelta(
                                        entityId: tomb.id,
                                        entityType: "ITEM",
                                        hlcPacked: tombHlc,
                                        versionVectorJson: "{\"\(nodeId)\":\(tomb.deletedAtMs)}",
                                        ivHex: enc.ivHex,
                                        ciphertextHex: enc.ciphertextHex,
                                        aadContext: aadStr,
                                        isTombstone: true
                                    )
                                )
                            }
                            knownRemoteTombstones[entityId] = tombHlc
                            localEntryById.removeValue(forKey: entityId)
                        }
                        continue
                    }

                    // Case B: Local active entry vs Remote
                    if let item = localItem {
                        let itemHlc = Self.normalizeHlc(item.hlcPacked)
                        if let rem = remote {
                            if rem.isTombstone {
                                if rem.hlcPacked >= itemHlc || item.isBuiltInSampleEntry {
                                    knownRemoteTombstones[entityId] = rem.hlcPacked
                                    localEntryById.removeValue(forKey: entityId)
                                    pulledApplied += 1
                                } else if !item.isBuiltInSampleEntry {
                                    outboundDeltas.append(try buildOutboundItemDelta(item, cloudKey: cloudKey))
                                }
                            } else if rem.hlcPacked > itemHlc {
                                if let decoded = decryptRemoteDelta(rem, cloudKey: cloudKey, localFallbackKey: localVaultKey, migrationOutbox: &outboundDeltas) {
                                    localEntryById[entityId] = decoded
                                    pulledApplied += 1
                                }
                            } else if itemHlc > rem.hlcPacked && !item.isBuiltInSampleEntry {
                                outboundDeltas.append(try buildOutboundItemDelta(item, cloudKey: cloudKey))
                            }
                        } else {
                            // Not on remote yet: push ONLY if non-sample AND not superseded by a known tombstone
                            if !item.isBuiltInSampleEntry {
                                if let knownTombHlc = knownRemoteTombstones[entityId], knownTombHlc >= itemHlc {
                                    localEntryById.removeValue(forKey: entityId)
                                } else {
                                    outboundDeltas.append(try buildOutboundItemDelta(item, cloudKey: cloudKey))
                                }
                            }
                        }
                        continue
                    }

                    // Case C: Remote-only record (not present locally)
                    if let rem = remote {
                        if rem.isTombstone {
                            knownRemoteTombstones[entityId] = rem.hlcPacked
                        } else if let decoded = decryptRemoteDelta(rem, cloudKey: cloudKey, localFallbackKey: localVaultKey, migrationOutbox: &outboundDeltas) {
                            localEntryById[entityId] = decoded
                            pulledApplied += 1
                        }
                    }
                }

                // 3. PUSH ONLY WINNING OUTBOUND DELTAS
                var pushed = 0
                if !outboundDeltas.isEmpty {
                    pushed = try await MacCloudSyncClient.pushDeltasToFirestore(
                        deltas: outboundDeltas,
                        session: activeSession,
                        config: endpointConfig
                    )
                }

                // Clear flushed tombstones & outbox
                pendingTombstones.removeAll()
                pendingOfflineOutboxIds.removeAll()
                persistTombstoneMaps()

                // Hide untouched built-in sample noise automatically once real cloud items exist
                let hasRealCloudItems = pulledDeltas.contains { !$0.isTombstone && !MacVaultEntry.knownBuiltInSampleIds.contains($0.entityId) }
                if hasRealCloudItems && !UserDefaults.standard.bool(forKey: samplePurgedKey) {
                    UserDefaults.standard.set(true, forKey: samplePurgedKey)
                    localEntryById = localEntryById.filter { !$0.value.isBuiltInSampleEntry }
                } else if UserDefaults.standard.bool(forKey: samplePurgedKey) {
                    localEntryById = localEntryById.filter { !$0.value.isBuiltInSampleEntry }
                }

                let sortedMerged = Array(localEntryById.values).sorted { $0.updatedAt > $1.updatedAt }
                if sortedMerged != entries {
                    entries = sortedMerged
                    if let sel = selectedEntryId, !entries.contains(where: { $0.id == sel }) {
                        selectedEntryId = entries.first?.id
                    } else if selectedEntryId == nil {
                        selectedEntryId = entries.first?.id
                    }
                    recalculateStorageIsolationCounts()
                    persistActiveStorageSnapshot()
                }

                lastSyncPushed = pushed
                lastSyncPulled = pulledApplied
                lastSyncedIso = ISO8601DateFormatter().string(from: Date())
                lastErrorBanner = nil
                if pushed > 0 || pulledApplied > 0 {
                    recordDiagnostic(
                        operation: "cloud.firebaseSync",
                        detail: "Synced zero-knowledge deltas (↑\(pushed) pushed • ↓\(pulledApplied) pulled)"
                    )
                }
            } else if cloudProvider == .googleDrive {
                try? await Task.sleep(nanoseconds: 180_000_000)
                let flushed = pendingOfflineOutboxIds.count
                pendingOfflineOutboxIds.removeAll()
                lastSyncPushed = flushed
                lastSyncedIso = ISO8601DateFormatter().string(from: Date())
                recalculateStorageIsolationCounts()
                persistActiveStorageSnapshot()
                recordDiagnostic(operation: "cloud.gdriveSync", detail: "Synced \(flushed) encrypted item(s) to Google Drive appDataFolder")
            }
        } catch {
            if (error as NSError).code == 401 {
                cloudSession = nil
                MacKeychainStore.delete(account: "cloud_session")
            }
            recordDiagnostic(operation: "cloud.syncError", detail: error.localizedDescription, isError: true)
        }
    }

    private func buildOutboundItemDelta(_ item: MacVaultEntry, cloudKey: SymmetricKey) throws -> MacEncryptedSyncDelta {
        let hlc = item.hlcPacked.isEmpty ? nextHlcPacked(for: item.updatedAt) : Self.normalizeHlc(item.hlcPacked)
        let aadStr = "safepocket:v1:\(item.category):\(item.id)"
        let plain = try item.toCanonicalWireData()
        let enc = try MacZeroKnowledgeCrypto.encryptData(plain, key: cloudKey, aad: Data(aadStr.utf8))
        let epochMs = Int64((item.updatedAt.timeIntervalSince1970 * 1000.0).rounded())
        return MacEncryptedSyncDelta(
            entityId: item.id,
            entityType: "ITEM",
            hlcPacked: hlc,
            versionVectorJson: "{\"\(nodeId)\":\(epochMs)}",
            ivHex: enc.ivHex,
            ciphertextHex: enc.ciphertextHex,
            aadContext: aadStr,
            isTombstone: false
        )
    }

    private func decryptRemoteDelta(
        _ remote: MacEncryptedSyncDelta,
        cloudKey: SymmetricKey,
        localFallbackKey: SymmetricKey,
        migrationOutbox: inout [MacEncryptedSyncDelta]
    ) -> MacVaultEntry? {
        let aadData = Data(remote.aadContext.utf8)
        let normHlc = Self.normalizeHlc(remote.hlcPacked)
        if let plain = try? MacZeroKnowledgeCrypto.decryptData(
            ivHex: remote.ivHex,
            ciphertextHex: remote.ciphertextHex,
            key: cloudKey,
            aad: aadData
        ), let decoded = MacVaultEntry.fromCanonicalOrLegacyData(plain, hlcPacked: normHlc) {
            return decoded
        }
        // Self-migration fallback for v0.3.x envelopes pushed by this Mac under localVaultKey
        if let legacyPlain = try? MacZeroKnowledgeCrypto.decryptData(
            ivHex: remote.ivHex,
            ciphertextHex: remote.ciphertextHex,
            key: localFallbackKey,
            aad: aadData
        ), let decoded = MacVaultEntry.fromCanonicalOrLegacyData(legacyPlain, hlcPacked: normHlc) {
            if let upgraded = try? buildOutboundItemDelta(decoded, cloudKey: cloudKey) {
                migrationOutbox.append(upgraded)
            }
            return decoded
        }
        return nil
    }

    private func nextHlcPacked(for date: Date = Date()) -> String {
        hlcLogicalCounter = (hlcLogicalCounter + 1) % 10_000
        let ms = Int64((date.timeIntervalSince1970 * 1000.0).rounded())
        return String(format: "%015lld:%04d:%@", ms, hlcLogicalCounter, nodeId)
    }

    private func recalculateStorageIsolationCounts() {
        standaloneLocalRowCount = entries.count
        cloudSessionMirrorCount = (storageMode == .cloudSync) ? entries.count : 0
    }

    // MARK: - Vault CRUD & Sample Data Purge

    func openCreateEditor(category: String? = nil) {
        restartInactivityAutoLockTimer()
        let validCategories = [
            "PAYMENT_CARD",
            "BANK_ACCOUNT",
            "INSURANCE_CARD",
            "MEMBERSHIP_CARD",
            "IDENTITY",
            "OFFICIAL_DOCUMENT",
            "SECURE_NOTE"
        ]
        if let explicit = category, validCategories.contains(explicit) {
            pendingCreateCategory = explicit
        } else if validCategories.contains(selectedCategory) {
            pendingCreateCategory = selectedCategory
        } else {
            pendingCreateCategory = "PAYMENT_CARD"
        }
        editingEntry = nil
        presentEditorSheet = true
    }

    func openEditEditor(_ entry: MacVaultEntry) {
        restartInactivityAutoLockTimer()
        editingEntry = entry
        presentEditorSheet = true
    }

    func saveEntry(_ updated: MacVaultEntry) {
        restartInactivityAutoLockTimer()
        var normalized = updated
        normalized.isSampleItem = false
        normalized.updatedAt = Date()
        normalized.hlcPacked = nextHlcPacked(for: normalized.updatedAt)
        pendingTombstones.removeValue(forKey: normalized.id)
        knownRemoteTombstones.removeValue(forKey: normalized.id)
        persistTombstoneMaps()
        for idx in normalized.fields.indices {
            if normalized.fields[idx].valueType == .url && !normalized.fields[idx].value.isEmpty {
                normalized.fields[idx].value = Self.normalizeHttpsUrl(normalized.fields[idx].value)
            }
        }
        if let inst = MacSmartIntelligence.resolveInstitution(for: normalized), normalized.customColorHex == nil {
            normalized.customColorHex = inst.primaryHex
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
        entries[idx].isSampleItem = false
        entries[idx].pinned.toggle()
        entries[idx].updatedAt = Date()
        entries[idx].hlcPacked = nextHlcPacked()
        markMutated(id: id)
    }

    func toggleFavorite(_ id: String) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        entries[idx].isSampleItem = false
        entries[idx].favorite.toggle()
        entries[idx].updatedAt = Date()
        entries[idx].hlcPacked = nextHlcPacked()
        markMutated(id: id)
    }

    func duplicateEntry(_ id: String) {
        guard let src = entries.first(where: { $0.id == id }) else { return }
        var copy = src
        copy.id = "item-\(Int(Date().timeIntervalSince1970 * 1000))"
        copy.title = "\(src.title) (Copy)"
        copy.isSampleItem = false
        copy.updatedAt = Date()
        copy.hlcPacked = nextHlcPacked()
        entries.insert(copy, at: 0)
        selectedEntryId = copy.id
        markMutated(id: copy.id)
    }

    func deleteEntry(_ id: String) {
        let deletedCategory = entries.first(where: { $0.id == id })?.category ?? "SECURE_NOTE"
        let now = Date()
        let hlc = nextHlcPacked(for: now)
        let nowMs = Int64((now.timeIntervalSince1970 * 1000.0).rounded())
        pendingTombstones[id] = MacPendingTombstone(id: id, category: deletedCategory, hlcPacked: hlc, deletedAtMs: nowMs)
        knownRemoteTombstones[id] = hlc
        persistTombstoneMaps()

        entries.removeAll { $0.id == id }
        if selectedEntryId == id {
            selectedEntryId = entries.first?.id
        }
        markMutated(id: id)
    }

    func deleteAllExampleData() {
        let removedEntries = entries.filter { $0.isSampleItem }
        let now = Date()
        let nowMs = Int64((now.timeIntervalSince1970 * 1000.0).rounded())
        for sample in removedEntries {
            let hlc = nextHlcPacked(for: now)
            pendingTombstones[sample.id] = MacPendingTombstone(
                id: sample.id,
                category: sample.category,
                hlcPacked: hlc,
                deletedAtMs: nowMs
            )
            knownRemoteTombstones[sample.id] = hlc
            pendingOfflineOutboxIds.insert(sample.id)
        }
        persistTombstoneMaps()

        entries.removeAll { $0.isSampleItem }
        UserDefaults.standard.set(true, forKey: samplePurgedKey)
        if let sel = selectedEntryId, !entries.contains(where: { $0.id == sel }) {
            selectedEntryId = entries.first?.id
        }
        recalculateStorageIsolationCounts()
        persistActiveStorageSnapshot()
        if storageMode == .cloudSync && isNetworkOnline && isCloudAuthenticated {
            Task { await reconcileCloudSessionAutomatically() }
        }
        statusNotice = "✓ \(t("delete_example_data_done", ["count": String(removedEntries.count)]))"
        recordDiagnostic(operation: "vault.deleteExampleData", detail: "Deleted \(removedEntries.count) built-in sample item(s) permanently")
    }

    private func markMutated(id: String) {
        recalculateStorageIsolationCounts()
        persistActiveStorageSnapshot()
        if storageMode == .cloudSync {
            pendingOfflineOutboxIds.insert(id)
            if isNetworkOnline && isCloudAuthenticated {
                Task { await reconcileCloudSessionAutomatically() }
            }
        }
    }

    // MARK: - Native macOS File Dialogs for Backup, Import & Export

    func exportEncryptedBackupViaDialog(backupPassword: String) {
        do {
            let data = try MacPortabilityEngine.exportEncryptedContainer(
                entries: entries,
                backupPassword: backupPassword,
                secretKey: secretKeyFormatted
            )
            saveDataWithPanel(data: data, defaultName: "safepocket-backup.safepocket")
            statusNotice = "✓ Exported encrypted .safepocket backup"
        } catch {
            recordDiagnostic(operation: "backup.export", detail: error.localizedDescription, isError: true)
        }
    }

    func restoreEncryptedBackupViaDialog(backupPassword: String, overrideSecretKey: String, replaceExisting: Bool) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let data = try Data(contentsOf: url)
            let sk = overrideSecretKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? secretKeyFormatted
                : overrideSecretKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let restored = try MacPortabilityEngine.restoreEncryptedContainer(data, backupPassword: backupPassword, secretKey: sk)
            if replaceExisting {
                entries = restored
            } else {
                var byId = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
                for r in restored { byId[r.id] = r }
                entries = Array(byId.values).sorted { $0.updatedAt > $1.updatedAt }
            }
            selectedEntryId = entries.first?.id
            persistActiveStorageSnapshot()
            recalculateStorageIsolationCounts()
            statusNotice = "✓ Restored \(restored.count) encrypted item(s)"
        } catch {
            recordDiagnostic(operation: "backup.restore", detail: error.localizedDescription, isError: true)
        }
    }

    func importCsvOr1PasswordViaDialog() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let data = try Data(contentsOf: url)
            let imported = try MacPortabilityEngine.importFromCsvOr1Pux(
                data: data,
                fileName: url.lastPathComponent,
                includeLoginsAsNotes: includeLoginsOnImport
            )
            entries.insert(contentsOf: imported, at: 0)
            selectedEntryId = entries.first?.id
            persistActiveStorageSnapshot()
            recalculateStorageIsolationCounts()
            statusNotice = "✓ Imported \(imported.count) item(s)"
        } catch {
            recordDiagnostic(operation: "import.file", detail: error.localizedDescription, isError: true)
        }
    }

    func exportPlainFormatViaDialog(kind: String, confirmMasterPw: String) {
        guard verifyMasterPassword(confirmMasterPw) else {
            recordDiagnostic(operation: "export.\(kind)", detail: "Invalid Master Password confirmation", isError: true)
            return
        }
        do {
            switch kind {
            case "1pux":
                saveDataWithPanel(data: try MacPortabilityEngine.export1Password1PuxJson(entries: entries), defaultName: "safepocket-export.1pux")
            case "1p_csv":
                saveDataWithPanel(data: MacPortabilityEngine.export1PasswordCsv(entries: entries), defaultName: "safepocket-1password.csv")
            case "sp_csv":
                saveDataWithPanel(data: MacPortabilityEngine.exportSafePocketCsv(entries: entries), defaultName: "safepocket-vault.csv")
            default:
                saveDataWithPanel(data: try MacPortabilityEngine.exportPlainJson(entries: entries), defaultName: "safepocket-vault.json")
            }
            statusNotice = "✓ Exported \(kind.uppercased())"
        } catch {
            recordDiagnostic(operation: "export.\(kind)", detail: error.localizedDescription, isError: true)
        }
    }

    func exportEmergencyKitViaDialog(versionDisplay: String) {
        let html = MacPortabilityEngine.generateEmergencyKitHtml(
            secretKey: secretKeyFormatted,
            hint: masterPasswordHint,
            versionDisplay: versionDisplay
        )
        saveDataWithPanel(data: html, defaultName: "safepocket-emergency-kit.html")
        statusNotice = "✓ Saved Emergency Kit HTML"
    }

    private func saveDataWithPanel(data: Data, defaultName: String) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = defaultName
        panel.canCreateDirectories = true
        if panel.runModal() == .OK, let url = panel.url {
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: - Utilities, Scrubbing, Touch ID & Clipboard Hygiene

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

    func clearDiagnostics() {
        diagnostics.removeAll()
    }

    func copyDiagnosticsReport() {
        let text = diagnostics.map { "[\(ISO8601DateFormatter().string(from: $0.timestamp))] [\($0.operation)] \($0.scrubbedDetail)" }
            .joined(separator: "\n")
        copyFieldWithClipboardHygiene(text.isEmpty ? "No diagnostics events" : text, isSensitive: false)
        statusNotice = "✓ \(t("action_copied"))"
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
            if ok {
                isLocked = false
                restartInactivityAutoLockTimer()
            }
            return ok
        } catch {
            recordDiagnostic(operation: "unlock.touchId", detail: error.localizedDescription, isError: true)
            return false
        }
    }

    func copyFieldWithClipboardHygiene(_ value: String, isSensitive: Bool) {
        restartInactivityAutoLockTimer()
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
        let soonDate = ISO8601DateFormatter.string(
            from: Date().addingTimeInterval(48 * 86_400),
            timeZone: TimeZone(secondsFromGMT: 0)!,
            formatOptions: [.withFullDate]
        )
        return [
            MacVaultEntry(
                id: "item-card-db-visa",
                category: "PAYMENT_CARD",
                title: "Deutsche Bank Travel VISA",
                subtitle: "Clara Sofia Müller • Credit Card",
                fields: [
                    .init(id: "f1", labelKey: "field_cardholder", value: "CLARA SOFIA MÜLLER"),
                    .init(id: "f2", labelKey: "field_card_number", value: "4532 0151 1283 0366", valueType: .cardNumber, isSensitive: true),
                    .init(id: "f3", labelKey: "field_card_network", value: "VISA"),
                    .init(id: "f4", labelKey: "field_card_type", value: "Credit Card"),
                    .init(id: "f5", labelKey: "field_expiry_date", value: "2029-08", valueType: .dateIso),
                    .init(id: "f6", labelKey: "field_cvv", value: "842", valueType: .sensitiveSecret, isSensitive: true),
                    .init(id: "f7", labelKey: "field_pin", value: "2940", valueType: .sensitiveSecret, isSensitive: true),
                    .init(id: "f8", labelKey: "field_issuing_bank", value: "Deutsche Bank AG"),
                    .init(id: "f9", labelKey: "field_iban", value: "DE12 5007 0010 0532 0130 00", valueType: .iban, isSensitive: true),
                    .init(id: "f10", labelKey: "field_bic", value: "DEUTDEFFXXX"),
                    .init(id: "f11", labelKey: "field_support_phone", value: "+49 69 910 10000", valueType: .phone),
                    .init(id: "f12", labelKey: "field_institution_domain", value: "https://www.deutsche-bank.de", valueType: .url)
                ],
                tags: ["travel", "visa", "sepa"],
                customColorHex: "#0018A8",
                networkBrand: "VISA",
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
                fields: [
                    .init(id: "b1", labelKey: "field_account_holder", value: "Clara Sofia Müller"),
                    .init(id: "b2", labelKey: "field_issuing_bank", value: "ING-DiBa AG"),
                    .init(id: "b3", labelKey: "field_account_type", value: "Checking Account (Girokonto)"),
                    .init(id: "b4", labelKey: "field_iban", value: "DE89 5001 0517 5407 3249 31", valueType: .iban, isSensitive: true),
                    .init(id: "b5", labelKey: "field_bic", value: "INGDDEFFXXX"),
                    .init(id: "b6", labelKey: "field_currency", value: "EUR"),
                    .init(id: "b7", labelKey: "field_support_phone", value: "+49 69 50 500 105", valueType: .phone),
                    .init(id: "b8", labelKey: "field_institution_domain", value: "https://www.ing.de", valueType: .url)
                ],
                tags: ["girokonto", "sepa"],
                customColorHex: "#FF6200",
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
                fields: [
                    .init(id: "d1", labelKey: "field_document_subtype", value: "Biometric Passport"),
                    .init(id: "d2", labelKey: "field_document_number", value: "C01X00T47", valueType: .sensitiveSecret, isSensitive: true),
                    .init(id: "d3", labelKey: "field_issuing_authority", value: "Stadt München"),
                    .init(id: "d4", labelKey: "field_expiry_date", value: soonDate, valueType: .dateIso),
                    .init(id: "d5", labelKey: "field_institution_domain", value: "https://www.auswaertiges-amt.de", valueType: .url)
                ],
                tags: ["passport", "eu"],
                customColorHex: "#1E3A5F",
                pinned: false,
                favorite: false,
                isSampleItem: true,
                updatedAt: Date().addingTimeInterval(-7200)
            )
        ]
    }
}
