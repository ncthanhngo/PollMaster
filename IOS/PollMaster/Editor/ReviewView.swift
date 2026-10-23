import SwiftUI
import PollMasterCore

/// "Kiểm tra & hoàn thành": issues by section, signatures, complete / reopen.
struct ReviewView: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env

    private var grouped: [(id: String, title: String, issues: [ValidationIssue])] {
        var order: [String] = []
        var map: [String: [ValidationIssue]] = [:]
        for i in model.issues {
            if map[i.sectionId] == nil { order.append(i.sectionId) }
            map[i.sectionId, default: []].append(i)
        }
        return order.map { ($0, map[$0]![0].sectionTitle, map[$0]!) }
    }

    var body: some View {
        List {
            if model.isLocked { Section { LockedBanner(model: model) } }
            Section { ProgressRow(progress: model.progress) }
            if model.issues.isEmpty {
                Section { Label("Không còn lỗi", systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
            }
            ForEach(grouped, id: \.id) { g in
                Section(g.title) {
                    ForEach(g.issues) { issue in
                        Button { jump(issue) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(issue.fieldId)\(issue.rowId == nil ? "" : " · dòng")  \(issue.label)").font(.subheadline).foregroundStyle(.primary)
                                Text(issue.message).font(.caption).foregroundStyle(.red)
                            }
                        }
                    }
                }
            }
            if !model.template.signatures.isEmpty {
                Section("Chữ ký") {
                    ForEach(model.template.signatures) { SignatureRow(model: model, def: $0) }
                }
            }
            Section {
                Button { model.complete() } label: { Text("Hoàn thành").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    .disabled(!model.issues.isEmpty || model.isLocked)
            } footer: {
                if !model.issues.isEmpty { Text("Cần xử lý hết lỗi để hoàn thành.") }
            }
        }
        .navigationTitle("Kiểm tra & hoàn thành")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.showAllErrors = true }
        .onDisappear { model.flush() }
    }

    private func jump(_ issue: ValidationIssue) {
        guard let idx = model.sectionTitles.firstIndex(where: { $0.id == issue.sectionId }) else { return }
        if let row = issue.rowId {
            env.recordsPath.append(.row(record: model.record.id, table: issue.fieldId, row: row))
        } else {
            env.recordsPath.append(.section(record: model.record.id, index: idx, focus: issue.fieldId))
        }
    }
}

private struct SignatureRow: View {
    let model: RecordEditorModel
    let def: SignatureDef
    @State private var name = ""
    @State private var error: String?

    private var entry: SignatureEntry? { model.record.signatures[def.id] }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(def.label).font(.subheadline.weight(.semibold))
            TextField("Họ tên người ký", text: $name).textFieldStyle(.roundedBorder).disabled(model.isLocked)
                .onChange(of: name) { _, n in
                    if var e = entry, e.name != n { e.name = n; model.setSignature(def.id, e) }
                }
            if let e = entry, let img = UIImage(contentsOfFile: model.store.fileURL(model.record.id, e.file).path) {
                Image(uiImage: img).resizable().scaledToFit().frame(height: 120)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
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
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
        .padding(.vertical, 4)
        .onAppear { name = entry?.name ?? "" }
    }
}
