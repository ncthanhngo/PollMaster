import SwiftUI
import PollMasterCore

struct TemplateDetailView: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env

    private var isNewest: Bool { env.newestTemplates.contains { $0.key == template.key } }

    var body: some View {
        List {
            Section {
                if let s = template.subtitle { Text(s).font(.subheadline) }
                Text("\(template.id) · v\(template.version)").font(.caption).secondaryText()
            }
            if let i = template.instructions {
                Section("Hướng dẫn") { Text(i).font(.footnote) }
            }
            Section("Các mục") {
                Text("\(Validator.headerSectionTitle) (\(template.header.count) trường)")
                ForEach(template.sections) { s in
                    Text("\(s.title) (\(s.fields.count) trường\(s.tables.isEmpty ? "" : ", \(s.tables.count) bảng"))")
                }
            }
            Section {
                if !isNewest {
                    Text("Phiên bản cũ: chỉ dùng cho các phiếu đã tạo. Phiếu mới được tạo từ phiên bản mới nhất.").font(.footnote).secondaryText()
                }
                Button { env.startCreate(from: template) } label: { Text("Tạo phiếu mới").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(!isNewest)
            }
        }
        .navigationTitle(template.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
