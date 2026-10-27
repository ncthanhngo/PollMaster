import SwiftUI
import PollMasterCore

/// Opens the draft named by `AppEnvironment.editingDraftId` (full screen): loads it and its base version, then shows the editor.
struct TemplateEditorHost: View {
    let draftId: String
    @Environment(AppEnvironment.self) private var env
    @Environment(\.scenePhase) private var phase
    @State private var model: TemplateEditorModel?

    var body: some View {
        Group {
            if let model {
                TemplateEditorView(model: model)
                    .onChange(of: phase) { _, p in if p != .active { model.flush() } }
            } else {
                ContentUnavailableView {
                    Label("Không mở được bản nháp", systemImage: "exclamationmark.triangle")
                } description: { Text("Bản nháp không còn trên máy.") } actions: { Button("Đóng") { env.editingDraftId = nil } }
            }
        }
        .onAppear {
            guard model == nil, let d = env.templateDrafts.first(where: { $0.draftId == draftId }) ?? env.draftStore.load(draftId) else { return }
            let base = d.baseId.flatMap { id in d.baseVersion.flatMap { env.templateStore.data(id: id, version: $0) } }
                .flatMap { String(data: $0, encoding: .utf8) }.flatMap { try? TemplateDoc(text: $0) }
            model = TemplateEditorModel(draft: d, base: base) { [weak env] in env?.saveDraft($0) }
            #if DEBUG
            model?.applyDebugLaunch()
            #endif
        }
    }
}

/// Table of contents of the draft: info, header, sections, signatures.
struct TemplateEditorView: View {
    let model: TemplateEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var deleting: Int?
    @State private var discarding = false

    var body: some View {
        NavigationStack(path: Bindable(model).path) {
            List {
                Section { issuesChip }.listRowBackground(Color.clear).listRowInsets(EdgeInsets())
                Section {
                    link("Thông tin mẫu", model.text([], "title"), "doc.text.fill", .info)
                    link("Đầu biên bản", "\(model.list(["header"]).count) trường", "person.text.rectangle", .header)
                }
                sectionsBlock
                Section {
                    link("Chữ ký", "\(model.list(["signatures"]).count) ô", "signature", .signatures)
                    Button("Bỏ bản nháp này", systemImage: "trash", role: .destructive) { discarding = true }.frame(minHeight: 44)
                }
            }
            .navigationTitle(model.text([], "shortName").isEmpty ? model.draft.templateId : model.text([], "shortName"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { model.flush(); env.editingDraftId = nil } }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { open(.preview) } label: { Image(systemName: "eye") }.accessibilityLabel("Xem trước")
                    Button { open(.publish) } label: { Text("Phát hành").bold() }
                        .opacity(model.errors.isEmpty ? 1 : 0.45)
                }
            }
            .navigationDestination(for: EditorRoute.self) { destination($0) }
        }
        .sheet(item: Binding(get: { model.sheet == .preview ? nil : model.sheet }, set: { model.sheet = $0 })) { s in
            switch s {
            case .issues: IssuesSheet(model: model)
            case .publish: PublishSheet(model: model)
            case .preview: EmptyView()
            }
        }
        .fullScreenCover(isPresented: Binding(get: { model.sheet == .preview }, set: { if !$0 { model.sheet = nil } })) { TemplatePreviewView(model: model) }
        .onDisappear { model.flush() }
        .alert("Không thực hiện được", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .confirmationDialog("Bỏ bản nháp?", isPresented: $discarding, titleVisibility: .visible) {
            Button("Bỏ bản nháp", role: .destructive) { model.finish(); env.discardDraft(model.draft.draftId); env.editingDraftId = nil }
        } message: { Text("Mọi thay đổi chưa phát hành sẽ mất. Mẫu đã cài trên máy không đổi.") }
    }

    private func open(_ s: EditorSheet) {
        model.relint()
        if s == .publish && !model.errors.isEmpty || s == .preview && !model.errors.isEmpty { model.sheet = .issues } else { model.sheet = s }
    }

    @ViewBuilder private func destination(_ r: EditorRoute) -> some View {
        switch r {
        case .info: TemplateInfoForm(model: model)
        case .header: HeaderEditorView(model: model)
        case .signatures: SignaturesView(model: model)
        case .section(let i): SectionEditorView(model: model, index: i)
        case .item(let p): ItemFormView(model: model, path: p)
        case .json(let p): JSONNodeEditor(model: model, path: p)
        }
    }

    private func link(_ title: String, _ detail: String, _ icon: String, _ route: EditorRoute) -> some View {
        Button { model.path.append(route) } label: {
            HStack(spacing: 12) {
                IconBadge(systemImage: icon, size: 32)
                Text(title).font(.subheadline.weight(.medium))
                Spacer(minLength: 8)
                Text(detail).font(.caption).secondaryText().lineLimit(1)
                Image(systemName: "chevron.right").font(.caption.weight(.bold)).secondaryText()
            }
            .frame(minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// "2 lỗi · 1 cảnh báo": opens the list.
    private var issuesChip: some View {
        let e = model.errors.count, w = model.warnings.count
        let color = e > 0 ? theme.alert : (w > 0 ? theme.warning : theme.success)
        return Button { model.sheet = .issues } label: {
            HStack(spacing: 8) {
                Image(systemName: e > 0 ? "exclamationmark.circle.fill" : (w > 0 ? "exclamationmark.triangle.fill" : "checkmark.circle.fill"))
                Text(e + w == 0 ? "Không có lỗi" : "\(e) lỗi · \(w) cảnh báo").font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                if e + w > 0 { Text("Xem").font(.subheadline); Image(systemName: "chevron.right").font(.caption.weight(.bold)) }
            }
            .foregroundStyle(color).padding(.horizontal, PMSpace.m).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .background(color.opacity(0.14), in: RoundedRectangle(cornerRadius: PMRadius.m))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(e + w == 0 ? "Không có lỗi" : "\(e) lỗi, \(w) cảnh báo. Mở danh sách")
    }

    private var sectionsBlock: some View {
        let list = model.list(["sections"])
        return Section {
            ForEach(list.indices, id: \.self) { i in
                let o = list[i].object ?? [:], p: DocPath = ["sections", .index(i)], n = (o["items"]?.array ?? []).count
                EditorRow(icon: "list.bullet.rectangle", code: o["id"]?.string, title: o["title"]?.string ?? "", subtitle: "\(n) phần tử",
                          canUp: i > 0, canDown: i < list.count - 1, issue: model.severity(under: p),
                          open: { model.path.append(.section(i)) }, up: { model.move(["sections"], i, by: -1) }, down: { model.move(["sections"], i, by: 1) }) {
                    Button("Sửa", systemImage: "pencil") { model.path.append(.section(i)) }
                    Button("Sửa JSON", systemImage: "curlybraces") { model.path.append(.json(p)) }
                    Button("Xoá", systemImage: "trash", role: .destructive) { deleting = i }
                }
            }
            Button {
                if model.apply(.newItem(path: ["sections"], index: nil, kind: "section", type: nil)) { model.path.append(.section(model.list(["sections"]).count - 1)) }
            } label: { Label("Thêm mục", systemImage: "plus.circle.fill").frame(minHeight: 44) }
        } header: { Text("Các mục (\(list.count))") }
        .confirmationDialog(deleting.map { "Xoá mục \"\(list.indices.contains($0) ? (list[$0].object?["title"]?.string ?? "") : "")\"?" } ?? "",
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Xoá mục và mọi phần tử trong đó", role: .destructive) { if let i = deleting { model.apply(.delete(path: ["sections"], index: i)) } }
        } message: {
            if let i = deleting, !model.references(afterDeleting: ["sections"], at: i).isEmpty {
                let refs = model.references(afterDeleting: ["sections"], at: i)
                Text("Đang được dùng ở:\n" + refs.prefix(6).map { "• " + model.place(of: $0.path) }.joined(separator: "\n") + (refs.count > 6 ? "\n… và \(refs.count - 6) nơi nữa" : ""))
            } else { Text("Phiếu đã tạo giữ nguyên dữ liệu.") }
        }
    }
}
