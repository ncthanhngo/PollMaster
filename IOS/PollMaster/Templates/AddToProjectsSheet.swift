import SwiftUI
import PollMasterCore

/// After a template was imported or pasted: "Thêm vào dự án …?" (several projects can be ticked).
struct AddToProjectsSheet: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<String> = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(env.activeProjects) { p in
                        let has = p.templates.contains(TemplateRef(template))
                        Button { if !picked.insert(p.id).inserted { picked.remove(p.id) } } label: {
                            HStack(spacing: 10) {
                                ProjectDot(project: p)
                                VStack(alignment: .leading) { Text(p.name).font(.headline); Text(p.code).font(.footnote).secondaryText() }
                                Spacer()
                                if has { Text("Đã có").font(.caption).secondaryText() }
                                else { Image(systemName: picked.contains(p.id) ? "checkmark.circle.fill" : "circle") }
                            }
                        }
                        .buttonStyle(.plain).disabled(has)
                    }
                } header: { Text("Thêm mẫu \(template.id) v\(template.version) vào dự án") }
            }
            .navigationTitle("Thêm vào dự án?").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Bỏ qua") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Thêm") {
                        for p in env.activeProjects where picked.contains(p.id) { env.addTemplates([template], to: p) }
                        dismiss()
                    }.disabled(picked.isEmpty)
                }
            }
            .onAppear { if let c = env.currentProject, !c.templates.contains(TemplateRef(template)) { picked = [c.id] } }
        }
        .presentationDetents([.medium, .large])
    }
}
