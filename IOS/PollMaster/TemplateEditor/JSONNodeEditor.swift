import SwiftUI
import PollMasterCore

/// Advanced JSON of one node (or the whole template for `path == []`): checked 300 ms after typing, applied only when it parses.
struct JSONNodeEditor: View {
    let model: TemplateEditorModel
    let path: DocPath
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pmTheme) private var theme
    @State private var text = ""
    @State private var report: Report = .checking
    @State private var task: Task<Void, Never>?

    private enum Report {
        case checking
        case broken(String)
        case parsed(errors: [TemplateIssue], warnings: [TemplateIssue])
    }

    private var broken: Bool { if case .broken = report { return true }; return false }
    private var title: String { path.isEmpty ? "JSON cả mẫu" : "JSON · " + (model.text(path, "id").isEmpty ? "phần tử" : model.text(path, "id")) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Dành cho người rành JSON: công thức, điều kiện hiện, tra cứu, bấm giờ… Lỗi hiện ngay dưới ô; chỉ Áp dụng được khi JSON đọc được.")
                    .font(.footnote).secondaryText()
                MonoTextView(text: $text).frame(minHeight: 280).accessibilityLabel("Nội dung JSON")
                reportView
                HStack(spacing: 10) {
                    Button("Sao chép", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }
                    Button("Dán", systemImage: "doc.on.clipboard") { if let s = UIPasteboard.general.string { text = s } }
                    Button("Hoàn lại", systemImage: "arrow.uturn.backward") { text = current }
                }
                .buttonStyle(.bordered).font(.subheadline).lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Áp dụng", action: apply).disabled(broken || text == current) } }
        .onAppear { text = current; schedule() }
        .onChange(of: text) { schedule() }
    }

    private var current: String { model.node(path).map { TemplateDoc(root: $0).pretty() } ?? "{}" }

    @ViewBuilder private var reportView: some View {
        switch report {
        case .checking: Label("Đang kiểm tra…", systemImage: "hourglass").font(.footnote).secondaryText()
        case .broken(let m): Label(m, systemImage: "xmark.octagon.fill").font(.footnote).foregroundStyle(theme.alert)
        case .parsed(let errors, let warnings):
            if errors.isEmpty && warnings.isEmpty { Label("JSON hợp lệ.", systemImage: "checkmark.circle.fill").font(.footnote).foregroundStyle(theme.success) }
            ForEach(errors, id: \.self) { issueLine($0, theme.alert, "exclamationmark.circle.fill") }
            ForEach(warnings, id: \.self) { issueLine($0, theme.warning, "exclamationmark.triangle.fill") }
        }
    }

    private func issueLine(_ i: TemplateIssue, _ color: Color, _ icon: String) -> some View {
        Label { Text("\(model.place(of: i.path)): \(i.message)") } icon: { Image(systemName: icon) }.font(.footnote).foregroundStyle(color)
    }

    private func schedule() {
        task?.cancel(); report = .checking
        let snapshot = text
        task = Task {
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            report = check(snapshot)
        }
    }

    /// Parse, then lint the whole template as it would be after applying; only findings the edit adds are listed.
    private func check(_ t: String) -> Report {
        let node: JSONValue
        do { node = try TemplateDoc.parse(t) } catch { return .broken(error.localizedDescription) }
        guard node.object != nil else { return .broken(TemplateEditError.notObject.localizedDescription) }
        if path.isEmpty, !model.isNew, node.object?["id"]?.string != model.draft.templateId {
            return .broken("Mã mẫu \(model.draft.templateId) không đổi được khi sửa mẫu có sẵn.")
        }
        guard let trial = try? TemplateEdit.apply(model.doc, .replace(path: path, node: node)) else { return .broken(TemplateEditError.badPath.localizedDescription) }
        let before = Set(model.issues.map { $0.path + $0.message })
        let added = trial.lint(base: model.base, isNewTemplate: model.isNew).filter { !before.contains($0.path + $0.message) }
        return .parsed(errors: added.filter { $0.severity == .error }, warnings: added.filter { $0.severity == .warning })
    }

    private func apply() {
        guard model.applyJSON(path, text) else { return }
        dismiss()
    }
}
