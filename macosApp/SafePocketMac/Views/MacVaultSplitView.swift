import SwiftUI
import AppKit

/// Native Apple HIG 3-Column NavigationSplitView for SafePocket v0.3 (Build 5).
/// Includes:
/// - Dedicated non-truncated Settings item + bottom-left quick theme toggle
/// - Swipe options & right-click context menus on every vault entry
/// - Delete All Example Data banner & confirmation
/// - Smart Field Editor sheet (Bank Account Type dropdown, E.164 phone prefix, live HTTPS reachability check, IBAN autofill)
/// - Persistent Bottom-Right Sync Status Pill
struct MacVaultSplitView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"
    @State private var confirmDeleteSamples = false

    private func cycleTheme() {
        switch themeMode {
        case "light": themeMode = "dark"
        case "dark": themeMode = "system"
        default: themeMode = "light"
        }
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            NavigationSplitView {
                VStack(spacing: 0) {
                    List(selection: $vaultState.selectedCategory) {
                        Section("Vault") {
                            Label("All Items (\(vaultState.entries.count))", systemImage: "tray.full")
                                .tag("all")
                            Label("Favorites", systemImage: "star")
                                .tag("favorites")
                        }
                        Section("Categories") {
                            Label("Payment Cards", systemImage: "creditcard")
                                .tag("PAYMENT_CARD")
                            Label("Bank Accounts", systemImage: "building.columns")
                                .tag("BANK_ACCOUNT")
                            Label("Insurance Cards", systemImage: "cross.case")
                                .tag("INSURANCE_CARD")
                            Label("Membership Cards", systemImage: "person.crop.rectangle.stack")
                                .tag("MEMBERSHIP_CARD")
                            Label("Identities", systemImage: "person.text.rectangle")
                                .tag("IDENTITY")
                            Label("Official Documents", systemImage: "doc.badge.gearshape")
                                .tag("OFFICIAL_DOCUMENT")
                            Label("Secure Notes", systemImage: "lock.doc")
                                .tag("SECURE_NOTE")
                        }
                        Section {
                            Label("Settings & Updates", systemImage: "gearshape.fill")
                                .lineLimit(1)
                                .tag("SETTINGS")
                        }
                    }
                    .listStyle(.sidebar)

                    Divider()

                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            Button {
                                cycleTheme()
                            } label: {
                                Image(systemName: themeMode == "dark" ? "moon.stars.fill" : (themeMode == "system" ? "circle.lefthalf.filled" : "sun.max.fill"))
                            }
                            .buttonStyle(.bordered)
                            .help("Switch Light / Dark / System Theme")

                            Button {
                                vaultState.lockVault()
                            } label: {
                                Label(vaultState.t("action_lock_vault"), systemImage: "lock.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }

                        HStack {
                            Image(systemName: "checkmark.shield.fill")
                                .foregroundStyle(.secondary)
                                .imageScale(.small)
                            Text(updater.currentVersionDisplay)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Spacer()
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                }
                .background(.ultraThinMaterial)
                .navigationSplitViewColumnWidth(min: 220, ideal: 248, max: 300)
            } content: {
                VStack(spacing: 0) {
                    if let err = vaultState.lastErrorBanner {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            Text(err)
                                .font(.caption)
                                .lineLimit(2)
                            Spacer()
                            Button("✕") {
                                vaultState.lastErrorBanner = nil
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(8)
                        .background(Color.orange.opacity(0.14))
                    }

                    if vaultState.sampleEntriesCount > 0 {
                        HStack {
                            Text("Example Data (\(vaultState.sampleEntriesCount))")
                                .font(.caption.weight(.semibold))
                            Spacer()
                            Button(role: .destructive) {
                                confirmDeleteSamples = true
                            } label: {
                                Text("Delete Example Data")
                                    .font(.caption)
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.red.opacity(0.08))
                    }

                    List(selection: $vaultState.selectedEntryId) {
                        ForEach(vaultState.filteredEntries) { entry in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    if entry.pinned {
                                        Image(systemName: "pin.fill")
                                            .font(.caption2)
                                            .foregroundStyle(.teal)
                                    }
                                    if entry.favorite {
                                        Image(systemName: "star.fill")
                                            .font(.caption2)
                                            .foregroundStyle(.yellow)
                                    }
                                    Text(entry.title)
                                        .font(.body.weight(.semibold))
                                        .lineLimit(1)
                                }
                                Text(entry.subtitle)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            .padding(.vertical, 3)
                            .tag(entry.id)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                Button {
                                    vaultState.togglePin(entry.id)
                                } label: {
                                    Label(entry.pinned ? "Unpin" : "Pin", systemImage: "pin.fill")
                                }
                                .tint(.teal)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    vaultState.deleteEntry(entry.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Button {
                                    vaultState.openEditEditor(entry)
                                } label: {
                                    Label("Edit", systemImage: "pencil")
                                }
                                .tint(.blue)
                                Button {
                                    vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                                } label: {
                                    Label("Copy", systemImage: "doc.on.doc")
                                }
                                .tint(.indigo)
                            }
                            .contextMenu {
                                Button {
                                    vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                                } label: {
                                    Label("Copy \(entry.primaryLabel)", systemImage: "doc.on.doc")
                                }
                                Button {
                                    vaultState.openEditEditor(entry)
                                } label: {
                                    Label("Edit Entry…", systemImage: "pencil")
                                }
                                Button {
                                    vaultState.togglePin(entry.id)
                                } label: {
                                    Label(entry.pinned ? "Unpin" : "Pin to Top", systemImage: "pin")
                                }
                                Button {
                                    vaultState.toggleFavorite(entry.id)
                                } label: {
                                    Label(entry.favorite ? "Remove Favorite" : "Mark Favorite", systemImage: "star")
                                }
                                Button {
                                    vaultState.duplicateEntry(entry.id)
                                } label: {
                                    Label("Duplicate", systemImage: "plus.square.on.square")
                                }
                                Divider()
                                Button(role: .destructive) {
                                    vaultState.deleteEntry(entry.id)
                                } label: {
                                    Label("Delete Entry", systemImage: "trash")
                                }
                            }
                        }
                    }
                    .searchable(text: $vaultState.searchQuery, prompt: "Search titles, IBAN, websites…")
                }
                .navigationSplitViewColumnWidth(min: 280, ideal: 330, max: 430)
            } detail: {
                if vaultState.selectedCategory == "SETTINGS" {
                    MacSettingsAndAboutView(vaultState: vaultState, updater: updater)
                } else if let selected = vaultState.entries.first(where: { $0.id == vaultState.selectedEntryId }) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            HStack(alignment: .top) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(selected.category.replacingOccurrences(of: "_", with: " "))
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(.teal)
                                    Text(selected.title)
                                        .font(.title2.weight(.bold))
                                    Text(selected.subtitle)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                HStack(spacing: 8) {
                                    Button("Edit") {
                                        vaultState.openEditEditor(selected)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    Button("Duplicate") {
                                        vaultState.duplicateEntry(selected.id)
                                    }
                                    Button(role: .destructive) {
                                        vaultState.deleteEntry(selected.id)
                                    } label: {
                                        Text("Delete")
                                    }
                                }
                            }

                            GroupBox(selected.primaryLabel) {
                                HStack {
                                    Text(selected.primaryValue)
                                        .font(.system(.body, design: .monospaced))
                                    Spacer()
                                    Button("Copy (30s Scrub)") {
                                        vaultState.copyFieldWithClipboardHygiene(selected.primaryValue, isSensitive: true)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                            .contextMenu {
                                Button("Quick Copy \(selected.primaryLabel)") {
                                    vaultState.copyFieldWithClipboardHygiene(selected.primaryValue, isSensitive: true)
                                }
                            }

                            if !selected.websiteUrl.isEmpty {
                                GroupBox("Official Website (HTTPS)") {
                                    HStack {
                                        Text(selected.websiteUrl)
                                            .font(.system(.callout, design: .monospaced))
                                        Spacer()
                                        Button("Copy URL") {
                                            vaultState.copyFieldWithClipboardHygiene(selected.websiteUrl, isSensitive: false)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                }
                            }

                            if !selected.phone.isEmpty {
                                GroupBox("International Support Phone") {
                                    HStack {
                                        Text(selected.phone)
                                            .font(.system(.callout, design: .monospaced))
                                        Spacer()
                                        Button("Copy Phone") {
                                            vaultState.copyFieldWithClipboardHygiene(selected.phone, isSensitive: false)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                        }
                        .padding(24)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                } else {
                    VStack(spacing: 12) {
                        Text("SafePocket Zero-Knowledge Vault")
                            .font(.title3.weight(.semibold))
                        Button("Create First Vault Entry") {
                            vaultState.openCreateEditor()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        vaultState.openCreateEditor()
                    } label: {
                        Label(vaultState.t("action_new_item"), systemImage: "plus")
                    }

                    Button {
                        vaultState.lockVault()
                    } label: {
                        Label(vaultState.t("action_lock_vault"), systemImage: "lock")
                    }
                }
            }

            // Persistent Bottom-Right Sync Status Pill
            Button {
                if vaultState.storageMode == .cloudSync {
                    Task { await vaultState.reconcileCloudSessionAutomatically() }
                } else {
                    vaultState.selectedCategory = "SETTINGS"
                }
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: vaultState.syncPillSystemImage)
                        .foregroundStyle(vaultState.storageMode == .cloudSync ? .green : .teal)
                    Text(vaultState.syncPillLabel)
                        .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(.regularMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(Color.primary.opacity(0.12), lineWidth: 1))
                .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
            }
            .buttonStyle(.plain)
            .padding(16)
        }
        .sheet(isPresented: $vaultState.presentEditorSheet) {
            MacSmartItemEditorSheet(vaultState: vaultState, existing: vaultState.editingEntry)
        }
        .confirmationDialog(
            "Delete all \(vaultState.sampleEntriesCount) built-in example entries?",
            isPresented: $confirmDeleteSamples
        ) {
            Button("Delete All Example Data", role: .destructive) {
                vaultState.deleteAllExampleData()
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}

// MARK: - Smart Field Item Editor Sheet (Bank Type Dropdown, E.164 Phone, Live HTTPS Probe, IBAN Autofill)

struct MacSmartItemEditorSheet: View {
    @ObservedObject var vaultState: MacVaultViewModel
    let existing: MacVaultEntry?
    @Environment(\.dismiss) private var dismiss

    @State private var category: String = "BANK_ACCOUNT"
    @State private var title: String = ""
    @State private var bankAccountType: String = "Checking Account (Girokonto)"
    @State private var primaryValue: String = ""
    @State private var websiteUrl: String = ""
    @State private var websiteStatus: String = ""
    @State private var dialPrefix: String = "+49"
    @State private var localPhone: String = ""

    private let bankAccountTypes = [
        "Checking Account (Girokonto)",
        "Savings / Instant Access (Tagesgeld)",
        "Fixed Deposit (Festgeld)",
        "Joint Account (Gemeinschaftskonto)",
        "Business Account (Geschäftskonto)",
        "Securities / Brokerage (Depot)",
        "Foreign Currency Account"
    ]

    private let dialPrefixes = ["+49", "+30", "+39", "+33", "+34", "+43", "+41", "+44", "+1"]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(existing == nil ? "New Smart Vault Entry" : "Edit Vault Entry")
                .font(.headline)

            Form {
                Picker("Category", selection: $category) {
                    Text("Bank Account").tag("BANK_ACCOUNT")
                    Text("Payment Card").tag("PAYMENT_CARD")
                    Text("Insurance Card").tag("INSURANCE_CARD")
                    Text("Membership Card").tag("MEMBERSHIP_CARD")
                    Text("Identity").tag("IDENTITY")
                    Text("Official Document").tag("OFFICIAL_DOCUMENT")
                    Text("Secure Note").tag("SECURE_NOTE")
                }

                TextField("Title / Institution", text: $title)

                if category == "BANK_ACCOUNT" {
                    Picker("Bank Account Type", selection: $bankAccountType) {
                        ForEach(bankAccountTypes, id: \.self) { opt in
                            Text(opt).tag(opt)
                        }
                    }
                }

                TextField(category == "BANK_ACCOUNT" ? "IBAN (auto-formats 4-char blocks)" : "Primary Number / ID", text: $primaryValue)
                    .onChange(of: primaryValue) { _, next in
                        if category == "BANK_ACCOUNT" {
                            let compact = next.uppercased().filter { $0.isLetter || $0.isNumber }
                            let grouped = stride(from: 0, to: compact.count, by: 4).map { idx -> String in
                                let s = compact.index(compact.startIndex, offsetBy: idx)
                                let e = compact.index(s, offsetBy: 4, limitedBy: compact.endIndex) ?? compact.endIndex
                                return String(compact[s..<e])
                            }.joined(separator: " ")
                            if grouped != next { primaryValue = grouped }
                            if compact.hasPrefix("DE") && compact.count >= 12 {
                                let blzStart = compact.index(compact.startIndex, offsetBy: 4)
                                let blzEnd = compact.index(blzStart, offsetBy: 8)
                                let blz = String(compact[blzStart..<blzEnd])
                                if blz == "50070010" && title.isEmpty {
                                    title = "Deutsche Bank AG"
                                    websiteUrl = "https://www.deutsche-bank.de"
                                } else if blz == "50010517" && title.isEmpty {
                                    title = "ING-DiBa AG"
                                    websiteUrl = "https://www.ing.de"
                                }
                            }
                        }
                    }

                HStack {
                    TextField("Website (auto-prefixes https://)", text: $websiteUrl)
                    Button("Verify HTTPS") {
                        websiteUrl = MacVaultViewModel.normalizeHttpsUrl(websiteUrl)
                        verifyHttpsReachability(websiteUrl)
                    }
                }

                if !websiteStatus.isEmpty {
                    Text(websiteStatus)
                        .font(.caption)
                        .foregroundStyle(websiteStatus.hasPrefix("✓") ? .green : .orange)
                }

                HStack {
                    Picker("Prefix", selection: $dialPrefix) {
                        ForEach(dialPrefixes, id: \.self) { p in
                            Text(p).tag(p)
                        }
                    }
                    .frame(width: 110)

                    TextField("Support Phone", text: $localPhone)
                }
            }
            .formStyle(.grouped)

            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Save Encrypted") {
                    let normalizedSite = MacVaultViewModel.normalizeHttpsUrl(websiteUrl)
                    let digits = localPhone.filter(\.isNumber)
                    let formattedPhone = digits.isEmpty ? "" : "\(dialPrefix) \(digits)"
                    let entry = MacVaultEntry(
                        id: existing?.id ?? "item-\(Int(Date().timeIntervalSince1970 * 1000))",
                        category: category,
                        title: title.isEmpty ? "Untitled Entry" : title,
                        subtitle: category == "BANK_ACCOUNT" ? bankAccountType : category,
                        primaryLabel: category == "BANK_ACCOUNT" ? "IBAN" : "Primary Number",
                        primaryValue: primaryValue,
                        websiteUrl: normalizedSite,
                        phone: formattedPhone,
                        pinned: existing?.pinned ?? false,
                        favorite: existing?.favorite ?? false,
                        isSampleItem: false,
                        updatedAt: Date()
                    )
                    vaultState.saveEntry(entry)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(20)
        .frame(width: 520, height: 470)
        .onAppear {
            if let ex = existing {
                category = ex.category
                title = ex.title
                bankAccountType = ex.subtitle
                primaryValue = ex.primaryValue
                websiteUrl = ex.websiteUrl
                localPhone = ex.phone
            }
        }
    }

    private func verifyHttpsReachability(_ urlString: String) {
        guard let url = URL(string: urlString), url.scheme == "https", let host = url.host else {
            websiteStatus = "⚠ Invalid HTTPS domain"
            return
        }
        websiteStatus = "⏳ Verifying https://\(host)…"
        Task {
            var req = URLRequest(url: url)
            req.httpMethod = "HEAD"
            req.timeoutInterval = 4.5
            do {
                let (_, res) = try await URLSession.shared.data(for: req)
                if let http = res as? HTTPURLResponse, (200..<500).contains(http.statusCode) {
                    websiteStatus = "✓ Live HTTPS website verified (\(host))"
                } else {
                    websiteStatus = "⚠ Website responded with unexpected status (\(host))"
                }
            } catch {
                websiteStatus = "⚠ Website could not be reached (\(host))"
            }
        }
    }
}

// MARK: - Settings, Exclusive Storage Mode, In-App Auto-Updater & Diagnostics

struct MacSettingsAndAboutView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"

    var body: some View {
        Form {
            Section("Exclusive Vault Storage Mode (One or the Other — Never Both)") {
                Picker("Active Vault Mode", selection: Binding(
                    get: { vaultState.storageMode },
                    set: { next in Task { await vaultState.switchExclusiveStorageMode(next) } }
                )) {
                    Text("Local Storage Only (Zero Cloud)").tag(MacExclusiveStorageMode.localOnly)
                    Text("Cloud Sync Only (100% Automatic Offline Support)").tag(MacExclusiveStorageMode.cloudSync)
                }
                .pickerStyle(.radioGroup)

                if vaultState.storageMode == .localOnly {
                    Text("✓ Standalone encrypted SQLCipher vault active (\(vaultState.standaloneLocalRowCount) items) • Cloud mirror rows: 0")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("✓ Cloud Sync authoritative • Standalone local DB purged (0 rows) • Automatic offline mirror (\(vaultState.cloudSessionMirrorCount) items, \(vaultState.pendingOfflineOutboxIds.count) queued)")
                        .font(.caption)
                        .foregroundStyle(.teal)
                }
            }

            Section("Automated In-App macOS Updates (Ed25519 + SHA-256)") {
                Toggle("Automatically download & stage verified updates in-app", isOn: $updater.automaticChecksEnabled)

                HStack(spacing: 10) {
                    Button("Check for Updates…") {
                        updater.checkForUpdatesInitiatedByUser()
                    }
                    .disabled(updater.isChecking || updater.isDownloadingUpdate)

                    if updater.availableDmgUrl != nil && updater.stagedAppBundleURL == nil {
                        Button("Download & Verify DMG") {
                            Task { await updater.downloadVerifyAndStageMacUpdate() }
                        }
                        .disabled(updater.isDownloadingUpdate)
                    }

                    if updater.stagedAppBundleURL != nil {
                        Button("Install & Relaunch") {
                            updater.installStagedUpdateAndRelaunch()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }

                if updater.isDownloadingUpdate {
                    ProgressView(value: updater.downloadProgress)
                }

                if let next = updater.availableVersion {
                    Text("Update Available: v\(next) • SHA-256 Verified")
                        .font(.caption)
                        .foregroundStyle(.green)
                } else if !updater.statusText.isEmpty {
                    Text(updater.statusText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section("Example Data & Appearance") {
                Picker("Theme", selection: $themeMode) {
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                    Text("System").tag("system")
                }
                .pickerStyle(.segmented)

                Button(role: .destructive) {
                    vaultState.deleteAllExampleData()
                } label: {
                    Text("Delete All Example Data (\(vaultState.sampleEntriesCount))")
                }
                .disabled(vaultState.sampleEntriesCount == 0)
            }

            Section("About & Privacy-Scrubbed Diagnostics") {
                LabeledContent("Version", value: updater.currentVersionDisplay)
                LabeledContent("Encryption", value: "Argon2id + Secret Key → AES-256-GCM (SQLCipher)")

                if vaultState.diagnostics.isEmpty {
                    Text("No errors or warnings recorded in this session.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(vaultState.diagnostics.prefix(6)) { entry in
                        Text("[\(entry.operation)] \(entry.scrubbedDetail)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}
