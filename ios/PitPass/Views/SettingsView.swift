import SwiftUI

/// Account, server and offline storage — the app's counterpart to the
/// website's Manage pages, in the same panel vocabulary (hairline sections
/// on the panel surface, amber only on the one primary action).
struct SettingsView: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var serverText = ""
    @State private var serverError: String?
    @State private var stats: OfflineStore.Stats?
    @State private var confirmSignOut = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: PP.Space.s5) {
                    account
                    appearance
                    server
                    storage
                }
                .padding(PP.Space.s5)
                .frame(maxWidth: 640, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(PP.bg.ignoresSafeArea())
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(PP.bg, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.font(PP.sans(PP.TextSize.sm, weight: 600)).tint(PP.accentInk)
                }
            }
            .task {
                serverText = session.serverURL.absoluteString
                stats = await session.store.stats()
            }
        }
        .tint(PP.accentInk)
        .presentationBackground(PP.bg)
    }

    private var account: some View {
        Section_(title: "Account") {
            if case let .ready(me) = session.phase {
                Row(label: "Signed in as", value: me.authEnabled ? (me.email ?? "—") : "Open server (auth off)", mono: me.authEnabled)
                Row(label: "Role", value: me.isAdmin ? "Admin" : "Viewer")
                if me.authEnabled {
                    HStack {
                        Spacer()
                        Button("Sign out", role: .destructive) { confirmSignOut = true }
                            .buttonStyle(PPSecondaryButtonStyle())
                    }
                    .padding(.vertical, PP.Space.s2)
                    .confirmationDialog("Sign this iPad out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                        Button("Sign out", role: .destructive) { Task { await session.signOut(); dismiss() } }
                    } message: {
                        Text("Offline data on this iPad is cleared too.")
                    }
                }
            } else {
                Text("Not signed in").ppBody().padding(.vertical, PP.Space.s2)
            }
        }
    }

    private var appearance: some View {
        Section_(title: "Appearance") {
            HStack {
                Text("Theme").ppLabel()
                Spacer()
                ThemeToggle()
            }
            .padding(.vertical, PP.Space.s2)
        }
    }

    private var server: some View {
        Section_(title: "Server", footer: "Changing servers signs this iPad out and clears its offline data.") {
            VStack(alignment: .leading, spacing: PP.Space.s3) {
                TextField("https://pitpass.arjunakankipati.com", text: $serverText)
                    .font(PP.mono(PP.TextSize.sm))
                    .foregroundStyle(PP.text)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.vertical, 7)
                    .padding(.horizontal, 12)
                    .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
                if let serverError { ErrorPanel(message: serverError) }
                HStack(spacing: PP.Space.s2) {
                    Button("Use this server") { apply(serverText) }
                        .buttonStyle(PPPrimaryButtonStyle(compact: true))
                        .disabled(serverText.isEmpty)
                    Button("Production") { apply(ServerConfig.production.absoluteString) }
                        .buttonStyle(PPSecondaryButtonStyle())
                    Button("Local dev server") { apply(ServerConfig.localDev.absoluteString) }
                        .buttonStyle(PPSecondaryButtonStyle())
                }
            }
            .padding(.vertical, PP.Space.s2)
        }
    }

    private var storage: some View {
        Section_(title: "Offline storage", footer: "Everything the iPad has read is kept. Download an event or a season to keep pages you haven’t opened yet.") {
            if let stats {
                Row(label: "Documents", value: "\(stats.entries)", mono: true)
                Row(label: "Size", value: ByteCountFormatter.string(fromByteCount: Int64(stats.bytes), countStyle: .file), mono: true)
            }
            downloads
            HStack {
                Spacer()
                Button("Clear offline data", role: .destructive) {
                    Task { await session.clearOfflineData(); stats = await session.store.stats() }
                }
                .buttonStyle(PPSecondaryButtonStyle())
            }
            .padding(.vertical, PP.Space.s2)
        }
        .task(id: session.downloads.recordsNewestFirst.map(\.completedAt)) {
            stats = await session.store.stats()
        }
    }

    /// What "Download this event / season" completed, newest first.
    @ViewBuilder private var downloads: some View {
        let records = session.downloads.recordsNewestFirst
        if records.isEmpty {
            Text("Nothing downloaded yet — open an event sheet or a season and tap Download.")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                .padding(.vertical, 10)
                .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
        } else {
            ForEach(records) { r in
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.title).font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.text).lineLimit(1)
                            Text(downloadDetail(r, now: context.date))
                                .font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                        }
                        Spacer()
                        Text(ByteCountFormatter.string(fromByteCount: Int64(r.bytes), countStyle: .file))
                            .font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                    }
                    .padding(.vertical, 10)
                    .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
                }
            }
        }
    }

    private func downloadDetail(_ r: OfflineStore.DownloadRecord, now: Date) -> String {
        var parts = ["Downloaded \(ConnectivityPill.age(now.timeIntervalSince(r.completedAt))) ago",
                     "\(r.documents) documents"]
        if r.missing > 0 { parts.append("\(r.missing) missing") }
        return parts.joined(separator: " · ")
    }

    private func apply(_ text: String) {
        guard let url = ServerConfig.parse(text) else {
            serverError = "Enter a full server address, like https://pitpass.example.com"
            return
        }
        serverError = nil
        serverText = url.absoluteString
        Task { await session.setServer(url) }
    }

    /// A titled panel: caption title, hairline-divided rows on the panel surface.
    private struct Section_<Content: View>: View {
        let title: String
        var footer: String?
        @ViewBuilder let content: Content

        var body: some View {
            VStack(alignment: .leading, spacing: PP.Space.s2) {
                Text(title).ppCaption()
                VStack(alignment: .leading, spacing: 0) { content }
                    .padding(.horizontal, PP.Space.s4)
                    .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous).strokeBorder(PP.border))
                if let footer {
                    Text(footer).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                }
            }
        }
    }

    private struct Row: View {
        let label: String
        let value: String
        var mono = false

        var body: some View {
            HStack(alignment: .firstTextBaseline) {
                Text(label).ppLabel()
                Spacer()
                Text(value)
                    .font(mono ? PP.mono(PP.TextSize.sm) : PP.sans(PP.TextSize.sm))
                    .foregroundStyle(PP.text)
                    .textSelection(.enabled)
            }
            .padding(.vertical, 10)
            .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
        }
    }
}
