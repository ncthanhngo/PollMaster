import SwiftUI
import PollMasterCore

/// Preview of an opened `.pmproject` (name, code, templates, sites), the ERP project to import it into and the Nhập button,
/// or the list of errors.
struct ProjectImportSheet: View {
    let incoming: IncomingBundle
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var askOverwrite = false
    /// Proposed: the bundle's own project when this account sees it.
    @State private var targetId: String?

    private var target: Project? { env.project(id: targetId) }

    var body: some View {
        NavigationStack {
            List {
                if let b = incoming.bundle, let plan = incoming.plan { preview(b, plan) } else { errors }
            }
            .navigationTitle("Gói dự án").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(incoming.bundle == nil ? "Đóng" : "Hủy") { close() } }
                if incoming.bundle != nil {
                    ToolbarItem(placement: .confirmationAction) { Button("Nhập") { start() }.disabled(target == nil) }
                }
            }
            .confirmationDialog("Mẫu đã có trên máy với nội dung khác. Ghi đè?", isPresented: $askOverwrite, titleVisibility: .visible) {
                Button("Ghi đè", role: .destructive) { if let target { env.importIncoming(into: target, overwriteTemplates: true) } }
                Button("Giữ mẫu hiện có") { if let target { env.importIncoming(into: target, overwriteTemplates: false) } }
                Button("Hủy", role: .cancel) {}
            } message: { Text((incoming.plan?.conflicts ?? []).map { "\($0.id) v\($0.version)" }.joined(separator: ", ")) }
        }
        .presentationDetents([.medium, .large])
        .onAppear { if targetId == nil { targetId = incoming.plan?.existing?.id } }
    }

    private func close() { env.incomingBundle = nil }

    private func start() {
        guard let target else { return }
        if incoming.plan?.conflicts.isEmpty == false { askOverwrite = true } else { env.importIncoming(into: target, overwriteTemplates: false) }
    }

    @ViewBuilder private func preview(_ b: ProjectBundle, _ plan: ProjectImporter.Plan) -> some View {
        Section {
            HStack(spacing: 10) {
                ProjectAvatar(project: b.project, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(b.project.name).font(.title3.bold())
                    Text(b.project.code).font(.subheadline.monospaced()).secondaryText()
                }
            }
            if let c = b.project.client { LabeledContent("Khách hàng", value: c) }
            LabeledContent("Số mẫu", value: "\(b.templates.count)")
            LabeledContent("Số điểm", value: b.sites.map { "\($0.count)" } ?? "Không có")
        }
        Section("Mẫu trong gói") {
            ForEach(b.templates, id: \.template.key) { e in
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.template.title).font(.subheadline)
                    Text("\(e.template.id) · v\(e.template.version)\(plan.conflicts.contains { $0.key == e.template.key } ? " · khác bản trên máy" : "")")
                        .font(.caption).secondaryText()
                }
            }
        }
        Section {
            Picker("Nhập vào dự án", selection: $targetId) {
                Text("Chọn dự án").tag(String?.none)
                ForEach(projectKindSections, id: \.kind) { k in
                    ForEach(env.projects(ofKind: k.kind)) { Text($0.name).tag(Optional($0.id)) }
                }
            }
        } footer: {
            Text("Mẫu trong gói được thêm vào dự án (thay bản cũ cùng mã), danh sách điểm thay bằng danh sách trong gói; phiếu hiện có không bị động tới.")
        }
    }

    @ViewBuilder private var errors: some View {
        Section("Không nhập được gói") { ForEach(incoming.errors, id: \.self) { Text($0).font(.footnote) } }
        Section {
            Button(copied ? "Đã sao chép" : "Sao chép lỗi", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = incoming.errors.joined(separator: "\n")
                copied = true
            }
        }
    }
}
