import SwiftUI
import PollMasterCore

struct TemplateDetailView: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        List {
            Section {
                if let s = template.subtitle { Text(s).font(.subheadline) }
                Text("\(template.id) · v\(template.version)").font(.caption).foregroundStyle(.secondary)
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
                Button { env.createRecord(from: template) } label: { Text("Tạo phiếu mới").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
            }
        }
        .navigationTitle(template.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
