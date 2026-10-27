import SwiftUI
import PollMasterCore

/// "Phát hành": target project, what changes, the other projects that move along, and the result (stale base, taken code, errors).
struct PublishSheet: View {
    let model: TemplateEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pmTheme) private var theme

    private enum Result: Equatable {
        case done(version: String, failed: [PublishFailure])
        case failed([String])
        case invalid([TemplateIssue])
        case idTaken
    }

    @State private var targetId = ""
    @State private var also: Set<String> = []
    @State private var working = false
    @State private var result: Result?
    @State private var staleVersion: String?

    private var choices: [Project] {
        model.isNew ? env.publishTargets : (env.account == nil ? env.activeProjects : env.managedProjects(using: model.draft.templateId))
    }
    private var target: Project? { choices.first { $0.id == targetId } ?? env.activeProjects.first { $0.id == targetId } }
    private var kind: TemplatePublishRules.Kind { model.isNew ? .newTemplate : TemplatePublishRules.publishKind(base: model.base, draft: model.doc) }
    private var others: [Project] { target.map { env.otherProjects(for: model.draft, excluding: $0) } ?? [] }
    private var allowed: Bool { target.map { env.canChangeTemplates(in: $0) } ?? false }
    private var ready: Bool { model.errors.isEmpty && allowed && !working }

    var body: some View {
        NavigationStack {
            Group {
                if let result { resultView(result) } else { form }
            }
            .navigationTitle("Phát hành").navigationBarTitleDisplayMode(.inline)
            .toolbar { if result == nil { ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } } } }
        }
        .interactiveDismissDisabled(working)
        .onAppear {
            model.relint()
            targetId = choices.contains { $0.id == model.draft.projectId } ? model.draft.projectId : (choices.first?.id ?? model.draft.projectId)
            also = Set(others.map(\.id))
        }
        .alert("Đã có bản mới hơn", isPresented: Binding(get: { staleVersion != nil }, set: { if !$0 { staleVersion = nil } })) {
            Button("Vẫn phát hành") { run(force: true) }
            Button("Huỷ", role: .cancel) {}
        } message: {
            Text("Đã có bản v\(staleVersion ?? "") do người khác phát hành sau khi bạn bắt đầu sửa từ v\(model.draft.baseVersion ?? ""). Phát hành sẽ thay bản đó cho phiếu mới.")
        }
    }

    private var form: some View {
        Form {
            Section("Sẽ phát hành") {
                LabeledContent("Mẫu") { Text("\(model.text([], "title")) (\(model.draft.templateId))").multilineTextAlignment(.trailing) }
                Text(summary).font(.subheadline.weight(.semibold))
                if choices.count > 1 {
                    Picker("Dự án", selection: $targetId) { ForEach(choices) { Text("\($0.name) (\($0.code))").tag($0.id) } }
                        .onChange(of: targetId) { _, id in model.setProject(id); also = Set(others.map(\.id)) }
                } else if let t = target { LabeledContent("Dự án", value: "\(t.name) (\(t.code))") }
            }
            if env.account != nil, kind != .meta, !others.isEmpty {
                Section {
                    ForEach(others) { p in
                        Toggle(isOn: Binding(get: { also.contains(p.id) }, set: { if $0 { also.insert(p.id) } else { also.remove(p.id) } })) {
                            Text("\(p.name) (\(p.code))")
                        }
                    }
                } header: { Text("Chuyển luôn sang bản mới") } footer: { Text("Các dự án khác bạn quản lý đang dùng mẫu này. Bỏ tích dự án nào muốn giữ bản cũ.") }
            }
            Section {
                if !model.errors.isEmpty {
                    Label("Còn \(model.errors.count) lỗi — sửa xong mới phát hành được.", systemImage: "exclamationmark.circle.fill").foregroundStyle(theme.alert)
                    Button("Xem danh sách lỗi") { model.sheet = .issues }.frame(minHeight: 44)
                } else if !allowed {
                    Label(AppEnvironment.templateNeedsManage + ".", systemImage: "wifi.exclamationmark").foregroundStyle(theme.warning)
                }
                if working { HStack { ProgressView(); Text("Đang phát hành…").padding(.leading, 8) } }
                Button { run(force: false) } label: { Text("Phát hành").font(.headline).frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.borderedProminent).disabled(!ready)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)).listRowBackground(Color.clear)
            } footer: { Text("Phiếu đã tạo (kể cả nháp dở) giữ phiên bản cũ. Phiếu tạo sau dùng bản mới.") }
        }
    }

    private var summary: String {
        switch kind {
        case .newTemplate: return "Mẫu mới · v1.0"
        case .meta: return "Chỉ đổi tên, viết tắt hoặc màu — không tạo phiên bản mới."
        case .newVersion:
            let known = env.templates.filter { $0.id == model.draft.templateId }.map(\.version)
            return "v\(model.draft.baseVersion ?? "?") → v\(TemplatePublishRules.nextVersion(known: known))"
        }
    }

    private func run(force: Bool) {
        guard let target, ready || force else { return }
        model.flush()
        working = true
        Task {
            defer { working = false }
            do {
                switch try await env.publishDraft(model.draft, to: target, also: Array(also), force: force) {
                case .published(let v, let failed): model.finish(); env.reloadDrafts(); result = .done(version: v, failed: failed)
                case .staleBase(let v): staleVersion = v
                case .invalid(let list): result = .invalid(list)
                case .idTaken: result = .idTaken
                }
            } catch { result = .failed(TemplateText.errorLines(error)) }
        }
    }

    @ViewBuilder private func resultView(_ r: Result) -> some View {
        List {
            switch r {
            case .done(let v, let failed):
                Section {
                    VStack(spacing: 8) {
                        Image(systemName: "checkmark.seal.fill").font(.system(size: 44)).foregroundStyle(theme.success)
                        Text(kind == .meta ? "Đã cập nhật tên, viết tắt và màu" : "Đã phát hành v\(v)").font(.headline)
                    }.frame(maxWidth: .infinity)
                }
                if !failed.isEmpty {
                    Section {
                        ForEach(failed, id: \.projectId) { f in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(env.project(id: f.projectId)?.name ?? f.projectId).font(.subheadline.weight(.semibold))
                                Text(f.message).font(.footnote).foregroundStyle(theme.alert)
                            }
                        }
                    } header: { Text("Chưa chuyển được sang bản mới") } footer: { Text("Bản chính đã phát hành. Dùng \"Quay lại bản trước\" hay chọn lại bản ở màn các dự án này.") }
                }
                closeButton("Xong") { dismiss(); env.editingDraftId = nil }
            case .failed(let lines):
                Section("Chưa phát hành được") { ForEach(lines, id: \.self) { Text($0).foregroundStyle(theme.alert) } }
                Section { Text("Bản nháp vẫn còn trên máy. Gửi lại sau cũng không tạo bản trùng.").font(.footnote).secondaryText() }
                closeButton("Quay lại") { result = nil }
            case .invalid(let list):
                Section("Còn lỗi, chưa phát hành") { ForEach(list, id: \.self) { Text($0.message).foregroundStyle(theme.alert) } }
                closeButton("Xem danh sách lỗi") { model.relint(); result = nil; model.sheet = .issues }
            case .idTaken:
                Section {
                    Label("Mã mẫu đã có trong tổ chức — đổi mã", systemImage: "exclamationmark.triangle.fill").foregroundStyle(theme.warning)
                }
                closeButton("Đổi mã") { dismiss(); model.path = [.info] }
            }
        }
    }

    private func closeButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Section { Button(action: action) { Text(title).font(.headline).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent) }
            .listRowBackground(Color.clear)
    }
}
