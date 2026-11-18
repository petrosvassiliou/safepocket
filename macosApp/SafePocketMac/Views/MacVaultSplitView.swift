import SwiftUI
import AppKit

/// Native Apple HIG 3-Column NavigationSplitView for SafePocket v0.3.7 (Build 12) with 100% Cross-Platform Parity.
struct MacVaultSplitView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"
    @State private var confirmDeleteSamples = false
    @State private var revealedFieldIds: Set<String> = []
    @State private var unlockPasswordInput: String = ""
    @State private var unlockError: Bool = false

    private func cycleTheme() {
        switch themeMode {
        case "light": themeMode = "dark"
        case "dark": themeMode = "system"
        default: themeMode = "light"
        }
    }

    @ViewBuilder
    private var macNewItemCategoryMenu: some View {
        Menu {
            Button {
                vaultState.openCreateEditor(category: "PAYMENT_CARD")
            } label: {
                Label(vaultState.t("category_payment_card"), systemImage: "creditcard")
            }
            Button {
                vaultState.openCreateEditor(category: "BANK_ACCOUNT")
            } label: {
                Label(vaultState.t("category_bank_account"), systemImage: "building.columns")
            }
            Button {
                vaultState.openCreateEditor(category: "INSURANCE_CARD")
            } label: {
                Label(vaultState.t("category_insurance_card"), systemImage: "cross.case")
            }
            Button {
                vaultState.openCreateEditor(category: "MEMBERSHIP_CARD")
            } label: {
                Label(vaultState.t("category_membership_card"), systemImage: "person.crop.rectangle.stack")
            }
            Button {
                vaultState.openCreateEditor(category: "IDENTITY")
            } label: {
                Label(vaultState.t("category_identity"), systemImage: "person.text.rectangle")
            }
            Button {
                vaultState.openCreateEditor(category: "OFFICIAL_DOCUMENT")
            } label: {
                Label(vaultState.t("category_official_document"), systemImage: "doc.badge.gearshape")
            }
            Button {
                vaultState.openCreateEditor(category: "SECURE_NOTE")
            } label: {
                Label(vaultState.t("category_secure_note"), systemImage: "lock.doc")
            }
        } label: {
            Label(vaultState.t("action_new_item"), systemImage: "plus")
        }
    }

    var body: some View {
        Group {
            if vaultState.isLocked {
                macLockedOverlay
            } else {
                NavigationSplitView {
                    sidebarContent
                } content: {
                    vaultListContent
                } detail: {
                    detailContent
                }
                .navigationSplitViewStyle(.balanced)
                .toolbar {
                    ToolbarItemGroup(placement: .primaryAction) {
                        MacFlagLanguageMenu(vaultState: vaultState)

                        macNewItemCategoryMenu

                        Button {
                            vaultState.presentSettingsSheet = true
                        } label: {
                            Label(vaultState.t("nav_settings"), systemImage: "gearshape")
                        }

                        Button {
                            vaultState.lockVault()
                        } label: {
                            Label(vaultState.t("action_lock_vault"), systemImage: "lock")
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $vaultState.presentSettingsSheet) {
            MacSettingsModalWindow(vaultState: vaultState, updater: updater)
        }
        .sheet(isPresented: $vaultState.presentEditorSheet) {
            MacSmartItemEditorSheet(vaultState: vaultState, existing: vaultState.editingEntry)
        }
        .confirmationDialog(
            vaultState.t("delete_example_data_confirm", ["count": String(vaultState.sampleEntriesCount)]),
            isPresented: $confirmDeleteSamples
        ) {
            Button(vaultState.t("action_delete_example_data", ["count": String(vaultState.sampleEntriesCount)]), role: .destructive) {
                vaultState.deleteAllExampleData()
            }
            Button(vaultState.t("action_cancel"), role: .cancel) {}
        }
    }

    // MARK: - Lock Screen Overlay (Touch ID + Master Password + Flag Language Switcher)

    private var macLockedOverlay: some View {
        VStack(spacing: 16) {
            HStack {
                Spacer()
                MacFlagLanguageMenu(vaultState: vaultState)
            }
            .frame(maxWidth: 360)

            Image(systemName: "lock.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(vaultState.materialAccent.swiftUIColor)

            Text(vaultState.t("app_name"))
                .font(.title.weight(.bold))

            Text(vaultState.t("app_tagline"))
                .font(.subheadline)
                .foregroundStyle(.secondary)

            SecureField(vaultState.t("security_master_password"), text: $unlockPasswordInput)
                .textFieldStyle(.roundedBorder)
                .frame(width: 280)
                .onSubmit {
                    if vaultState.unlockWithMasterPassword(unlockPasswordInput) {
                        unlockPasswordInput = ""
                        unlockError = false
                    } else {
                        unlockError = true
                    }
                }

            if unlockError {
                Text(vaultState.t("security_invalid_master_password"))
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack(spacing: 10) {
                Button {
                    if vaultState.unlockWithMasterPassword(unlockPasswordInput) {
                        unlockPasswordInput = ""
                        unlockError = false
                    } else {
                        unlockError = true
                    }
                } label: {
                    Text(vaultState.t("action_unlock_vault"))
                        .frame(minWidth: 120)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    Task { _ = await vaultState.unlockWithTouchID() }
                } label: {
                    Label("Touch ID", systemImage: "touchid")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(36)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.ultraThinMaterial)
    }

    // MARK: - Column 1: Native AppKit-Aligned Sidebar (Root List + SafeAreaInset Footer)

    private var sidebarContent: some View {
        List(selection: $vaultState.selectedCategory) {
            Section {
                Label(vaultState.t("nav_all_items"), systemImage: "tray.full")
                    .badge(vaultState.entries.count)
                    .tag("all")
                Label(vaultState.t("nav_favorites"), systemImage: "star")
                    .tag("favorites")
            }
            Section {
                Label(vaultState.t("category_payment_card"), systemImage: "creditcard")
                    .tag("PAYMENT_CARD")
                Label(vaultState.t("category_bank_account"), systemImage: "building.columns")
                    .tag("BANK_ACCOUNT")
                Label(vaultState.t("category_insurance_card"), systemImage: "cross.case")
                    .tag("INSURANCE_CARD")
                Label(vaultState.t("category_membership_card"), systemImage: "person.crop.rectangle.stack")
                    .tag("MEMBERSHIP_CARD")
                Label(vaultState.t("category_identity"), systemImage: "person.text.rectangle")
                    .tag("IDENTITY")
                Label(vaultState.t("category_official_document"), systemImage: "doc.badge.gearshape")
                    .tag("OFFICIAL_DOCUMENT")
                Label(vaultState.t("category_secure_note"), systemImage: "lock.doc")
                    .tag("SECURE_NOTE")
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(alignment: .leading, spacing: 5) {
                Divider()
                HStack(alignment: .center, spacing: 6) {
                    Button {
                        cycleTheme()
                    } label: {
                        Image(systemName: themeMode == "dark" ? "moon.stars.fill" : (themeMode == "system" ? "circle.lefthalf.filled" : "sun.max.fill"))
                            .font(.system(size: 12.5, weight: .semibold))
                            .frame(width: 20, height: 18, alignment: .center)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help(vaultState.t("settings_theme_title"))

                    MacFlagLanguageMenu(vaultState: vaultState)
                        .controlSize(.small)

                    Button {
                        vaultState.lockVault()
                    } label: {
                        HStack(alignment: .center, spacing: 5) {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 11.5, weight: .semibold))
                            Text(vaultState.t("action_lock_vault"))
                                .lineLimit(1)
                        }
                        .frame(minHeight: 18, alignment: .center)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    Spacer(minLength: 0)
                }
                .padding(.leading, 8)
                .padding(.trailing, 10)
                .padding(.top, 5)

                HStack(spacing: 5) {
                    Image(systemName: "checkmark.shield.fill")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 10))
                    Text(updater.currentVersionDisplay)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.leading, 8)
                .padding(.trailing, 10)
                .padding(.bottom, 6)
            }
            .background(.bar)
        }
        .navigationSplitViewColumnWidth(min: 230, ideal: 252, max: 310)
    }

    // MARK: - Column 2: Native Inset Vault List with Aligned Toolbar Title & Banners

    private var hasListTopBanners: Bool {
        vaultState.lastErrorBanner != nil || vaultState.statusNotice != nil || vaultState.sampleEntriesCount > 0
    }

    private var vaultListContent: some View {
        List(selection: $vaultState.selectedEntryId) {
            ForEach(vaultState.filteredEntries) { entry in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        if entry.pinned {
                            Image(systemName: "pin.fill")
                                .font(.caption2)
                                .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                        }
                        if entry.favorite {
                            Image(systemName: "star.fill")
                                .font(.caption2)
                                .foregroundStyle(.yellow)
                        }
                        Text(entry.title)
                            .font(.body.weight(.semibold))
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        if let days = entry.daysUntilExpiry(), days <= vaultState.expiryWindowDays {
                            Text(days < 0 ? vaultState.t("badge_expired") : "\(days)d")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background((days < 0 ? Color.red : Color.orange).opacity(0.18), in: Capsule())
                                .foregroundStyle(days < 0 ? .red : .orange)
                        }
                    }
                    Text(vaultState.displaySubtitle(for: entry))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.vertical, 4)
                .tag(entry.id)
                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                    Button {
                        vaultState.togglePin(entry.id)
                    } label: {
                        Label(entry.pinned ? vaultState.t("context_unpin") : vaultState.t("context_pin"), systemImage: "pin.fill")
                    }
                    .tint(vaultState.materialAccent.swiftUIColor)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) {
                        vaultState.deleteEntry(entry.id)
                    } label: {
                        Label(vaultState.t("action_delete"), systemImage: "trash")
                    }
                    .tint(.red)
                    Button {
                        vaultState.openEditEditor(entry)
                    } label: {
                        Label(vaultState.t("action_edit"), systemImage: "pencil")
                    }
                    .tint(.blue)
                    Button {
                        vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                    } label: {
                        Label(vaultState.t("action_copy"), systemImage: "doc.on.doc")
                    }
                    .tint(.indigo)
                }
                .contextMenu {
                    Button {
                        vaultState.selectedEntryId = entry.id
                        vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                    } label: {
                        Label(vaultState.t("context_copy_primary"), systemImage: "doc.on.doc")
                    }
                    Button {
                        vaultState.selectedEntryId = entry.id
                        vaultState.openEditEditor(entry)
                    } label: {
                        Label(vaultState.t("action_edit"), systemImage: "pencil")
                    }
                    Button {
                        vaultState.selectedEntryId = entry.id
                        vaultState.togglePin(entry.id)
                    } label: {
                        Label(entry.pinned ? vaultState.t("context_unpin") : vaultState.t("context_pin"), systemImage: "pin")
                    }
                    Button {
                        vaultState.selectedEntryId = entry.id
                        vaultState.toggleFavorite(entry.id)
                    } label: {
                        Label(vaultState.t("nav_favorites"), systemImage: "star")
                    }
                    Button {
                        vaultState.selectedEntryId = entry.id
                        vaultState.duplicateEntry(entry.id)
                    } label: {
                        Label(vaultState.t("context_duplicate"), systemImage: "plus.square.on.square")
                    }
                    Divider()
                    Button(role: .destructive) {
                        vaultState.deleteEntry(entry.id)
                    } label: {
                        Label(vaultState.t("action_delete"), systemImage: "trash")
                    }
                }
            }
        }
        .listStyle(.inset)
        .navigationTitle(vaultState.selectedCategoryTitle)
        .searchable(text: $vaultState.searchQuery, placement: .toolbar, prompt: vaultState.t("search_placeholder"))
        .safeAreaInset(edge: .top, spacing: 0) {
            if hasListTopBanners {
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
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.orange.opacity(0.14))
                    }

                    if let notice = vaultState.statusNotice {
                        HStack(spacing: 8) {
                            Text(notice)
                                .font(.caption)
                                .lineLimit(1)
                            Spacer()
                            Button("✕") {
                                vaultState.statusNotice = nil
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(vaultState.materialAccent.swiftUIColor.opacity(0.12))
                    }

                    if vaultState.sampleEntriesCount > 0 {
                        HStack(spacing: 8) {
                            Text(vaultState.t("action_delete_example_data", ["count": String(vaultState.sampleEntriesCount)]))
                                .font(.caption.weight(.medium))
                                .lineLimit(1)
                            Spacer()
                            Button(role: .destructive) {
                                confirmDeleteSamples = true
                            } label: {
                                Text(vaultState.t("action_delete"))
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.red.opacity(0.06))
                    }
                    Divider()
                }
            }
        }
        .navigationSplitViewColumnWidth(min: 280, ideal: 328, max: 420)
    }

    // MARK: - Column 3: Aligned Detail Inspector + Slim Non-Overlapping Bottom Status Bar

    private var macBottomStatusFooter: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                Spacer()
                Button {
                    if vaultState.storageMode == .cloudSync && vaultState.isCloudAuthenticated {
                        Task { await vaultState.reconcileCloudSessionAutomatically() }
                    } else {
                        vaultState.presentSettingsSheet = true
                    }
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: vaultState.syncPillSystemImage)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(
                                vaultState.storageMode == .cloudSync
                                    ? (vaultState.isCloudAuthenticated ? .green : .orange)
                                    : vaultState.materialAccent.swiftUIColor
                            )
                        Text(vaultState.syncPillLabel)
                            .font(.system(size: 10.5, weight: .medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.045), in: Capsule())
                    .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.75))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 5)
            .background(.bar)
        }
    }

    @ViewBuilder
    private var detailContent: some View {
        Group {
            if let selected = vaultState.entries.first(where: { $0.id == vaultState.selectedEntryId }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        macVisualBrandCard(for: selected)

                        HStack(alignment: .center, spacing: 8) {
                            Button(vaultState.t("action_edit")) {
                                vaultState.openEditEditor(selected)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.regular)

                            Button(vaultState.t("context_duplicate")) {
                                vaultState.duplicateEntry(selected.id)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.regular)

                            Button(selected.pinned ? vaultState.t("context_unpin") : vaultState.t("context_pin")) {
                                vaultState.togglePin(selected.id)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.regular)

                            Spacer()

                            Button(role: .destructive) {
                                vaultState.deleteEntry(selected.id)
                            } label: {
                                Text(vaultState.t("action_delete"))
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.regular)
                        }

                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(selected.fields.filter { !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) { field in
                                let label = vaultState.resolveFieldLabel(
                                    key: field.labelKey,
                                    isCustom: field.isCustomLabel,
                                    category: selected.category
                                )
                                let isRevealed = !field.isSensitive || revealedFieldIds.contains(field.id)

                                HStack(alignment: .center, spacing: 14) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(label)
                                            .font(.system(size: 11.5, weight: .semibold))
                                            .foregroundStyle(.secondary)
                                        Text(isRevealed ? field.value : String(repeating: "•", count: min(14, max(6, field.value.count))))
                                            .font(.system(size: 13.5, weight: .medium, design: .monospaced))
                                            .foregroundStyle(.primary)
                                            .textSelection(.enabled)
                                    }

                                    Spacer(minLength: 12)

                                    HStack(spacing: 8) {
                                        if field.isSensitive {
                                            Button(isRevealed ? vaultState.t("action_hide") : vaultState.t("action_reveal")) {
                                                if revealedFieldIds.contains(field.id) {
                                                    revealedFieldIds.remove(field.id)
                                                } else {
                                                    revealedFieldIds.insert(field.id)
                                                }
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.small)
                                        }
                                        Button(vaultState.t("action_copy")) {
                                            vaultState.copyFieldWithClipboardHygiene(field.value, isSensitive: field.isSensitive)
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                                .background(
                                    Color(nsColor: .controlBackgroundColor),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .strokeBorder(Color.primary.opacity(0.09), lineWidth: 1)
                                )
                                .contextMenu {
                                    Button("\(vaultState.t("action_copy")) \(label)") {
                                        vaultState.copyFieldWithClipboardHygiene(field.value, isSensitive: field.isSensitive)
                                    }
                                }
                            }
                        }
                    }
                    .frame(maxWidth: 760, alignment: .leading)
                    .padding(.horizontal, 28)
                    .padding(.top, 22)
                    .padding(.bottom, 32)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                }
            } else {
                VStack(spacing: 12) {
                    Text(vaultState.t("app_name"))
                        .font(.title3.weight(.semibold))
                    macNewItemCategoryMenu
                        .controlSize(.large)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            macBottomStatusFooter
        }
    }

    @ViewBuilder
    private func macVisualBrandCard(for entry: MacVaultEntry) -> some View {
        let inst = MacSmartIntelligence.resolveInstitution(for: entry)
        let baseColor = MacSmartIntelligence.colorFromHex(entry.customColorHex ?? inst?.primaryHex)
            ?? vaultState.materialAccent.swiftUIColor
        let networkBadge = MacSmartIntelligence.resolveNetworkBadge(for: entry)

        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center) {
                HStack(spacing: 10) {
                    if vaultState.autoDownloadLogos,
                       let domain = inst?.domain ?? URL(string: entry.websiteUrl)?.host,
                       let logoURL = URL(string: "https://www.google.com/s2/favicons?domain=\(domain)&sz=64") {
                        AsyncImage(url: logoURL) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFit().frame(width: 28, height: 28)
                                    .padding(4)
                                    .background(.white, in: RoundedRectangle(cornerRadius: 8))
                            } else {
                                Text(inst?.monogram ?? String(entry.title.prefix(2)).uppercased())
                                    .font(.caption.weight(.bold))
                                    .padding(8)
                                    .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    } else {
                        Text(inst?.monogram ?? String(entry.title.prefix(2)).uppercased())
                            .font(.caption.weight(.bold))
                            .padding(8)
                            .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 8))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.title)
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text(vaultState.displaySubtitle(for: entry))
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.85))
                    }
                }
                Spacer()
                Text(networkBadge)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.22), in: Capsule())
                    .foregroundStyle(.white)
            }

            if !entry.primaryValue.isEmpty {
                Text(entry.primaryValue)
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .foregroundStyle(.white)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [baseColor, baseColor.opacity(0.76)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 16)
        )
    }
}

// MARK: - Main-App Country-Flag Language Selector & Modal Settings Window

struct MacFlagLanguageMenu: View {
    @ObservedObject var vaultState: MacVaultViewModel

    private var activeFlagLabel: String {
        if vaultState.localeOverrideCode == "__system__" || vaultState.localeOverrideCode.isEmpty {
            return "🌐 \(vaultState.activeLocale.flagEmoji) \(vaultState.activeLocale.rawValue.uppercased())"
        }
        return "\(vaultState.activeLocale.flagEmoji) \(vaultState.activeLocale.rawValue.uppercased())"
    }

    var body: some View {
        Menu {
            Button {
                vaultState.localeOverrideCode = "__system__"
            } label: {
                let sys = MacLocalizationEngine.detectedSystemLocale
                Text("🌐 \(vaultState.t("settings_language_system", ["locale": "\(sys.flagEmoji) \(sys.nativeDisplayName)"]))")
            }

            Divider()

            ForEach(MacSupportedLocale.allCases) { loc in
                Button {
                    vaultState.localeOverrideCode = loc.rawValue
                } label: {
                    let check = (!vaultState.localeOverrideCode.isEmpty && vaultState.localeOverrideCode != "__system__" && vaultState.activeLocale == loc) ? "✓ " : ""
                    Text("\(check)\(loc.flagEmoji) \(loc.nativeDisplayName) (\(loc.rawValue.uppercased()))")
                }
            }
        } label: {
            Text(activeFlagLabel)
                .font(.system(size: 11.5, weight: .semibold))
        }
        .menuStyle(.borderedButton)
        .fixedSize()
        .help(vaultState.t("settings_language_title"))
    }
}

struct MacSettingsModalWindow: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "gearshape.fill")
                    .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                Text(vaultState.t("nav_settings"))
                    .font(.title3.weight(.bold))
                Spacer()
                MacFlagLanguageMenu(vaultState: vaultState)
            }
            .padding(.horizontal, 22)
            .padding(.vertical, 14)
            .background(.regularMaterial)

            Divider()

            MacSettingsAndAboutView(vaultState: vaultState, updater: updater)

            Divider()

            HStack {
                Spacer()
                Button(vaultState.t("action_done")) {
                    vaultState.presentSettingsSheet = false
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 22)
            .padding(.top, 12)
            .padding(.bottom, 18)
            .background(.regularMaterial)
        }
        .frame(minWidth: 760, idealWidth: 780, minHeight: 680, idealHeight: 720)
    }
}

// MARK: - Native Bordered macOS Input & Card Primitives Across All Views

struct MacSectionCard<Content: View>: View {
    let title: String
    var systemImage: String? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                }
                Text(title)
                    .font(.system(size: 13.5, weight: .bold))
                    .foregroundStyle(.primary)
            }

            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
            )
        }
    }
}

struct MacLabeledTextField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var onSubmitAction: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.primary)
            }
            TextField("", text: $text, prompt: Text(placeholder))
                .labelsHidden()
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .onSubmit {
                    onSubmitAction?()
                }
        }
    }
}

struct MacLabeledSecureField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var onSubmitAction: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.primary)
            }
            SecureField("", text: $text, prompt: Text(placeholder))
                .labelsHidden()
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .onSubmit {
                    onSubmitAction?()
                }
        }
    }
}

// MARK: - Full Multi-Field Smart Item Editor Sheet (All 7 Categories + Custom Fields — Category-Free Dialog)

struct MacSmartItemEditorSheet: View {
    @ObservedObject var vaultState: MacVaultViewModel
    let existing: MacVaultEntry?
    @Environment(\.dismiss) private var dismiss

    @State private var category: String = "PAYMENT_CARD"
    @State private var title: String = ""
    @State private var subtitle: String = ""
    @State private var customColorHex: String = ""
    @State private var tagsInput: String = ""
    @State private var fieldDrafts: [String: String] = [:]
    @State private var dialPrefixesByKey: [String: String] = [:]
    @State private var customFields: [MacVaultField] = []
    @State private var websiteStatus: String = ""
    @State private var autofillNotice: String = ""
    @FocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 10) {
                Text(existing == nil ? vaultState.t("action_new_item") : vaultState.t("action_edit"))
                    .font(.title3.weight(.bold))
                Spacer()
                Text(vaultState.categorySingularName(category))
                    .font(.system(size: 11.5, weight: .semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(vaultState.materialAccent.swiftUIColor.opacity(0.14), in: Capsule())
                    .foregroundStyle(vaultState.materialAccent.swiftUIColor)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    MacSectionCard(title: vaultState.t("app_name")) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(vaultState.t("editor_title_label"))
                                .font(.system(size: 12, weight: .semibold))
                            TextField("", text: $title, prompt: Text("Deutsche Bank, ING, Visa, Allianz…"))
                                .labelsHidden()
                                .textFieldStyle(.roundedBorder)
                                .controlSize(.large)
                                .focused($titleFocused)
                        }

                        MacLabeledTextField(
                            label: vaultState.t("editor_subtitle_label"),
                            placeholder: vaultState.categorySingularName(category),
                            text: $subtitle
                        )

                        HStack(spacing: 12) {
                            MacLabeledTextField(
                                label: vaultState.t("editor_brand_color_hex"),
                                placeholder: "#0018A8",
                                text: $customColorHex
                            )
                            MacLabeledTextField(
                                label: vaultState.t("editor_tags_label"),
                                placeholder: "banking, personal",
                                text: $tagsInput
                            )
                        }
                    }

                    MacSectionCard(title: vaultState.t("editor_section_structured_fields")) {
                        ForEach(MacCategoryTemplates.specs(for: category), id: \.key) { spec in
                            smartEditorRow(for: spec)
                        }
                    }

                    MacSectionCard(title: vaultState.t("editor_section_custom_fields")) {
                        ForEach($customFields) { $cf in
                            HStack(spacing: 8) {
                                TextField("", text: $cf.labelKey, prompt: Text(vaultState.t("editor_field_label_placeholder")))
                                    .labelsHidden()
                                    .textFieldStyle(.roundedBorder)
                                    .controlSize(.large)
                                    .frame(width: 170)
                                TextField("", text: $cf.value, prompt: Text(vaultState.t("editor_field_value_placeholder")))
                                    .labelsHidden()
                                    .textFieldStyle(.roundedBorder)
                                    .controlSize(.large)
                                Toggle(vaultState.t("editor_field_secret_toggle"), isOn: $cf.isSensitive)
                                    .toggleStyle(.checkbox)
                                Button(role: .destructive) {
                                    customFields.removeAll { $0.id == cf.id }
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        Button(vaultState.t("editor_add_custom_field")) {
                            customFields.append(
                                MacVaultField(
                                    id: "cf-\(UUID().uuidString.prefix(8))",
                                    labelKey: vaultState.t("editor_field_label_placeholder"),
                                    isCustomLabel: true,
                                    value: ""
                                )
                            )
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.trailing, 4)
            }

            if !autofillNotice.isEmpty {
                Text("✨ \(autofillNotice)")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(vaultState.materialAccent.swiftUIColor)
            }

            Divider()

            HStack(spacing: 10) {
                Spacer()
                Button(vaultState.t("action_cancel")) { dismiss() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                Button(vaultState.t("action_save")) {
                    saveConstructedEntry()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 22)
        .padding(.top, 20)
        .padding(.bottom, 22)
        .frame(width: 680, height: 660)
        .onAppear {
            NSApplication.shared.activate(ignoringOtherApps: true)
            if let ex = existing {
                category = ex.category
                title = ex.title
                subtitle = vaultState.displaySubtitle(for: ex)
                customColorHex = ex.customColorHex ?? ""
                tagsInput = ex.tags.joined(separator: ", ")
                for f in ex.fields {
                    if f.isCustomLabel {
                        customFields.append(f)
                    } else {
                        fieldDrafts[f.labelKey] = f.value
                    }
                }
            } else {
                category = vaultState.pendingCreateCategory
                if category == "BANK_ACCOUNT" {
                    fieldDrafts["field_currency"] = "EUR"
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                titleFocused = true
            }
        }
    }

    @ViewBuilder
    private func smartEditorRow(for spec: MacFieldTemplateSpec) -> some View {
        let label = vaultState.resolveFieldLabel(key: spec.key, isCustom: false, category: category)
        let binding = Binding<String>(
            get: { fieldDrafts[spec.key] ?? "" },
            set: { next in handleSmartInput(spec: spec, newValue: next) }
        )

        if let options = MacSmartIntelligence.dropdownOptions(
            for: spec.key,
            category: category,
            i18n: vaultState.i18n,
            localeCode: vaultState.localeOverrideCode
        ) {
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                Picker("", selection: Binding<String>(
                    get: {
                        let cur = fieldDrafts[spec.key] ?? ""
                        return options.contains(cur) ? cur : (options.first ?? "")
                    },
                    set: { selected in fieldDrafts[spec.key] = selected }
                )) {
                    ForEach(options, id: \.self) { opt in
                        Text(opt).tag(opt)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else if spec.valueType == .phone {
            let prefixBinding = Binding<String>(
                get: { dialPrefixesByKey[spec.key] ?? "+49" },
                set: { nextPrefix in
                    dialPrefixesByKey[spec.key] = nextPrefix
                    let cur = fieldDrafts[spec.key] ?? ""
                    if !cur.isEmpty {
                        fieldDrafts[spec.key] = MacSmartIntelligence.formatInternationalPhone(cur, dialPrefix: nextPrefix)
                    }
                }
            )
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                HStack(spacing: 8) {
                    Picker("", selection: prefixBinding) {
                        ForEach(MacSmartIntelligence.dialPrefixes, id: \.self) { p in
                            Text(p).tag(p)
                        }
                    }
                    .labelsHidden()
                    .frame(width: 96)

                    TextField("", text: binding, prompt: Text(spec.placeholder))
                        .labelsHidden()
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                }
            }
        } else if spec.valueType == .url {
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                HStack(spacing: 8) {
                    TextField("", text: binding, prompt: Text(spec.placeholder))
                        .labelsHidden()
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                    Button(vaultState.t("editor_verify_https")) {
                        let normalized = MacVaultViewModel.normalizeHttpsUrl(fieldDrafts[spec.key] ?? "")
                        fieldDrafts[spec.key] = normalized
                        verifyHttpsReachability(normalized)
                    }
                    .buttonStyle(.bordered)
                }
                if !websiteStatus.isEmpty {
                    Text(websiteStatus)
                        .font(.caption)
                        .foregroundStyle(websiteStatus.hasPrefix("✓") ? .green : .orange)
                }
            }
        } else if spec.valueType == .sensitiveSecret {
            MacLabeledSecureField(label: label, placeholder: spec.placeholder, text: binding)
        } else {
            MacLabeledTextField(label: label, placeholder: spec.placeholder, text: binding)
        }
    }

    private func handleSmartInput(spec: MacFieldTemplateSpec, newValue: String) {
        switch spec.valueType {
        case .iban:
            let info = MacSmartIntelligence.formatIbanAndInfer(newValue)
            fieldDrafts[spec.key] = info.formatted
            if let routing = info.routing, (fieldDrafts["field_routing_number"] ?? "").isEmpty {
                fieldDrafts["field_routing_number"] = routing
            }
            if let account = info.account, (fieldDrafts["field_account_number"] ?? "").isEmpty {
                fieldDrafts["field_account_number"] = account
            }
            if let bank = info.bank {
                if (fieldDrafts["field_issuing_bank"] ?? "").isEmpty {
                    fieldDrafts["field_issuing_bank"] = bank.displayName
                }
                if (fieldDrafts["field_bic"] ?? "").isEmpty && !bank.defaultBic.isEmpty {
                    fieldDrafts["field_bic"] = bank.defaultBic
                }
                if (fieldDrafts["field_institution_domain"] ?? "").isEmpty {
                    fieldDrafts["field_institution_domain"] = "https://www.\(bank.domain)"
                }
                if title.isEmpty { title = bank.displayName }
                if customColorHex.isEmpty { customColorHex = bank.primaryHex }
                autofillNotice = vaultState.t("smart_iban_autofilled", ["bank": bank.displayName, "bic": bank.defaultBic])
            }
        case .cardNumber:
            let res = MacSmartIntelligence.formatCardPan(newValue)
            fieldDrafts[spec.key] = res.formatted
            if !res.network.isEmpty && (fieldDrafts["field_card_network"] ?? "").isEmpty {
                fieldDrafts["field_card_network"] = res.network
            }
        case .phone:
            let pref = dialPrefixesByKey[spec.key] ?? "+49"
            fieldDrafts[spec.key] = MacSmartIntelligence.formatInternationalPhone(newValue, dialPrefix: pref)
        default:
            fieldDrafts[spec.key] = newValue
        }
    }

    private func verifyHttpsReachability(_ urlString: String) {
        guard let url = URL(string: urlString), url.scheme == "https", let host = url.host else {
            websiteStatus = "⚠ \(vaultState.t("website_status_unreachable", ["hostname": urlString]))"
            return
        }
        websiteStatus = "⏳ \(vaultState.t("website_status_checking", ["hostname": host]))"
        Task {
            var req = URLRequest(url: url)
            req.httpMethod = "HEAD"
            req.timeoutInterval = 4.5
            do {
                let (_, res) = try await URLSession.shared.data(for: req)
                if let http = res as? HTTPURLResponse, (200..<500).contains(http.statusCode) {
                    websiteStatus = "✓ \(vaultState.t("website_status_reachable", ["hostname": host]))"
                } else {
                    websiteStatus = "⚠ \(vaultState.t("website_status_unreachable", ["hostname": host]))"
                }
            } catch {
                websiteStatus = "⚠ \(vaultState.t("website_status_unreachable", ["hostname": host]))"
            }
        }
    }

    private func saveConstructedEntry() {
        var builtFields: [MacVaultField] = []
        for spec in MacCategoryTemplates.specs(for: category) {
            let val = (fieldDrafts[spec.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !val.isEmpty {
                builtFields.append(
                    MacVaultField(
                        id: "f-\(spec.key)",
                        labelKey: spec.key,
                        isCustomLabel: false,
                        value: spec.valueType == .url ? MacVaultViewModel.normalizeHttpsUrl(val) : val,
                        valueType: spec.valueType,
                        isSensitive: spec.sensitive
                    )
                )
            }
        }
        builtFields.append(contentsOf: customFields.filter { !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        let defaultTypeSub = fieldDrafts["field_account_type"]
            ?? fieldDrafts["field_card_type"]
            ?? fieldDrafts["field_insurance_type"]
            ?? fieldDrafts["field_document_subtype"]
        let cleanSub = subtitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedSub = cleanSub.isEmpty
            ? ((defaultTypeSub?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
                ? defaultTypeSub!
                : vaultState.categorySingularName(category))
            : vaultState.i18n.resolveDisplaySubtitle(
                subtitle: cleanSub,
                category: category,
                localeCode: vaultState.localeOverrideCode
            )
        let tags = tagsInput.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let entry = MacVaultEntry(
            id: existing?.id ?? "item-\(Int(Date().timeIntervalSince1970 * 1000))",
            category: category,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? vaultState.t("editor_untitled_entry") : title,
            subtitle: resolvedSub,
            fields: builtFields,
            tags: tags,
            customColorHex: customColorHex.isEmpty ? nil : customColorHex,
            networkBrand: fieldDrafts["field_card_network"],
            pinned: existing?.pinned ?? false,
            favorite: existing?.favorite ?? false,
            isSampleItem: false,
            updatedAt: Date()
        )
        vaultState.saveEntry(entry)
        dismiss()
    }
}

// MARK: - Full Cross-Platform Parity Settings View (Explicit Bordered Inputs + Always-Active Buttons + Inline Validation)

struct MacSettingsAndAboutView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"

    private enum SettingsInputFocus: Hashable {
        case cloudEmail
        case cloudPassword
        case gdriveToken
        case backupPassword
        case masterPassword
    }

    @FocusState private var focusedInput: SettingsInputFocus?

    @State private var cloudEmailInput: String = ""
    @State private var cloudPasswordInput: String = ""
    @State private var cloudValidationMessage: String?
    @State private var showEndpointAdvanced: Bool = false

    @State private var newMasterPasswordInput: String = ""
    @State private var pairSecretKeyInput: String = ""
    @State private var securityValidationMessage: String?

    @State private var backupPasswordInput: String = ""
    @State private var restoreSecretKeyInput: String = ""
    @State private var plainExportConfirmPassword: String = ""
    @State private var backupValidationMessage: String?

    private func triggerCloudAuth(isSignUp: Bool) {
        let cleanEmail = cloudEmailInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanEmail.contains("@"), cleanEmail.contains(".") else {
            cloudValidationMessage = "⚠ \(vaultState.t("cloud_auth_email")): name@example.com"
            focusedInput = .cloudEmail
            return
        }
        guard cloudPasswordInput.count >= 6 else {
            cloudValidationMessage = "⚠ \(vaultState.t("cloud_auth_password")): min. 6 characters"
            focusedInput = .cloudPassword
            return
        }
        cloudValidationMessage = nil
        let pw = cloudPasswordInput
        Task {
            if isSignUp {
                await vaultState.createFirebaseAccount(email: cleanEmail, password: pw)
            } else {
                await vaultState.signInFirebaseAccount(email: cleanEmail, password: pw)
            }
            if vaultState.cloudSession != nil {
                cloudPasswordInput = ""
            }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let err = vaultState.lastErrorBanner {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text(err)
                            .font(.system(size: 12, weight: .medium))
                            .textSelection(.enabled)
                        Spacer()
                        Button("✕") {
                            vaultState.lastErrorBanner = nil
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(10)
                    .background(Color.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
                }

                if let notice = vaultState.statusNotice {
                    HStack(spacing: 8) {
                        Text(notice)
                            .font(.system(size: 12, weight: .medium))
                        Spacer()
                        Button("✕") {
                            vaultState.statusNotice = nil
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(10)
                    .background(vaultState.materialAccent.swiftUIColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                }

                // 1. APPEARANCE, ACCENT COLOR & 6-LANGUAGE SELECTOR (WITH COUNTRY FLAGS)
                MacSectionCard(title: vaultState.t("settings_theme_title") + " & " + vaultState.t("settings_language_title")) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(vaultState.t("settings_theme_title"))
                            .font(.system(size: 12, weight: .semibold))
                        Picker("", selection: $themeMode) {
                            Text(vaultState.t("theme_light")).tag("light")
                            Text(vaultState.t("theme_dark")).tag("dark")
                            Text(vaultState.t("theme_system")).tag("system")
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(vaultState.t("settings_material_color_title"))
                            .font(.system(size: 12, weight: .semibold))
                        Picker("", selection: $vaultState.materialAccent) {
                            ForEach(MacMaterialAccent.allCases) { accent in
                                Text(vaultState.t(accent.labelKey)).tag(accent)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }

                    HStack {
                        Text(vaultState.t("settings_language_title"))
                            .font(.system(size: 12, weight: .semibold))
                        Spacer()
                        Picker("", selection: $vaultState.localeOverrideCode) {
                            let sys = MacLocalizationEngine.detectedSystemLocale
                            Text("🌐 " + vaultState.t("settings_language_system", ["locale": "\(sys.flagEmoji) \(sys.nativeDisplayName)"]))
                                .tag("__system__")
                            ForEach(MacSupportedLocale.allCases) { loc in
                                Text("\(loc.flagEmoji) \(loc.nativeDisplayName) (\(loc.rawValue.uppercased()))")
                                    .tag(loc.rawValue)
                            }
                        }
                        .labelsHidden()
                        .frame(maxWidth: 310)
                    }
                }

                // 2. VAULT STORAGE MODE (2-COLUMN LOKALE DB VS CLOUD-SYNC CARDS) + CLOUD SYNC CONFIGURATION
                MacSectionCard(title: vaultState.t("storage_mode_section_title"), systemImage: "cylinder.split.1x2") {
                    HStack(spacing: 12) {
                        MacStorageModeSelectionCard(
                            systemImage: "icloud.slash",
                            title: vaultState.t("storage_mode_local_only_title"),
                            subtitle: vaultState.t("storage_mode_local_only_desc"),
                            selected: vaultState.storageMode == .localOnly
                        ) {
                            Task {
                                await vaultState.switchExclusiveStorageMode(.localOnly)
                            }
                        }

                        MacStorageModeSelectionCard(
                            systemImage: "checkmark.icloud",
                            title: vaultState.t("storage_mode_cloud_only_title"),
                            subtitle: vaultState.t("storage_mode_cloud_only_desc"),
                            selected: vaultState.storageMode == .cloudSync
                        ) {
                            Task {
                                await vaultState.switchExclusiveStorageMode(.cloudSync)
                                if !vaultState.isCloudAuthenticated {
                                    focusedInput = .cloudEmail
                                }
                            }
                        }
                    }

                    if vaultState.storageMode == .localOnly {
                        Text("✓ Encrypted Local Vault (\(vaultState.standaloneLocalRowCount) items)" + (vaultState.isCloudAuthenticated ? " • Cloud-Sync paused (session saved)" : ""))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                    } else {
                        if !vaultState.isCloudAuthenticated {
                            Text(vaultState.t("storage_mode_cloud_auth_required"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Divider()

                        Picker("", selection: $vaultState.cloudProvider) {
                            Text(vaultState.t("sync_provider_firebase")).tag(MacCloudProvider.firebase)
                            Text(vaultState.t("sync_provider_gdrive")).tag(MacCloudProvider.googleDrive)
                        }
                        .pickerStyle(.radioGroup)
                        .labelsHidden()

                        Divider()

                        if vaultState.cloudProvider == .firebase {
                            if let session = vaultState.cloudSession {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("✓ " + vaultState.t("cloud_auth_signed_in_as", ["email": session.email, "uid": String(session.uid.prefix(12))]))
                                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                        .foregroundStyle(.green)

                                    Text("Offline Mirror: \(vaultState.cloudSessionMirrorCount) items • Outbox: \(vaultState.pendingOfflineOutboxIds.count) queued • ↑\(vaultState.lastSyncPushed) ↓\(vaultState.lastSyncPulled)")
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(.secondary)

                                    HStack(spacing: 10) {
                                        Button {
                                            Task { await vaultState.reconcileCloudSessionAutomatically() }
                                        } label: {
                                            Label(vaultState.t("sync_now"), systemImage: "arrow.triangle.2.circlepath")
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(vaultState.isReconcilingCloud)

                                        Button(vaultState.t("cloud_auth_signout_btn")) {
                                            Task { await vaultState.signOutCloudAccount() }
                                        }
                                        .buttonStyle(.bordered)
                                        .disabled(vaultState.cloudAuthBusy)
                                    }
                                }
                            } else {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(vaultState.t("storage_mode_cloud_auth_required"))
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(vaultState.materialAccent.swiftUIColor)

                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(vaultState.t("cloud_auth_email"))
                                            .font(.system(size: 12, weight: .semibold))
                                        TextField("", text: $cloudEmailInput, prompt: Text("name@example.com"))
                                            .labelsHidden()
                                            .textFieldStyle(.roundedBorder)
                                            .controlSize(.large)
                                            .textContentType(.emailAddress)
                                            .focused($focusedInput, equals: .cloudEmail)
                                            .onChange(of: cloudEmailInput) { _, _ in cloudValidationMessage = nil }
                                            .onSubmit { focusedInput = .cloudPassword }
                                    }

                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(vaultState.t("cloud_auth_password"))
                                            .font(.system(size: 12, weight: .semibold))
                                        SecureField("", text: $cloudPasswordInput, prompt: Text("•••••••••••• (min. 6)"))
                                            .labelsHidden()
                                            .textFieldStyle(.roundedBorder)
                                            .controlSize(.large)
                                            .focused($focusedInput, equals: .cloudPassword)
                                            .onChange(of: cloudPasswordInput) { _, _ in cloudValidationMessage = nil }
                                            .onSubmit { triggerCloudAuth(isSignUp: false) }
                                    }

                                    if let msg = cloudValidationMessage {
                                        Text(msg)
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(.orange)
                                    }

                                    HStack(spacing: 10) {
                                        Button {
                                            triggerCloudAuth(isSignUp: false)
                                        } label: {
                                            Text(vaultState.t("cloud_auth_signin_btn"))
                                                .padding(.horizontal, 6)
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(vaultState.cloudAuthBusy)

                                        Button {
                                            triggerCloudAuth(isSignUp: true)
                                        } label: {
                                            Text("+ " + vaultState.t("cloud_auth_create_btn"))
                                                .padding(.horizontal, 6)
                                        }
                                        .buttonStyle(.bordered)
                                        .disabled(vaultState.cloudAuthBusy)

                                        if vaultState.cloudAuthBusy {
                                            ProgressView().controlSize(.small)
                                        }
                                    }
                                }
                                .onAppear {
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                                        if focusedInput == nil {
                                            focusedInput = .cloudEmail
                                        }
                                    }
                                }
                            }
                        } else {
                            VStack(alignment: .leading, spacing: 10) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(vaultState.t("cloud_bearer_token"))
                                        .font(.system(size: 12, weight: .semibold))
                                    SecureField("", text: $vaultState.endpointConfig.googleDriveBearerToken, prompt: Text("ya29.…"))
                                        .labelsHidden()
                                        .textFieldStyle(.roundedBorder)
                                        .controlSize(.large)
                                        .focused($focusedInput, equals: .gdriveToken)
                                }

                                HStack(spacing: 10) {
                                    Button(vaultState.t("sync_now")) {
                                        if !vaultState.isCloudAuthenticated {
                                            focusedInput = .gdriveToken
                                            vaultState.lastErrorBanner = "Please enter an OAuth Bearer token for Google Drive appDataFolder."
                                        } else {
                                            Task { await vaultState.reconcileCloudSessionAutomatically() }
                                        }
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .disabled(vaultState.isReconcilingCloud)

                                    if vaultState.isCloudAuthenticated {
                                        Button(vaultState.t("cloud_auth_signout_btn")) {
                                            Task { await vaultState.signOutCloudAccount() }
                                        }
                                        .buttonStyle(.bordered)
                                    }
                                }
                            }
                        }

                        Divider()

                        DisclosureGroup(vaultState.t("cloud_config_title"), isExpanded: $showEndpointAdvanced) {
                            VStack(alignment: .leading, spacing: 10) {
                                MacLabeledTextField(
                                    label: vaultState.t("cloud_firebase_project_id"),
                                    placeholder: "safepocket-vault-eu",
                                    text: $vaultState.endpointConfig.firebaseProjectId
                                )
                                MacLabeledSecureField(
                                    label: vaultState.t("cloud_firebase_api_key"),
                                    placeholder: "AIzaSy… (leave empty for local offline cloud replica)",
                                    text: $vaultState.endpointConfig.firebaseWebApiKey
                                )
                                MacLabeledTextField(
                                    label: vaultState.t("cloud_firebase_bucket"),
                                    placeholder: "safepocket-vault-eu.firebasestorage.app",
                                    text: $vaultState.endpointConfig.firebaseStorageBucket
                                )
                                MacLabeledTextField(
                                    label: "Vault Collection ID",
                                    placeholder: "default-vault",
                                    text: $vaultState.endpointConfig.vaultCollectionId
                                )
                                Button("Reset to Default EU Cloud (safepocket-vault-eu)") {
                                    vaultState.resetCloudEndpointDefaults()
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                            .padding(.top, 8)
                        }
                    }
                }

                // 3. PORTABILITY: ENCRYPTED BACKUP (.safepocket), 1PASSWORD & CSV IMPORT / EXPORT + DELETE EXAMPLE DATA
                MacSectionCard(title: vaultState.t("portability_section_title")) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(vaultState.t("backup_export_encrypted")) — Password")
                            .font(.system(size: 12, weight: .semibold))
                        SecureField("", text: $backupPasswordInput, prompt: Text("•••••••• (min. 4)"))
                            .labelsHidden()
                            .textFieldStyle(.roundedBorder)
                            .controlSize(.large)
                            .focused($focusedInput, equals: .backupPassword)
                            .onChange(of: backupPasswordInput) { _, _ in backupValidationMessage = nil }
                    }

                    MacLabeledTextField(
                        label: "\(vaultState.t("security_secret_key")) (optional override for restore)",
                        placeholder: "SPK-XXXX-XXXX-XXXX-XXXX",
                        text: $restoreSecretKeyInput
                    )

                    if let bMsg = backupValidationMessage {
                        Text(bMsg)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.orange)
                    }

                    HStack(spacing: 8) {
                        Button(vaultState.t("backup_export_encrypted")) {
                            guard backupPasswordInput.count >= 4 else {
                                backupValidationMessage = "⚠ Enter a backup password (min. 4 characters) above first."
                                focusedInput = .backupPassword
                                return
                            }
                            backupValidationMessage = nil
                            vaultState.exportEncryptedBackupViaDialog(backupPassword: backupPasswordInput)
                        }
                        .buttonStyle(.borderedProminent)

                        Button(vaultState.t("restore_mode_merge")) {
                            guard !backupPasswordInput.isEmpty else {
                                backupValidationMessage = "⚠ Enter the backup password above before restoring."
                                focusedInput = .backupPassword
                                return
                            }
                            backupValidationMessage = nil
                            vaultState.restoreEncryptedBackupViaDialog(
                                backupPassword: backupPasswordInput,
                                overrideSecretKey: restoreSecretKeyInput,
                                replaceExisting: false
                            )
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("restore_mode_replace")) {
                            guard !backupPasswordInput.isEmpty else {
                                backupValidationMessage = "⚠ Enter the backup password above before restoring."
                                focusedInput = .backupPassword
                                return
                            }
                            backupValidationMessage = nil
                            vaultState.restoreEncryptedBackupViaDialog(
                                backupPassword: backupPasswordInput,
                                overrideSecretKey: restoreSecretKeyInput,
                                replaceExisting: true
                            )
                        }
                        .buttonStyle(.bordered)
                    }

                    Divider()

                    Toggle(vaultState.t("import_include_logins_toggle"), isOn: $vaultState.includeLoginsOnImport)

                    HStack(spacing: 8) {
                        Button(vaultState.t("import_1password_btn")) {
                            vaultState.importCsvOr1PasswordViaDialog()
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("import_csv_btn")) {
                            vaultState.importCsvOr1PasswordViaDialog()
                        }
                        .buttonStyle(.bordered)
                    }

                    Divider()

                    if vaultState.hasCustomMasterPassword {
                        MacLabeledSecureField(
                            label: vaultState.t("security_master_password"),
                            placeholder: "Confirm Master Password for plaintext export",
                            text: $plainExportConfirmPassword
                        )
                    }

                    HStack(spacing: 8) {
                        Button(vaultState.t("export_1password_1pux_btn")) {
                            vaultState.exportPlainFormatViaDialog(kind: "1pux", confirmMasterPw: plainExportConfirmPassword)
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("export_1password_csv_btn")) {
                            vaultState.exportPlainFormatViaDialog(kind: "1p_csv", confirmMasterPw: plainExportConfirmPassword)
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("export_csv_btn")) {
                            vaultState.exportPlainFormatViaDialog(kind: "sp_csv", confirmMasterPw: plainExportConfirmPassword)
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("export_json_btn")) {
                            vaultState.exportPlainFormatViaDialog(kind: "json", confirmMasterPw: plainExportConfirmPassword)
                        }
                        .buttonStyle(.bordered)
                    }

                    if vaultState.sampleEntriesCount > 0 {
                        Divider()
                        Button(role: .destructive) {
                            vaultState.deleteAllExampleData()
                        } label: {
                            Text(vaultState.t("action_delete_example_data", ["count": String(vaultState.sampleEntriesCount)]))
                        }
                        .buttonStyle(.bordered)
                    }
                }

                // 4. ZERO-KNOWLEDGE SECURITY, SCREENSHOTS, BRANDING, EXPIRY WINDOW, AUTO-LOCK & EMERGENCY KIT
                MacSectionCard(title: vaultState.t("security_section_title")) {
                    HStack {
                        Text("\(vaultState.t("security_secret_key")): \(vaultState.secretKeyFormatted)")
                            .font(.system(size: 11.5, design: .monospaced))
                            .textSelection(.enabled)
                            .lineLimit(1)
                        Spacer()
                        Button(vaultState.t("action_copy")) {
                            vaultState.copyFieldWithClipboardHygiene(vaultState.secretKeyFormatted, isSensitive: true)
                        }
                        .buttonStyle(.bordered)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(vaultState.t("security_master_password"))
                            .font(.system(size: 12, weight: .semibold))
                        SecureField("", text: $newMasterPasswordInput, prompt: Text("•••••••••••• (min. 6)"))
                            .labelsHidden()
                            .textFieldStyle(.roundedBorder)
                            .controlSize(.large)
                            .focused($focusedInput, equals: .masterPassword)
                            .onChange(of: newMasterPasswordInput) { _, _ in securityValidationMessage = nil }
                    }

                    MacLabeledTextField(
                        label: "\(vaultState.t("security_secret_key")) (pair from another device — optional)",
                        placeholder: "SPK-XXXX-XXXX-XXXX-XXXX",
                        text: $pairSecretKeyInput
                    )

                    if let sMsg = securityValidationMessage {
                        Text(sMsg)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.orange)
                    }

                    HStack(spacing: 8) {
                        Button(vaultState.t("action_save")) {
                            guard newMasterPasswordInput.trimmingCharacters(in: .whitespacesAndNewlines).count >= 6 else {
                                securityValidationMessage = "⚠ Enter a Master Password (min. 6 characters) above first."
                                focusedInput = .masterPassword
                                return
                            }
                            securityValidationMessage = nil
                            vaultState.configureVaultCredentials(
                                newMasterPassword: newMasterPasswordInput,
                                pairedSecretKey: pairSecretKeyInput
                            )
                            newMasterPasswordInput = ""
                        }
                        .buttonStyle(.borderedProminent)

                        Button(vaultState.t("onboarding_download_emergency_kit")) {
                            vaultState.exportEmergencyKitViaDialog(versionDisplay: updater.currentVersionDisplay)
                        }
                        .buttonStyle(.bordered)
                    }

                    MacLabeledTextField(
                        label: vaultState.t("emergency_kit_hint_placeholder"),
                        placeholder: vaultState.t("emergency_kit_hint_placeholder"),
                        text: $vaultState.masterPasswordHint
                    )

                    Divider()

                    Toggle(vaultState.t("security_allow_screenshots_toggle"), isOn: $vaultState.allowScreenshots)
                    Toggle(vaultState.t("settings_auto_download_logos_toggle"), isOn: $vaultState.autoDownloadLogos)

                    VStack(alignment: .leading, spacing: 3) {
                        Toggle(vaultState.t("security_lock_on_standby_toggle"), isOn: $vaultState.lockOnStandby)
                        Text(vaultState.t("security_lock_on_standby_desc"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(vaultState.t("expiry_window_setting_title"))
                            .font(.system(size: 12, weight: .semibold))
                        Picker("", selection: $vaultState.expiryWindowDays) {
                            ForEach([30, 60, 90, 180], id: \.self) { days in
                                Text(vaultState.t("expiry_window_days_option", ["days": String(days)])).tag(days)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(vaultState.t("security_auto_lock"))
                            .font(.system(size: 12, weight: .semibold))
                        Picker("", selection: $vaultState.autoLockMinutes) {
                            Text(vaultState.t("security_auto_lock_1m")).tag(1)
                            Text(vaultState.t("security_auto_lock_5m")).tag(5)
                            Text(vaultState.t("security_auto_lock_15m")).tag(15)
                            Text(vaultState.t("security_auto_lock_never")).tag(0)
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                }

                // 5. AUTOMATED IN-APP MACOS UPDATES (Ed25519 + SHA-256)
                MacSectionCard(title: vaultState.t("update_section_title")) {
                    Toggle(vaultState.t("update_auto_toggle"), isOn: $updater.automaticChecksEnabled)

                    HStack(spacing: 10) {
                        Button(vaultState.t("update_check_btn") + "…") {
                            updater.checkForUpdatesInitiatedByUser()
                        }
                        .buttonStyle(.bordered)
                        .disabled(updater.isChecking || updater.isDownloadingUpdate)

                        if updater.availableDmgUrl != nil && updater.stagedAppBundleURL == nil {
                            Button("Download & Verify DMG") {
                                Task { await updater.downloadVerifyAndStageMacUpdate() }
                            }
                            .buttonStyle(.borderedProminent)
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
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.green)
                    } else if !updater.statusText.isEmpty {
                        Text(updater.statusText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                // 6. PRIVACY-SCRUBBED DIAGNOSTICS & ABOUT
                MacSectionCard(title: vaultState.t("err_diagnostics_title")) {
                    HStack {
                        Text("Version")
                            .font(.system(size: 12, weight: .semibold))
                        Spacer()
                        Text(updater.currentVersionDisplay)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }

                    HStack {
                        Text("KDF / Cipher")
                            .font(.system(size: 12, weight: .semibold))
                        Spacer()
                        Text(vaultState.t("security_kdf_badge"))
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }

                    HStack(spacing: 8) {
                        Button(vaultState.t("action_copy")) {
                            vaultState.copyDiagnosticsReport()
                        }
                        .buttonStyle(.bordered)

                        Button(vaultState.t("err_clear_log")) {
                            vaultState.clearDiagnostics()
                        }
                        .buttonStyle(.bordered)
                    }

                    if vaultState.diagnostics.isEmpty {
                        Text(vaultState.t("err_diagnostics_empty"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(vaultState.diagnostics.prefix(8)) { entry in
                            Text("[\(entry.operation)] \(entry.scrubbedDetail)")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
            .padding(24)
            .padding(.bottom, 54)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear {
            NSApplication.shared.activate(ignoringOtherApps: true)
        }
    }
}

private struct MacStorageModeSelectionCard: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let selected: Bool
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)

                Text(title)
                    .font(.system(size: 13.5, weight: .bold))
                    .foregroundStyle(.primary)

                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 102, alignment: .topLeading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        selected
                            ? Color.accentColor.opacity(0.12)
                            : (isHovered ? Color.primary.opacity(0.05) : Color(nsColor: .windowBackgroundColor).opacity(0.6))
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(
                        selected
                            ? Color.accentColor
                            : (isHovered ? Color.accentColor.opacity(0.45) : Color.primary.opacity(0.14)),
                        lineWidth: selected ? 2.0 : 1.0
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.14)) {
                isHovered = hovering
            }
        }
    }
}

