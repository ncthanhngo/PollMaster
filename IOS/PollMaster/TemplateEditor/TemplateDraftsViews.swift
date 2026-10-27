import SwiftUI
import PollMasterCore

extension AppEnvironment {
    /// "sửa lúc 14:05 27/10".
    func editedAtText(_ d: TemplateDraft) -> String {
        guard let date = ISO8601DateFormatter().date(from: d.updatedAt) else { return "" }
        return "sửa lúc " + ISODate.format(date, "HH:mm dd/MM")
    }
}

/// "Bản nháp (n)" at the top of the templates tab: tap resumes, swipe discards.
struct TemplateDraftsSection: View {
    @Environment(AppEnvironment.self) private var env
    @State private var discarding: TemplateDraft?

    var body: some View {
        if !env.templateDrafts.isEmpty {
            Section {
                ForEach(env.templateDrafts, id: \.draftId) { d in
                    Button { env.resume(d) } label: {
                        HStack(spacing: 12) {
                            IconBadge(systemImage: "square.and.pencil", tint: .orange, size: 36)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(d.content.object?["title"]?.string ?? d.templateId).font(.body.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                                Text("\(d.templateId) · \(d.baseVersion.map { "từ v\($0)" } ?? "mẫu mới") · \(env.editedAtText(d))")
                                    .font(.footnote).secondaryText().lineLimit(2).multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.caption.weight(.bold)).secondaryText()
                        }
                        .frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .swipeActions { Button("Bỏ", systemImage: "trash", role: .destructive) { discarding = d } }
                }
            } header: { Text("Bản nháp (\(env.templateDrafts.count))") }
            .confirmationDialog("Bỏ bản nháp này?", isPresented: Binding(get: { discarding != nil }, set: { if !$0 { discarding = nil } }), titleVisibility: .visible) {
                Button("Bỏ bản nháp", role: .destructive) { if let d = discarding { env.discardDraft(d.draftId) }; discarding = nil }
            } message: { Text("Mọi thay đổi chưa phát hành sẽ mất.") }
        }
    }
}

/// "Nhân bản từ mẫu…": pick the template to copy.
struct CloneTemplateSheet: View {
    var project: Project?
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(env.newestTemplates) { t in
                Button {
                    dismiss()
                    env.createTemplate(cloning: t, project: project)
                } label: {
                    HStack(spacing: 12) {
                        IconBadge(systemImage: "doc.on.doc", size: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.body.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                            Text("\(t.id) · v\(t.version)").font(.footnote).secondaryText()
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Nhân bản từ mẫu").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } } }
        }
    }
}
