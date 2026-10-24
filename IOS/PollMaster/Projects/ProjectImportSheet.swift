import SwiftUI
import PollMasterCore

/// Preview of an opened `.pmproject` (name, code, templates, sites) with the Nhập button, or the list of errors.
struct ProjectImportSheet: View {
    let incoming: IncomingBundle
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var askOverwrite = false

    var body: some View {
        NavigationStack {
            List {
                if let b = incoming.bundle, let plan = incoming.plan { preview(b, plan) } else { errors }
            }
            .navigationTitle("Gói dự án").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(incoming.bundle == nil ? "Đóng" : "Hủy") { close() } }
                if incoming.bundle != nil {
                    ToolbarItem(placement: .confirmationAction) { Button("Nhập") { start() } }
                }
            }
            .confirmationDialog("Mẫu đã có trên máy với nội dung khác. Ghi đè?", isPresented: $askOverwrite, titleVisibility: .visible) {
                Button("Ghi đè", role: .destructive) { env.importIncoming(overwriteTemplates: true) }
                Button("Giữ mẫu hiện có") { env.importIncoming(overwriteTemplates: false) }
                Button("Hủy", role: .cancel) {}
            } message: { Text((incoming.plan?.conflicts ?? []).map { "\($0.id) v\($0.version)" }.joined(separator: ", ")) }
        }
        .presentationDetents([.medium, .large])
    }

    private func close() { env.incomingBundle = nil }

    private func start() {
        if incoming.plan?.conflicts.isEmpty == false { askOverwrite = true } else { env.importIncoming(overwriteTemplates: false) }
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
            if let existing = plan.existing {
                Label("Cập nhật dự án \(existing.name)?", systemImage: "arrow.triangle.2.circlepath")
                Text("Tên, mẫu và danh sách điểm được cập nhật; phiếu hiện có không bị động tới.").font(.footnote).secondaryText()
            } else if env.projectStore.codes().contains(where: { $0.uppercased() == b.project.code }) {
                Text("Mã \(b.project.code) đã có trên máy, dự án mới sẽ có mã \(ProjectCode.unique(b.project.code, taken: env.projectStore.codes())).")
                    .font(.footnote).secondaryText()
            }
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
