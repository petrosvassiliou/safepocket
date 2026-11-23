import SwiftUI
import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Native Apple HIG 3-Column NavigationSplitView for SafePocket v0.3.7 (Build 12) with 100% Cross-Platform Parity.
struct MacVaultSplitView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"
    @State private var confirmDeleteSamples = false
    @State private var revealedFieldIds: Set<String> = []
    @State private var flippedEntryIds: Set<String> = []
    @State private var presentingPassEntry: MacVaultEntry? = nil
    @State private var unlockPasswordInput: String = ""
    @State private var unlockError: Bool = false
    @State private var unlockShakeOffset: CGFloat = 0

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
                .searchable(text: $vaultState.searchQuery, placement: .toolbar, prompt: vaultState.t("search_placeholder"))
            }
        }
        .overlay(alignment: .bottom) {
            if !vaultState.isLocked {
                macClipboardToastOverlay
                    .padding(.bottom, 34)
            }
        }
        .sheet(isPresented: $vaultState.presentSettingsSheet) {
            MacSettingsModalWindow(vaultState: vaultState, updater: updater)
        }
        .sheet(isPresented: $vaultState.presentEditorSheet) {
            MacSmartItemEditorSheet(vaultState: vaultState, existing: vaultState.editingEntry)
        }
        .sheet(item: $presentingPassEntry) { passEntry in
            MacPassPresentationSheet(vaultState: vaultState, entry: passEntry)
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

    // MARK: - Lock Screen Overlay (Vault Shield Card + Touch ID + Master Password + Flag Language Switcher)

    private func attemptPasswordUnlock() {
        if vaultState.unlockWithMasterPassword(unlockPasswordInput) {
            unlockPasswordInput = ""
            unlockError = false
        } else {
            unlockError = true
            withAnimation(.easeInOut(duration: 0.06)) { unlockShakeOffset = -8 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) {
                withAnimation(.easeInOut(duration: 0.06)) { unlockShakeOffset = 7 }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                withAnimation(.spring(response: 0.22, dampingFraction: 0.5)) { unlockShakeOffset = 0 }
            }
        }
    }

    private var macLockedOverlay: some View {
        ZStack {
            Rectangle()
                .fill(.ultraThinMaterial)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                HStack {
                    Spacer()
                    MacFlagLanguageMenu(vaultState: vaultState)
                }

                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(vaultState.materialAccent.swiftUIColor.opacity(0.14))
                        .frame(width: 60, height: 60)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(vaultState.materialAccent.swiftUIColor.opacity(0.32), lineWidth: 1)
                        )
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                }

                VStack(spacing: 4) {
                    Text(vaultState.t("app_name"))
                        .font(.title2.weight(.bold))

                    Text(vaultState.t("app_tagline"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                SecureField(vaultState.t("security_master_password"), text: $unlockPasswordInput)
                    .textFieldStyle(.roundedBorder)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .onSubmit {
                        attemptPasswordUnlock()
                    }

                if unlockError {
                    Label(vaultState.t("security_invalid_master_password"), systemImage: "exclamationmark.triangle.fill")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.red)
                }

                HStack(spacing: 10) {
                    Button {
                        attemptPasswordUnlock()
                    } label: {
                        Text(vaultState.t("action_unlock_vault"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    Button {
                        Task { _ = await vaultState.unlockWithTouchID() }
                    } label: {
                        Label("Touch ID", systemImage: "touchid")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .padding(28)
            .frame(width: 400)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(Color(nsColor: .windowBackgroundColor).opacity(0.92))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.16), radius: 24, y: 10)
            .offset(x: unlockShakeOffset)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            VStack(alignment: .center, spacing: 5) {
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
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.horizontal, 10)
                .padding(.top, 5)

                HStack(spacing: 5) {
                    Image(systemName: "checkmark.shield.fill")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 10))
                    Text(updater.currentVersionDisplay)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.horizontal, 10)
                .padding(.bottom, 6)
            }
            .background(.bar)
        }
        .navigationSplitViewColumnWidth(min: 230, ideal: 252, max: 310)
    }

    // MARK: - Column 2: Native Inset Vault List with Aligned Toolbar Title & Banners

    private var showPinnedWalletDeck: Bool {
        vaultState.selectedCategory == "all"
            && vaultState.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && vaultState.entries.contains(where: { $0.pinned })
    }

    private var hasListTopBanners: Bool {
        vaultState.lastErrorBanner != nil || vaultState.statusNotice != nil || vaultState.sampleEntriesCount > 0 || showPinnedWalletDeck
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
        .toolbar {
            ToolbarItem(placement: .automatic) {
                macNewItemCategoryMenu
                    .help(vaultState.t("action_new_item"))
            }
        }
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

                    if showPinnedWalletDeck {
                        macPinnedWalletDeck
                    }
                    Divider()
                }
            }
        }
        .navigationSplitViewColumnWidth(min: 280, ideal: 328, max: 420)
    }

    @ViewBuilder
    private var macPinnedWalletDeck: some View {
        let pinnedItems = vaultState.entries.filter { $0.pinned }
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(vaultState.t("wallet_deck_pinned_title"), systemImage: "pin.fill")
                    .font(.system(size: 10.5, weight: .bold))
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(pinnedItems.count)")
                    .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(pinnedItems.prefix(8)) { item in
                        let inst = MacSmartIntelligence.resolveInstitution(for: item)
                        let cardColor = MacSmartIntelligence.colorFromHex(item.customColorHex ?? inst?.primaryHex)
                            ?? vaultState.materialAccent.swiftUIColor
                        let compactVal = item.primaryValue.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
                        let tail = compactVal.count >= 4 ? "•• \(compactVal.suffix(4))" : MacSmartIntelligence.resolveNetworkBadge(for: item)
                        let isSel = vaultState.selectedEntryId == item.id

                        Button {
                            vaultState.selectedEntryId = item.id
                        } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(spacing: 4) {
                                    Text(item.title)
                                        .font(.system(size: 11.5, weight: .bold))
                                        .foregroundStyle(.white)
                                        .lineLimit(1)
                                    Spacer(minLength: 4)
                                    Image(systemName: "pin.fill")
                                        .font(.system(size: 9))
                                        .foregroundStyle(.white.opacity(0.85))
                                }
                                HStack {
                                    Text(tail)
                                        .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                                        .foregroundStyle(.white.opacity(0.92))
                                    Spacer(minLength: 4)
                                    Text(MacSmartIntelligence.resolveNetworkBadge(for: item))
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundStyle(.white.opacity(0.80))
                                }
                            }
                            .padding(.horizontal, 11)
                            .padding(.vertical, 8)
                            .frame(width: 142, alignment: .leading)
                            .background(
                                LinearGradient(
                                    colors: [cardColor, cardColor.opacity(0.76)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .strokeBorder(isSel ? Color.white : Color.white.opacity(0.24), lineWidth: isSel ? 2 : 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    @ViewBuilder
    private var macClipboardToastOverlay: some View {
        if let secs = vaultState.clipboardCountdownSeconds {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .stroke(Color.secondary.opacity(0.24), lineWidth: 2.6)
                        .frame(width: 24, height: 24)
                    Circle()
                        .trim(from: 0, to: max(0.03, CGFloat(secs) / 30.0))
                        .stroke(
                            vaultState.materialAccent.swiftUIColor,
                            style: StrokeStyle(lineWidth: 2.6, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .frame(width: 24, height: 24)
                    Text("\(secs)")
                        .font(.system(size: 9.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                }
                Text(vaultState.t("clipboard_hygiene_active", ["seconds": String(secs)]))
                    .font(.system(size: 12, weight: .medium))
                Button(vaultState.t("action_clear_now")) {
                    vaultState.scrubClipboardNow()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(vaultState.materialAccent.swiftUIColor.opacity(0.35), lineWidth: 1))
            .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
        } else if vaultState.clipboardClearedFlash {
            HStack(spacing: 7) {
                Image(systemName: "checkmark.shield.fill")
                    .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                Text(vaultState.t("clipboard_hygiene_cleared"))
                    .font(.system(size: 12, weight: .medium))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
            .shadow(color: .black.opacity(0.16), radius: 12, y: 5)
        } else if let plainMsg = vaultState.plainCopiedBanner {
            HStack(spacing: 7) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(vaultState.materialAccent.swiftUIColor)
                Text(plainMsg)
                    .font(.system(size: 12, weight: .medium))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
            .shadow(color: .black.opacity(0.14), radius: 10, y: 4)
        }
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

                        if selected.category == "BANK_ACCOUNT" {
                            MacSepaQrSectionCard(vaultState: vaultState, entry: selected)
                        }

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
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                MacFlagLanguageMenu(vaultState: vaultState)

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

    @ViewBuilder
    private func macVisualBrandCard(for entry: MacVaultEntry) -> some View {
        let isFlipped = flippedEntryIds.contains(entry.id)
        MacFlippableVisualCard(
            vaultState: vaultState,
            entry: entry,
            isFlipped: isFlipped,
            isCompactPreview: false,
            onToggleFlip: {
                withAnimation(.spring(response: 0.48, dampingFraction: 0.82)) {
                    if flippedEntryIds.contains(entry.id) {
                        flippedEntryIds.remove(entry.id)
                    } else {
                        flippedEntryIds.insert(entry.id)
                    }
                }
            },
            onCopyPrimary: {
                if !entry.primaryValue.isEmpty {
                    vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                }
            },
            onEditEntry: {
                vaultState.openEditEditor(entry)
            },
            onPresentPass: {
                presentingPassEntry = entry
            }
        )
    }
}

struct MacFlippableVisualCard: View {
    @ObservedObject var vaultState: MacVaultViewModel
    let entry: MacVaultEntry
    var isFlipped: Bool = false
    var isCompactPreview: Bool = false
    var onToggleFlip: (() -> Void)? = nil
    var onCopyPrimary: (() -> Void)? = nil
    var onEditEntry: (() -> Void)? = nil
    var onPresentPass: (() -> Void)? = nil

    @State private var isCvvRevealed: Bool = false
    @State private var isPinRevealed: Bool = false

    private struct ReverseCellItem: Identifiable {
        let id: String
        let label: String
        let value: String
        let isPin: Bool
    }

    private func fieldValue(_ key: String) -> String {
        entry.fields.first(where: { $0.labelKey == key })?.value.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private var resolvedCvv: String {
        let std = fieldValue("field_cvv")
        if !std.isEmpty { return std }
        let aliases = [
            "cvv", "cvc", "csc", "prüfziffer", "kartenprüfnummer", "prüfnummer",
            "verification number", "security code", "codice sicurezza",
            "cryptogramme", "código de seguridad", "κωδικός ασφαλείας"
        ]
        for f in entry.fields {
            let k = f.labelKey.lowercased()
            let v = f.value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !v.isEmpty && aliases.contains(where: { k.contains($0) }) {
                return v
            }
        }
        return ""
    }

    private var resolvedPin: String {
        let std = fieldValue("field_pin")
        if !std.isEmpty { return std }
        for f in entry.fields {
            let k = f.labelKey.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let v = f.value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !v.isEmpty && (k == "pin" || k.contains("karten-pin") || k.contains("card pin") || k.contains("telephonepin")) {
                return v
            }
        }
        return ""
    }

    private var maskedPrimary: String {
        let raw = entry.primaryValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            return entry.category == "PAYMENT_CARD" ? "•••• •••• •••• ••••" : ""
        }
        if isCompactPreview { return raw }
        if entry.category == "PAYMENT_CARD" {
            let digits = raw.replacingOccurrences(of: " ", with: "")
            return digits.count >= 4 ? "•••• •••• •••• \(digits.suffix(4))" : raw
        }
        if entry.category == "BANK_ACCOUNT" {
            let compact = raw.replacingOccurrences(of: " ", with: "")
            return compact.count >= 8 ? "\(compact.prefix(4)) •••• •••• \(compact.suffix(4))" : raw
        }
        return raw
    }

    private var holderDisplay: String {
        let h = fieldValue("field_cardholder")
            .isEmpty ? fieldValue("field_account_holder") : fieldValue("field_cardholder")
        if !h.isEmpty { return h }
        let fn = fieldValue("field_full_name")
        if !fn.isEmpty { return fn }
        return fieldValue("field_member_name")
    }

    private var reverseCells: [ReverseCellItem] {
        var out: [ReverseCellItem] = []
        let pin = resolvedPin
        if !pin.isEmpty {
            let maskedPin = String(repeating: "•", count: max(4, pin.count))
            out.append(ReverseCellItem(
                id: "pin",
                label: vaultState.resolveFieldLabel(key: "field_pin", isCustom: false, category: entry.category),
                value: isPinRevealed ? pin : maskedPin,
                isPin: true
            ))
        }
        let bic = fieldValue("field_bic")
        if !bic.isEmpty {
            out.append(ReverseCellItem(
                id: "bic",
                label: vaultState.resolveFieldLabel(key: "field_bic", isCustom: false, category: entry.category),
                value: bic,
                isPin: false
            ))
        }
        let routing = fieldValue("field_routing_code").isEmpty ? fieldValue("field_routing_number") : fieldValue("field_routing_code")
        if !routing.isEmpty {
            out.append(ReverseCellItem(
                id: "routing",
                label: vaultState.resolveFieldLabel(key: "field_routing_code", isCustom: false, category: entry.category),
                value: routing,
                isPin: false
            ))
        }
        let phone = fieldValue("field_emergency_phone").isEmpty ? fieldValue("field_support_phone") : fieldValue("field_emergency_phone")
        if !phone.isEmpty {
            out.append(ReverseCellItem(
                id: "phone",
                label: vaultState.resolveFieldLabel(key: "field_support_phone", isCustom: false, category: entry.category),
                value: phone,
                isPin: false
            ))
        }
        let domain = fieldValue("field_institution_domain")
        if !domain.isEmpty && out.count < 4 {
            let host = domain.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "www.", with: "")
            out.append(ReverseCellItem(
                id: "domain",
                label: vaultState.resolveFieldLabel(key: "field_institution_domain", isCustom: false, category: entry.category),
                value: host,
                isPin: false
            ))
        }
        return Array(out.prefix(4))
    }

    @ViewBuilder
    private func frontCardFace(baseColor: Color, inst: MacInstitutionProfile?, networkBadge: String) -> some View {
        let expiry = fieldValue("field_expiry_date")
        VStack(alignment: .leading, spacing: isCompactPreview ? 10 : 14) {
            HStack(alignment: .center) {
                HStack(spacing: 10) {
                    if vaultState.autoDownloadLogos,
                       let domain = inst?.domain ?? URL(string: entry.websiteUrl)?.host,
                       let logoURL = URL(string: "https://www.google.com/s2/favicons?domain=\(domain)&sz=64") {
                        AsyncImage(url: logoURL) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFit().frame(width: 26, height: 26)
                                    .padding(4)
                                    .background(.white, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            } else {
                                Text(inst?.monogram ?? String(entry.title.prefix(2)).uppercased())
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 34, height: 34)
                                    .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            }
                        }
                    } else {
                        Text(inst?.monogram ?? String(entry.title.prefix(2)).uppercased())
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.title)
                            .font(.headline)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text(vaultState.displaySubtitle(for: entry))
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                    }
                }
                Spacer()
                Text(networkBadge)
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.20), in: Capsule())
                    .foregroundStyle(.white)
            }

            if !isCompactPreview && ["PAYMENT_CARD", "INSURANCE_CARD", "OFFICIAL_DOCUMENT"].contains(entry.category) {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color(red: 0.95, green: 0.86, blue: 0.62), Color(red: 0.78, green: 0.62, blue: 0.32)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 40, height: 28)
                        .overlay(
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .strokeBorder(Color.black.opacity(0.24), lineWidth: 0.8)
                                .padding(5)
                        )
                    if entry.category == "PAYMENT_CARD" {
                        Image(systemName: "wave.3.right")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.82))
                    }
                }
            }

            Spacer(minLength: 0)

            if !maskedPrimary.isEmpty {
                Text(maskedPrimary)
                    .font(.system(isCompactPreview ? .callout : .title3, design: .monospaced).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }

            if !holderDisplay.isEmpty || !expiry.isEmpty {
                HStack(alignment: .bottom) {
                    Text(holderDisplay.uppercased())
                        .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.92))
                        .lineLimit(1)
                    Spacer()
                    if !expiry.isEmpty {
                        Text(expiry)
                            .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            .foregroundStyle(.white)
                    }
                }
            }
        }
        .padding(isCompactPreview ? 16 : 22)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            ZStack {
                LinearGradient(
                    colors: [baseColor, baseColor.opacity(0.76)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [.white.opacity(0.16), .clear],
                    startPoint: .topLeading,
                    endPoint: .center
                )
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(.white.opacity(0.22), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func backCardFace(baseColor: Color) -> some View {
        let cvv = resolvedCvv
        VStack(alignment: .leading, spacing: 12) {
            if entry.category == "PAYMENT_CARD" {
                Rectangle()
                    .fill(Color.black.opacity(0.82))
                    .frame(height: 34)
                    .padding(.horizontal, -22)

                if !cvv.isEmpty {
                    let maskedCvv = String(repeating: "•", count: max(3, cvv.count))
                    Button {
                        withAnimation(.easeInOut(duration: 0.16)) {
                            isCvvRevealed.toggle()
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Text("AUTHORIZED SIGNATURE • \(vaultState.t("field_cvv"))")
                                .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                                .foregroundStyle(Color.black.opacity(0.65))
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            HStack(spacing: 5) {
                                Text(isCvvRevealed ? cvv : maskedCvv)
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .monospacedDigit()
                                    .foregroundStyle(Color.black)
                                Image(systemName: isCvvRevealed ? "eye.slash" : "eye")
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundStyle(Color.black.opacity(0.62))
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.black.opacity(isCvvRevealed ? 0.11 : 0.06), in: Capsule())
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .frame(maxWidth: .infinity)
                        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .help(vaultState.t(isCvvRevealed ? "action_hide" : "action_reveal"))
                    .contextMenu {
                        Button(vaultState.t("action_copy")) {
                            vaultState.copyFieldWithClipboardHygiene(cvv, isSensitive: true)
                        }
                    }
                } else if let onEditEntry {
                    Button {
                        onEditEntry()
                    } label: {
                        HStack(spacing: 8) {
                            Text("AUTHORIZED SIGNATURE • \(vaultState.t("field_cvv"))")
                                .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                                .foregroundStyle(Color.black.opacity(0.65))
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            HStack(spacing: 4) {
                                Text("—")
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.black.opacity(0.45))
                                Image(systemName: "pencil")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(Color.black.opacity(0.50))
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.black.opacity(0.05), in: Capsule())
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .frame(maxWidth: .infinity)
                        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .help(vaultState.t("action_edit"))
                } else {
                    HStack {
                        Text("AUTHORIZED SIGNATURE • \(vaultState.t("field_cvv"))")
                            .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                            .foregroundStyle(Color.black.opacity(0.65))
                        Spacer()
                        Text("—")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.black.opacity(0.45))
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
            } else {
                HStack {
                    Text(entry.title)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Spacer()
                    Text(vaultState.t("card_back_badge"))
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(.white.opacity(0.18), in: Capsule())
                        .foregroundStyle(.white)
                }
            }

            if reverseCells.isEmpty {
                Text(vaultState.t("card_back_empty_hint"))
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    ForEach(reverseCells) { cell in
                        if cell.isPin {
                            Button {
                                withAnimation(.easeInOut(duration: 0.16)) {
                                    isPinRevealed.toggle()
                                }
                            } label: {
                                HStack(spacing: 6) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(cell.label.uppercased())
                                            .font(.system(size: 9, weight: .semibold))
                                            .foregroundStyle(.white.opacity(0.75))
                                            .lineLimit(1)
                                        Text(cell.value)
                                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                            .foregroundStyle(.white)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 4)
                                    Image(systemName: isPinRevealed ? "eye.slash" : "eye")
                                        .font(.system(size: 10.5, weight: .semibold))
                                        .foregroundStyle(.white.opacity(0.78))
                                }
                                .padding(.horizontal, 9)
                                .padding(.vertical, 6)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(.white.opacity(0.15), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                                .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .help(vaultState.t(isPinRevealed ? "action_hide" : "action_reveal"))
                        } else {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(cell.label.uppercased())
                                    .font(.system(size: 9, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.75))
                                    .lineLimit(1)
                                Text(cell.value)
                                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 9)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        }
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(22)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            LinearGradient(
                colors: [baseColor.opacity(0.92), baseColor.opacity(0.72)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(.white.opacity(0.22), lineWidth: 1)
        )
    }

    var body: some View {
        let inst = MacSmartIntelligence.resolveInstitution(for: entry)
        let baseColor = MacSmartIntelligence.colorFromHex(entry.customColorHex ?? inst?.primaryHex)
            ?? vaultState.materialAccent.swiftUIColor
        let networkBadge = MacSmartIntelligence.resolveNetworkBadge(for: entry)

        if isCompactPreview {
            frontCardFace(baseColor: baseColor, inst: inst, networkBadge: networkBadge)
                .frame(height: 152)
                .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
        } else {
            VStack(alignment: .leading, spacing: 10) {
                ZStack {
                    frontCardFace(baseColor: baseColor, inst: inst, networkBadge: networkBadge)
                        .opacity(isFlipped ? 0 : 1)
                        .rotation3DEffect(.degrees(isFlipped ? 180 : 0), axis: (x: 0, y: 1, z: 0), perspective: 0.45)
                        .allowsHitTesting(!isFlipped)

                    backCardFace(baseColor: baseColor)
                        .opacity(isFlipped ? 1 : 0)
                        .rotation3DEffect(.degrees(isFlipped ? 0 : -180), axis: (x: 0, y: 1, z: 0), perspective: 0.45)
                        .allowsHitTesting(isFlipped)
                }
                .aspectRatio(1.586, contentMode: .fit)
                .frame(maxWidth: 440)
                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
                .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .onTapGesture {
                    onToggleFlip?()
                }
                .onChange(of: entry.id) { _, _ in
                    isCvvRevealed = false
                    isPinRevealed = false
                }
                .onChange(of: isFlipped) { _, flipped in
                    if !flipped {
                        isCvvRevealed = false
                        isPinRevealed = false
                    }
                }

                HStack(spacing: 8) {
                    Button {
                        onToggleFlip?()
                    } label: {
                        Label(
                            vaultState.t(isFlipped ? "card_flip_to_front" : "card_flip_to_back"),
                            systemImage: "arrow.triangle.2.circlepath"
                        )
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    if !entry.primaryValue.isEmpty {
                        Button {
                            onCopyPrimary?()
                        } label: {
                            Label(vaultState.t("action_copy"), systemImage: "doc.on.doc")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }

                    if let onPresentPass {
                        Spacer(minLength: 0)
                        Button {
                            onPresentPass()
                        } label: {
                            Label(vaultState.t("action_present_card"), systemImage: "viewfinder")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
    }
}

// MARK: - Phase 2: Offline EPC SEPA GiroCode, CoreImage QR/Code128 & Counter Pass / Read-Aloud Sheet

enum MacEpcSepaAndBarcode {
    struct ReadAloudBlock: Identifiable {
        let id: Int
        let chars: String
    }

    static func buildEpcSepaQrPayload(
        holderName: String,
        iban: String,
        bic: String = "",
        amountEur: String = "",
        remittanceText: String = ""
    ) -> String? {
        let cleanIban = iban
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .uppercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanIban.range(of: #"^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$"#, options: .regularExpression) != nil else {
            return nil
        }

        let cleanHolder = String(
            holderName
                .replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(70)
        )
        let effectiveHolder = cleanHolder.isEmpty ? "Account Holder" : cleanHolder

        let rawBic = bic.replacingOccurrences(of: " ", with: "").uppercased()
        let cleanBic = rawBic.range(of: #"^[A-Z0-9]{8}([A-Z0-9]{3})?$"#, options: .regularExpression) != nil ? rawBic : ""

        let amtNormalized = amountEur
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
            .filter { "0123456789.".contains($0) }
        var formattedAmount = ""
        if let val = Double(amtNormalized), val > 0, val <= 999_999_999.99 {
            formattedAmount = String(format: "EUR%.2f", val)
        }

        let cleanRemit = String(
            remittanceText
                .replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(40)
        )

        var lines = [
            "BCD",
            "002",
            "1",
            "SCT",
            cleanBic,
            effectiveHolder,
            cleanIban,
            formattedAmount,
            "",
            "",
            cleanRemit
        ]
        while lines.count > 7 && lines.last == "" {
            lines.removeLast()
        }
        return lines.joined(separator: "\n")
    }

    static func buildReadAloudBlocks(_ rawValue: String, groupSize: Int = 4) -> [ReadAloudBlock] {
        let compact = rawValue
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !compact.isEmpty else { return [] }
        let chars = Array(compact)
        let step = max(2, groupSize)
        var out: [ReadAloudBlock] = []
        var idx = 1
        var offset = 0
        while offset < chars.count {
            let end = min(chars.count, offset + step)
            out.append(ReadAloudBlock(id: idx, chars: String(chars[offset..<end])))
            idx += 1
            offset += step
        }
        return out
    }

    static func renderQrImage(payload: String, scale: CGFloat = 6) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let outputImage = filter.outputImage else { return nil }
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cgImg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImg, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }

    static func renderCode128Image(payload: String, scaleX: CGFloat = 2.6, scaleY: CGFloat = 3.2) -> NSImage? {
        guard payload.canBeConverted(to: .ascii) else { return nil }
        let context = CIContext()
        let filter = CIFilter.code128BarcodeGenerator()
        filter.message = Data(payload.utf8)
        filter.quietSpace = 7
        guard let outputImage = filter.outputImage else { return nil }
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        guard let cgImg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImg, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}

struct MacSepaQrSectionCard: View {
    @ObservedObject var vaultState: MacVaultViewModel
    let entry: MacVaultEntry
    @State private var isExpanded: Bool = false
    @State private var amountInput: String = ""
    @State private var purposeInput: String = ""

    private func fieldVal(_ key: String) -> String {
        entry.fields.first(where: { $0.labelKey == key })?.value.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var body: some View {
        let iban = fieldVal("field_iban")
        let holder = fieldVal("field_account_holder").isEmpty ? entry.title : fieldVal("field_account_holder")
        let bic = fieldVal("field_bic")

        if let payload = MacEpcSepaAndBarcode.buildEpcSepaQrPayload(
            holderName: holder,
            iban: iban,
            bic: bic,
            amountEur: amountInput,
            remittanceText: purposeInput
        ) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .center, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label(vaultState.t("sepa_qr_title"), systemImage: "qrcode")
                            .font(.subheadline.weight(.bold))
                        Text(vaultState.t("sepa_qr_subtitle"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            isExpanded.toggle()
                        }
                    } label: {
                        Label(
                            vaultState.t(isExpanded ? "sepa_qr_hide_btn" : "sepa_qr_show_btn"),
                            systemImage: "qrcode"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }

                if isExpanded {
                    Divider()
                    HStack(alignment: .center, spacing: 18) {
                        if let qrImg = MacEpcSepaAndBarcode.renderQrImage(payload: payload, scale: 5.5) {
                            Image(nsImage: qrImg)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 148, height: 148)
                                .padding(10)
                                .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .strokeBorder(Color.black.opacity(0.12), lineWidth: 1)
                                )
                        }

                        VStack(alignment: .leading, spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(holder)
                                    .font(.caption.weight(.bold))
                                Text(bic.isEmpty ? iban : "\(iban) • \(bic)")
                                    .font(.system(size: 11.5, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                            }

                            HStack(spacing: 10) {
                                MacLabeledTextField(
                                    label: vaultState.t("sepa_qr_amount_label"),
                                    placeholder: "25.00",
                                    text: $amountInput
                                )
                                MacLabeledTextField(
                                    label: vaultState.t("sepa_qr_purpose_label"),
                                    placeholder: "Dinner / Invoice",
                                    text: $purposeInput
                                )
                            }
                        }
                    }
                }
            }
            .padding(16)
            .background(
                Color(nsColor: .controlBackgroundColor),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.09), lineWidth: 1)
            )
        }
    }
}

struct MacPassPresentationSheet: View {
    @ObservedObject var vaultState: MacVaultViewModel
    let entry: MacVaultEntry
    @Environment(\.dismiss) private var dismiss

    private enum PassTab: String, CaseIterable {
        case digital
        case readAloud
    }

    @State private var activeTab: PassTab = .digital
    @State private var useQrCode: Bool = false
    @State private var isCardFlipped: Bool = false

    private func fieldVal(_ key: String) -> String {
        entry.fields.first(where: { $0.labelKey == key })?.value.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var body: some View {
        let iban = fieldVal("field_iban")
        let holder = fieldVal("field_account_holder").isEmpty ? entry.title : fieldVal("field_account_holder")
        let bic = fieldVal("field_bic")
        let epcPayload = entry.category == "BANK_ACCOUNT"
            ? MacEpcSepaAndBarcode.buildEpcSepaQrPayload(holderName: holder, iban: iban, bic: bic)
            : nil
        let readableValue = [
            fieldVal("field_barcode_value"),
            fieldVal("field_membership_id"),
            fieldVal("field_insurance_number"),
            fieldVal("field_document_number"),
            iban,
            fieldVal("field_card_number")
        ].first(where: { !$0.isEmpty }) ?? entry.primaryValue
        let blocks = MacEpcSepaAndBarcode.buildReadAloudBlocks(readableValue, groupSize: 4)
        let effectiveQrMode = useQrCode || epcPayload != nil || !readableValue.canBeConverted(to: .ascii)
        let qrPayload = epcPayload ?? readableValue

        VStack(spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.title)
                        .font(.title3.weight(.bold))
                    Text(vaultState.t("present_wake_lock_active"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(vaultState.t("present_close_btn")) {
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.cancelAction)
            }

            if !blocks.isEmpty {
                Picker("", selection: $activeTab) {
                    Text(vaultState.t("pass_tab_digital")).tag(PassTab.digital)
                    Text(vaultState.t("pass_tab_read_aloud")).tag(PassTab.readAloud)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 320)
            }

            ScrollView {
                VStack(spacing: 16) {
                    if activeTab == .readAloud && !blocks.isEmpty {
                        VStack(spacing: 14) {
                            Text(vaultState.t("pass_read_aloud_hint"))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.black.opacity(0.65))

                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), spacing: 10)], spacing: 10) {
                                ForEach(blocks) { block in
                                    VStack(spacing: 3) {
                                        Text("#\(block.id)")
                                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                                            .foregroundStyle(Color.black.opacity(0.50))
                                        Text(block.chars)
                                            .font(.system(size: 22, weight: .heavy, design: .monospaced))
                                            .foregroundStyle(Color.black)
                                    }
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 10)
                                    .frame(maxWidth: .infinity)
                                    .background(Color(white: 0.96), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                                            .strokeBorder(Color.black.opacity(0.14), lineWidth: 1)
                                    )
                                }
                            }

                            if !bic.isEmpty {
                                Text("BIC: \(bic)")
                                    .font(.system(size: 13.5, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.black.opacity(0.78))
                            }
                        }
                        .padding(20)
                        .frame(maxWidth: .infinity)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    } else {
                        MacFlippableVisualCard(
                            vaultState: vaultState,
                            entry: entry,
                            isFlipped: isCardFlipped,
                            onToggleFlip: {
                                withAnimation(.spring(response: 0.46, dampingFraction: 0.82)) {
                                    isCardFlipped.toggle()
                                }
                            },
                            onCopyPrimary: {
                                if !entry.primaryValue.isEmpty {
                                    vaultState.copyFieldWithClipboardHygiene(entry.primaryValue, isSensitive: true)
                                }
                            }
                        )

                        if !readableValue.isEmpty {
                            VStack(spacing: 12) {
                                if epcPayload == nil && readableValue.canBeConverted(to: .ascii) {
                                    HStack(spacing: 8) {
                                        Button(vaultState.t("barcode_format_code128")) {
                                            useQrCode = false
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)

                                        Button(vaultState.t("barcode_format_qr")) {
                                            useQrCode = true
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                    }
                                } else if epcPayload != nil {
                                    Text(vaultState.t("sepa_qr_title"))
                                        .font(.system(size: 11.5, weight: .bold))
                                        .foregroundStyle(Color.black.opacity(0.68))
                                }

                                if effectiveQrMode, let qrImg = MacEpcSepaAndBarcode.renderQrImage(payload: qrPayload, scale: 6.2) {
                                    Image(nsImage: qrImg)
                                        .interpolation(.none)
                                        .resizable()
                                        .scaledToFit()
                                        .frame(width: 176, height: 176)
                                } else if let c128Img = MacEpcSepaAndBarcode.renderCode128Image(payload: readableValue) {
                                    Image(nsImage: c128Img)
                                        .interpolation(.none)
                                        .resizable()
                                        .scaledToFit()
                                        .frame(maxWidth: 380, maxHeight: 84)
                                }

                                Text(readableValue)
                                    .font(.system(size: 16, weight: .bold, design: .monospaced))
                                    .foregroundStyle(Color.black)
                                    .textSelection(.enabled)
                            }
                            .padding(20)
                            .frame(maxWidth: .infinity)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .strokeBorder(Color.black.opacity(0.10), lineWidth: 1)
                            )
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 22)
        .frame(width: 560, height: 640)
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
    @State private var moreFieldsExpanded: Bool = false
    @FocusState private var titleFocused: Bool

    private var livePreviewEntry: MacVaultEntry {
        let builtFields = MacCategoryTemplates.specs(for: category).compactMap { spec -> MacVaultField? in
            let val = (fieldDrafts[spec.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !val.isEmpty else { return nil }
            return MacVaultField(
                id: "pv-\(spec.key)",
                labelKey: spec.key,
                isCustomLabel: false,
                value: val,
                valueType: spec.valueType,
                isSensitive: spec.sensitive
            )
        }
        return MacVaultEntry(
            id: "preview",
            category: category,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? vaultState.categorySingularName(category)
                : title,
            subtitle: subtitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? vaultState.categorySingularName(category)
                : subtitle,
            fields: builtFields,
            tags: [],
            customColorHex: customColorHex.isEmpty ? nil : customColorHex,
            networkBrand: fieldDrafts["field_card_network"],
            pinned: false,
            favorite: false,
            isSampleItem: false,
            updatedAt: Date()
        )
    }

    var body: some View {
        let allSpecs = MacCategoryTemplates.specs(for: category)
        let primarySpecs = Array(allSpecs.prefix(6))
        let secondarySpecs = Array(allSpecs.dropFirst(6))

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
                    if category != "SECURE_NOTE" {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(vaultState.t("editor_live_preview_label").uppercased())
                                .font(.system(size: 10.5, weight: .bold))
                                .foregroundStyle(.secondary)
                            MacFlippableVisualCard(
                                vaultState: vaultState,
                                entry: livePreviewEntry,
                                isCompactPreview: true
                            )
                        }
                    }

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

                    MacSectionCard(title: vaultState.t("editor_essential_fields_title")) {
                        ForEach(primarySpecs, id: \.key) { spec in
                            smartEditorRow(for: spec)
                        }

                        if !secondarySpecs.isEmpty {
                            Divider()
                            DisclosureGroup(
                                "\(vaultState.t("editor_more_fields_toggle")) (\(secondarySpecs.count))",
                                isExpanded: $moreFieldsExpanded
                            ) {
                                VStack(alignment: .leading, spacing: 12) {
                                    ForEach(secondarySpecs, id: \.key) { spec in
                                        smartEditorRow(for: spec)
                                    }
                                }
                                .padding(.top, 8)
                            }
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
                let secSpecs = Array(MacCategoryTemplates.specs(for: ex.category).dropFirst(6))
                moreFieldsExpanded = secSpecs.contains { !(fieldDrafts[$0.key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
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

    private enum MacSettingsTab: String, CaseIterable, Identifiable {
        case appearance
        case security
        case sync
        case data
        case updates

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .appearance: return "sun.max"
            case .security: return "checkmark.shield"
            case .sync: return "icloud"
            case .data: return "externaldrive"
            case .updates: return "arrow.triangle.2.circlepath"
            }
        }

        var labelKey: String {
            switch self {
            case .appearance: return "settings_tab_appearance"
            case .security: return "settings_tab_security"
            case .sync: return "settings_tab_sync"
            case .data: return "settings_tab_data"
            case .updates: return "settings_tab_updates"
            }
        }
    }

    @State private var activeTab: MacSettingsTab = .appearance

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
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                ForEach(MacSettingsTab.allCases) { tab in
                    let isSelected = activeTab == tab
                    Button {
                        withAnimation(.easeInOut(duration: 0.14)) {
                            activeTab = tab
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: tab.systemImage)
                                .font(.system(size: 12, weight: .semibold))
                            Text(vaultState.t(tab.labelKey))
                                .font(.system(size: 12.5, weight: .semibold))
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .frame(maxWidth: .infinity)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(isSelected ? vaultState.materialAccent.swiftUIColor.opacity(0.14) : Color.clear)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .strokeBorder(isSelected ? vaultState.materialAccent.swiftUIColor.opacity(0.42) : Color.clear, lineWidth: 1)
                        )
                        .foregroundStyle(isSelected ? vaultState.materialAccent.swiftUIColor : .secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(6)
            .background(Color(nsColor: .controlBackgroundColor))
            .overlay(alignment: .bottom) { Divider() }

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

                    if activeTab == .appearance {
                        // 1. APPEARANCE, ACCENT COLOR & 6-LANGUAGE SELECTOR (WITH COUNTRY FLAGS)
                        MacSectionCard(title: vaultState.t("settings_theme_title") + " & " + vaultState.t("settings_language_title"), systemImage: "sun.max") {
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
                        Toggle(vaultState.t("settings_auto_download_logos_toggle"), isOn: $vaultState.autoDownloadLogos)
                    }
                    } // end activeTab == .appearance

                    if activeTab == .sync {
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
                } // end activeTab == .sync

                if activeTab == .data {
                // 3. PORTABILITY: ENCRYPTED BACKUP (.safepocket), 1PASSWORD & CSV IMPORT / EXPORT + DELETE EXAMPLE DATA
                MacSectionCard(title: vaultState.t("portability_section_title"), systemImage: "externaldrive") {
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
                } // end activeTab == .data

                if activeTab == .security {
                // 4. ZERO-KNOWLEDGE SECURITY, SCREENSHOTS, BRANDING, EXPIRY WINDOW, AUTO-LOCK & EMERGENCY KIT
                MacSectionCard(title: vaultState.t("security_section_title"), systemImage: "checkmark.shield") {
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
                } // end activeTab == .security

                if activeTab == .updates {
                // 5. AUTOMATED IN-APP MACOS UPDATES (Ed25519 + SHA-256)
                MacSectionCard(title: vaultState.t("update_section_title"), systemImage: "arrow.triangle.2.circlepath") {
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
                } // end activeTab == .updates
            }
            .padding(24)
            .padding(.bottom, 54)
        }
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

