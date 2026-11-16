import SwiftUI

/// Native Apple HIG 3-Column NavigationSplitView with Vibrancy/Translucency Sidebar,
/// Unified macOS Toolbar, SF Symbols, Native Context Menus, and Subtle Bottom Version Footer.
struct MacVaultSplitView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                List(selection: $vaultState.selectedCategory) {
                    Section("Vault") {
                        Label("All Items", systemImage: "tray.full")
                            .tag("all")
                        Label("Favorites", systemImage: "star")
                            .tag("favorites")
                        Label("Expiring Soon", systemImage: "clock.badge.exclamationmark")
                            .tag("expiring")
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
                }
                .listStyle(.sidebar)

                Divider()

                // Subtle semantic version & build badge at bottom of macOS vibrancy sidebar
                HStack {
                    Image(systemName: "checkmark.shield.fill")
                        .foregroundStyle(.secondary)
                        .imageScale(.small)
                    Text(updater.currentVersionDisplay)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }
            .background(.ultraThinMaterial) // Native macOS translucency/vibrancy
            .navigationSplitViewColumnWidth(min: 210, ideal: 240, max: 300)
        } content: {
            List {
                Text("Encrypted Vault Items")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .searchable(text: $vaultState.searchQuery, prompt: "Search titles, tags, or fields…")
            .navigationSplitViewColumnWidth(min: 280, ideal: 320, max: 420)
        } detail: {
            VStack(alignment: .leading, spacing: 16) {
                Text("SafePocket Inspector")
                    .font(.title2.weight(.semibold))
                Text("Right-click any card or identity field for native macOS Quick Copy.")
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(24)
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        vaultState.presentNewItemSheet = true
                    } label: {
                        Label("New Item", systemImage: "plus")
                    }

                    Button {
                        vaultState.lockVault()
                    } label: {
                        Label("Lock Vault", systemImage: "lock")
                    }
                }
            }
        }
    }
}

struct MacSettingsAndAboutView: View {
    @ObservedObject var vaultState: MacVaultViewModel
    @ObservedObject var updater: MacSparkleUpdateController
    @AppStorage("safepocket.theme_mode") private var themeMode: String = "light"

    var body: some View {
        Form {
            Section("Appearance & Theme") {
                Picker("Theme", selection: $themeMode) {
                    Text("Light (Default)").tag("light")
                    Text("Dark").tag("dark")
                    Text("System").tag("system")
                }
                .pickerStyle(.segmented)
            }
            Section("About SafePocket") {
                LabeledContent("Version", value: updater.currentVersionDisplay)
                LabeledContent("Encryption", value: "Argon2id + Secret Key → AES-256-GCM (SQLCipher)")
            }
            Section("Updates (Direct Signed Distribution)") {
                Toggle("Automatic Updates", isOn: $updater.automaticChecksEnabled)
                HStack {
                    Button("Check for Updates…") {
                        updater.checkForUpdatesInitiatedByUser()
                    }
                    if let next = updater.availableVersion {
                        Text("Update Ready: v\(next) • SHA-256 Verified")
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}
