import SwiftUI
import PollMasterCore

/// "Kiểm tra & hoàn thành": errors, warnings, checker notes, signatures, complete / reopen / return.
struct ReviewView: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var confirmReturn = false
    /// Expanded group keys ("kind/sectionId"); nil = default (first group of each kind open).
    @State private var expanded: Set<String>?
    private var missingCount: Int { FillQueue.steps(model.template, model.record).count }

    private typealias Group = (id: String, title: String, items: [(fieldId: String, rowId: String?, sectionId: String, label: String, message: String)])

    private func grouped(_ list: [(String, String, String, String?, String, String)]) -> [Group] {
        var order: [String] = []
        var map: [String: Group] = [:]
        for (sid, stitle, fid, rid, label, msg) in list {
            if map[sid] == nil { order.append(sid); map[sid] = (sid, stitle, []) }
            map[sid]!.items.append((fid, rid, sid, label, msg))
        }
        return order.compactMap { map[$0] }
    }

    /// Not filled yet (required, photo count, table rows) is shown neutral; only wrong values (range, missing KĐĐ reason) are errors.
    private func isMissing(_ i: ValidationIssue) -> Bool { [.required, .minCount, .minRows].contains(i.kind) }
    private var missingGroups: [Group] {
        grouped(model.issues.filter(isMissing).map { ($0.sectionId, $0.sectionTitle, $0.fieldId, $0.rowId, $0.label, $0.message) })
    }
    private var errorGroups: [Group] {
        grouped(model.issues.filter { !isMissing($0) }.map { ($0.sectionId, $0.sectionTitle, $0.fieldId, $0.rowId, $0.label, $0.message) })
    }
    private var warningGroups: [Group] { grouped(model.warnings.map { ($0.sectionId, $0.sectionTitle, $0.fieldId, $0.rowId, $0.label, $0.message) }) }
    private var noteGroups: [Group] { grouped(model.reviewNotes.map { ($0.sectionId, $0.sectionTitle, $0.fieldId, $0.rowId, $0.label, $0.message) }) }

    var body: some View {
        List {
            if model.isLocked { Section { LockedBanner(model: model) } }
            if model.issues.isEmpty || model.isLocked { Section { ProgressRow(progress: model.progress) } }
            if model.issues.isEmpty {
                Section { Label("Không còn lỗi", systemImage: "checkmark.circle.fill").foregroundStyle(theme.success) }
            } else if !model.isLocked {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Còn \(missingCount) mục bắt buộc").font(.title3.weight(.semibold))
                        ProgressView(value: model.progress.fraction)
                        Text("Đã điền \(model.progress.done)/\(model.progress.total)").font(.caption).secondaryText()
                        Button { env.recordsPath.append(.fill(record: model.record.id)) } label: {
                            Label("Điền lần lượt", systemImage: "arrow.right.circle.fill").font(.headline)
                                .frame(maxWidth: .infinity, minHeight: max(36, theme.minTouch))
                        }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                    }
                    .padding(.vertical, 4)
                }
            }
            groupSections("Lỗi", color: theme.alert, icon: "xmark.octagon.fill", errorGroups)
            groupSections("Còn thiếu", color: theme.secondaryText, icon: "circle.dashed", missingGroups)
            groupSections("Cần xem lại", color: theme.warning, icon: "exclamationmark.triangle.fill", warningGroups)
            groupSections("Ghi chú kiểm tra", color: theme.attention, icon: "text.bubble.fill", noteGroups)
            if !model.template.signatures.isEmpty {
                Section("Chữ ký") {
                    ForEach(model.template.signatures) { SignatureRow(model: model, def: $0) }
                }
            }
            Section {
                if model.isLocked && model.reviewMode {
                    Button { confirmReturn = true } label: { Label("Trả lại để sửa", systemImage: "arrow.uturn.backward").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).controlSize(.large).tint(theme.attention)
                }
                Button { model.complete() } label: { Text("Hoàn thành").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    .disabled(!model.issues.isEmpty || model.isLocked)
            } footer: {
                if !model.issues.isEmpty && !model.isLocked { Text("Còn \(missingCount) mục bắt buộc") }
            }
        }
        .navigationTitle("Kiểm tra & hoàn thành")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Trả phiếu về cho người khảo sát sửa? Trạng thái sẽ về Nháp, kèm các ghi chú kiểm tra.", isPresented: $confirmReturn,
                            titleVisibility: .visible) {
            Button("Trả lại để sửa") { model.returnForFix() }
        }
        .onAppear { model.showAllErrors = true }
        .onDisappear { model.flush() }
    }

    private func isOpen(_ key: String, first: Bool) -> Bool { expanded.map { $0.contains(key) } ?? first }

    private func toggle(_ key: String, _ open: Bool, defaults: [String]) {
        var s = expanded ?? Set(defaults)
        if open { s.insert(key) } else { s.remove(key) }
        expanded = s
    }

    /// One collapsible group per section; the first group of each kind starts open.
    @ViewBuilder private func groupSections(_ name: String, color: Color, icon: String, _ groups: [Group]) -> some View {
        let defaults = groups.prefix(1).map { "\(name)/\($0.id)" }
        ForEach(Array(groups.enumerated()), id: \.element.id) { n, g in
            let key = "\(name)/\(g.id)"
            Section {
                DisclosureGroup(isExpanded: Binding(get: { isOpen(key, first: n == 0) }, set: { toggle(key, $0, defaults: defaults) })) {
                    ForEach(Array(g.items.enumerated()), id: \.offset) { _, item in
                        Button { jump(item.fieldId, item.rowId, item.sectionId) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(item.fieldId)\(item.rowId == nil ? "" : " · dòng")  \(item.label)").font(.subheadline).foregroundStyle(.primary)
                                Label(item.message, systemImage: icon).font(.caption).foregroundStyle(color)
                            }
                        }
                    }
                } label: {
                    HStack {
                        Text(g.title).font(.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(g.items.count)").font(.caption.bold()).foregroundStyle(color)
                    }
                }
            } header: { if n == 0 { Text(name) } }
        }
    }

    private func jump(_ fieldId: String, _ rowId: String?, _ sectionId: String) {
        guard let idx = model.sectionIndex(of: sectionId) else { return }
        if let row = rowId {
            env.recordsPath.append(.row(record: model.record.id, table: fieldId, row: row))
        } else {
            env.recordsPath.append(.section(record: model.record.id, index: idx, focus: fieldId))
        }
    }
}

private struct SignatureRow: View {
    let model: RecordEditorModel
    let def: SignatureDef
    @Environment(\.pmTheme) private var theme
    @State private var name = ""
    @State private var error: String?

    private var entry: SignatureEntry? { model.record.signatures[def.id] }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(def.label).font(.subheadline.weight(.semibold))
            TextField("Họ tên người ký", text: $name).textFieldStyle(.plain).inputBox().disabled(model.isLocked)
                .onChange(of: name) { _, n in
                    if var e = entry, e.name != n { e.name = n; model.setSignature(def.id, e) }
                }
            if let e = entry, let img = UIImage(contentsOfFile: model.store.fileURL(model.record.id, e.file).path) {
                Image(uiImage: img).resizable().scaledToFit().frame(height: 120)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: PMRadius.s))
                if !model.isLocked {
                    Button("Xóa chữ ký", role: .destructive) {
                        try? FileManager.default.removeItem(at: model.store.fileURL(model.record.id, e.file))
                        model.setSignature(def.id, nil)
                    }.buttonStyle(.bordered)
                }
            } else if !model.isLocked {
                SignaturePad { png in
                    do {
                        let file = try model.mediaService.saveSignature(png, id: def.id)
                        model.setSignature(def.id, SignatureEntry(file: file, name: name))
                    } catch { self.error = error.localizedDescription }
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(theme.alert) }
        }
        .padding(.vertical, 4)
        .onAppear { name = entry?.name ?? "" }
    }
}
