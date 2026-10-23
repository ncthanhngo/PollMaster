import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

struct TemplatesView: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var pickingFile = false
    @State private var askingURL = false
    @State private var urlText = ""
    @State private var overwrite: (Template, Data)?
    @State private var message: String?
    @State private var sheet: ImportSheet?
    @State private var deleting: Template?
    @State private var loadingURL = false

    private enum ImportSheet: Identifiable {
        case paste, errors([String])
        var id: String { if case .errors = self { return "errors" } else { return "paste" } }
    }

    var body: some View {
        NavigationStack {
            List {
                Section { AITemplateCard { sheet = .paste } }
                    .listRowBackground(LinearGradient(colors: [Color.accentColor.opacity(0.14), theme.cardBackground],
                                                      startPoint: .topLeading, endPoint: .bottomTrailing))
                Section {
                    if env.templates.isEmpty {
                        ContentUnavailableView("Chưa có mẫu", systemImage: "doc.text",
                                               description: Text("Nhấn + để nhập mẫu từ tệp, URL hoặc dán JSON."))
                    }
                    ForEach(env.templates) { t in
                        NavigationLink(value: t.key) { templateRow(t) }
                            .swipeActions { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = t } }
                    }
                } header: { Text("Mẫu trên máy (\(env.templates.count))") }
            }
            .listSectionSpacing(20)
            .overlay { loadingOverlay }
            .navigationTitle("Mẫu")
            .navigationDestination(for: String.self) { key in
                if let t = env.templates.first(where: { $0.key == key }) { TemplateDetailView(template: t) }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { AppearanceToggleButton() }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Nhập từ tệp", systemImage: "doc") { pickingFile = true }
                        Button("Nhập từ URL", systemImage: "link") { urlText = ""; askingURL = true }
                        Button("Dán JSON", systemImage: "doc.on.clipboard") { sheet = .paste }
                    } label: { Image(systemName: "plus") }
                }
            }
            .fileImporter(isPresented: $pickingFile, allowedContentTypes: [.json]) { result in
                switch result {
                case .success(let url):
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do { try handle(Data(contentsOf: url)) } catch { fail(error) }
                case .failure(let e): fail(e)
                }
            }
            .sheet(item: $sheet) { s in
                switch s {
                case .paste: PasteTemplateSheet { importPasted($0) }
                case .errors(let lines): ImportErrorsSheet(lines: lines)
                }
            }
            .alert("Nhập từ URL", isPresented: $askingURL) {
                TextField("https://…/mau.json", text: $urlText).textInputAutocapitalization(.never).keyboardType(.URL)
                Button("Tải") { fetch() }
                Button("Hủy", role: .cancel) {}
            }
            .alert("Ghi đè?", isPresented: Binding(get: { overwrite != nil }, set: { if !$0 { overwrite = nil } })) {
                Button("Ghi đè", role: .destructive) { confirmOverwrite() }
                Button("Hủy", role: .cancel) { overwrite = nil }
            } message: { Text("Mẫu \(overwrite?.0.id ?? "") v\(overwrite?.0.version ?? "") đã có trên máy.") }
            .alert("Thông báo", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") { message = nil }
            } message: { Text(message ?? "") }
            .confirmationDialog("Xóa mẫu này?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("Xóa", role: .destructive) { if let d = deleting { env.deleteTemplate(d) }; deleting = nil }
            }
        }
    }

    private func templateRow(_ t: Template) -> some View {
        let old = !env.newestTemplates.contains { $0.key == t.key }
        return HStack(alignment: .top, spacing: 12) {
            IconBadge(systemImage: "doc.text.fill", tint: old ? theme.secondaryText : .accentColor)
            VStack(alignment: .leading, spacing: 6) {
                Text(t.title).font(.headline).lineLimit(2)
                HStack(spacing: 6) {
                    Text(t.id).font(.caption.weight(.medium)).secondaryText()
                    StatusPill(text: "v\(t.version)", color: .accentColor)
                    StatusPill(text: "\(t.displayFieldCount) trường", neutral: true)
                    if old { StatusPill(text: "Bản cũ", color: theme.warning) }
                }
                let used = env.projects(using: t)
                if !used.isEmpty {
                    HStack(spacing: 6) {
                        HStack(spacing: -3) { ForEach(used.prefix(4)) { ProjectDot(project: $0, size: 10) } }
                        Text("Dùng trong: " + used.map(\.code).joined(separator: ", ")).lineLimit(1).truncationMode(.tail)
                    }
                    .font(.caption).secondaryText()
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var loadingOverlay: some View {
        if loadingURL {
            ProgressView("Đang tải…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
    }


    private func handle(_ data: Data) throws {
        switch try env.importTemplate(data) {
        case .installed(let t):
            if env.activeProjects.isEmpty { message = "Đã nhập mẫu \(t.title)."; return }
            Task { try? await Task.sleep(for: .milliseconds(500)); env.addToProjectsFor = t }
        case .needsOverwrite(let t, let d): overwrite = (t, d)
        }
    }

    private func fail(_ error: Error) { sheet = .errors(TemplateText.errorLines(error)) }

    private func importPasted(_ text: String) {
        let json = TemplateText.extractJSON(text) ?? text
        do { sheet = nil; try handle(Data(json.utf8)) } catch { fail(error) }
    }

    private func confirmOverwrite() {
        guard let (_, data) = overwrite else { return }
        overwrite = nil
        do { try env.overwriteTemplate(data); message = "Đã ghi đè mẫu." } catch { fail(error) }
    }

    private func fetch() {
        guard let url = URL(string: urlText.trimmingCharacters(in: .whitespacesAndNewlines)), ["http", "https"].contains(url.scheme?.lowercased()) else {
            sheet = .errors(["URL không hợp lệ (cần http hoặc https)."]); return
        }
        loadingURL = true
        Task {
            defer { loadingURL = false }
            do {
                var req = URLRequest(url: url); req.timeoutInterval = 20
                let (data, resp) = try await URLSession.shared.data(for: req)
                if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                    sheet = .errors(["Máy chủ trả về lỗi HTTP \(http.statusCode)."]); return
                }
                guard data.count < 5_000_000 else { sheet = .errors(["Tệp quá lớn."]); return }
                try handle(data)
            } catch { fail(error) }
        }
    }
}
