import SwiftUI
import PollMasterCore

/// Errors (block publishing) and warnings of the draft; a tap goes to the place.
struct IssuesSheet: View {
    let model: TemplateEditorModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pmTheme) private var theme

    var body: some View {
        NavigationStack {
            List {
                if model.issues.isEmpty {
                    ContentUnavailableView("Không có lỗi hay cảnh báo", systemImage: "checkmark.seal", description: Text("Mẫu sẵn sàng để phát hành."))
                }
                group("Lỗi (\(model.errors.count)) — phải sửa mới phát hành được", model.errors, "exclamationmark.circle.fill", theme.alert)
                group("Cảnh báo (\(model.warnings.count)) — không chặn phát hành", model.warnings, "exclamationmark.triangle.fill", theme.warning)
            }
            .navigationTitle("Lỗi và cảnh báo").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
    }

    @ViewBuilder private func group(_ title: String, _ list: [TemplateIssue], _ icon: String, _ color: Color) -> some View {
        if !list.isEmpty {
            Section(title) {
                ForEach(list, id: \.self) { i in
                    Button {
                        model.sheet = nil
                        model.jump(toIssue: i.path)
                    } label: {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: icon).foregroundStyle(color).padding(.top, 2)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(i.message).font(.subheadline).multilineTextAlignment(.leading)
                                Text(model.place(of: i.path)).font(.caption).secondaryText().multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.caption.weight(.bold)).secondaryText()
                        }
                        .frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
