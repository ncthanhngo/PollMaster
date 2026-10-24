import SwiftUI
import PollMasterCore

/// Multiline JSON entry, prefilled from the clipboard when it holds a template.
struct PasteTemplateSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    let onImport: (String) -> Void

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.system(.footnote, design: .monospaced))
                .autocorrectionDisabled().textInputAutocapitalization(.never)
                .padding(.horizontal, 8)
                .navigationTitle("Dán JSON").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Nhập") { onImport(text) }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    ToolbarItem(placement: .bottomBar) { Button("Dán từ bộ nhớ tạm", systemImage: "doc.on.clipboard") { paste() } }
                }
                .onAppear { if text.isEmpty { paste(onlyJSON: true) } }
        }
    }

    private func paste(onlyJSON: Bool = false) {
        guard let clip = UIPasteboard.general.string else { return }
        if let json = TemplateText.extractJSON(clip) { text = json } else if !onlyJSON { text = clip }
    }
}

/// Import errors with a one-tap copy of a message the user can send back to the AI.
struct ImportErrorsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    let lines: [String]

    var body: some View {
        NavigationStack {
            List {
                Section("Mẫu bị từ chối") { ForEach(lines, id: \.self) { Text($0).font(.footnote) } }
                Section {
                    Button(copied ? "Đã sao chép" : "Sao chép lỗi", systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = TemplateText.fixRequest(lines)
                        copied = true
                    }
                } footer: { Text("Dán nội dung đã sao chép cho AI để được sửa lại JSON.") }
            }
            .navigationTitle("Lỗi nhập mẫu").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Đóng") { dismiss() } } }
        }
    }
}
