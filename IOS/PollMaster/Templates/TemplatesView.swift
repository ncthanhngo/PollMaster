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
    @State private var cloning = false

    private enum ImportSheet: Identifiable {
        case ai, paste, errors([String])
        var id: String {
            switch self { case .ai: "ai"; case .paste: "paste"; case .errors: "errors" }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section { aiRow }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                TemplateDraftsSection()
                Section {
                    if env.templates.isEmpty {
                        ContentUnavailableView("Chưa có mẫu", systemImage: "doc.text",
                                               description: Text("Nhấn + để nhập mẫu, hoặc tạo mẫu bằng AI."))
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
                        Button("Tạo mẫu bằng AI", systemImage: "sparkles") { sheet = .ai }
                        if env.canCreateTemplate {
                            Menu("Tạo mẫu mới", systemImage: "square.and.pencil") {
                                Button("Mẫu trống", systemImage: "doc") { env.createTemplate() }
                                Button("Nhân bản từ mẫu…", systemImage: "doc.on.doc") { cloning = true }
                            }
                        }
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
            .sheet(isPresented: $cloning) { CloneTemplateSheet() }
            .sheet(item: $sheet) { s in
                switch s {
                case .ai: AIGuideSheet { sheet = .paste }
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

    /// One-line brand strip at the top: the AI flow is the first thing offered, at the height of a single row.
    private var aiRow: some View {
        Button { sheet = .ai } label: {
            HStack(spacing: 10) {
                Image(systemName: "sparkles").font(.body.weight(.semibold))
                Text("Tạo mẫu bằng AI").font(.body.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 8)
                Text("Bắt đầu").font(.subheadline.weight(.semibold)).lineLimit(1)
                Image(systemName: "chevron.right").font(.footnote.weight(.bold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, PMSpace.m)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(theme.highContrast ? AnyShapeStyle(Color.black) : AnyShapeStyle(LinearGradient(
                colors: [Color(red: 0.13, green: 0.50, blue: 0.42), Color(red: 0.08, green: 0.32, blue: 0.27)],
                startPoint: .leading, endPoint: .trailing)), in: RoundedRectangle(cornerRadius: PMRadius.m))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Gửi hướng dẫn cho AI rồi dán JSON kết quả")
    }

    /// Two lines, never wrapped: title (full width), then "BB-TDP-01 · v1.2 · 180 trường" + avatars of the projects using it.
    private func templateRow(_ t: Template) -> some View {
        let old = !env.newestTemplates.contains { $0.key == t.key }
        let used = env.projects(using: t)
        return HStack(spacing: 12) {
            IconBadge(systemImage: "doc.text.fill", tint: old ? theme.secondaryText : .accentColor, size: 36)
            VStack(alignment: .leading, spacing: 3) {
                Text(t.title).font(.body.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                HStack(spacing: 8) {
                    (Text("\(t.id) · v\(t.version) · \(t.displayFieldCount) trường")
                        + (old ? Text(" · Bản cũ").foregroundColor(theme.warning) : Text("")))
                        .font(.footnote).secondaryText().lineLimit(1)
                    if !used.isEmpty {
                        HStack(spacing: -3) { ForEach(used.prefix(3)) { ProjectAvatar(project: $0, size: 16) } }
                            .accessibilityElement().accessibilityLabel("Dùng trong \(used.count) dự án: \(used.map(\.name).joined(separator: ", "))")
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private var loadingOverlay: some View {
        if loadingURL {
            ProgressView("Đang tải…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: PMRadius.m))
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
