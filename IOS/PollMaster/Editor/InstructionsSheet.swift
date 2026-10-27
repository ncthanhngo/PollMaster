import SwiftUI
import PollMasterCore

struct InstructionsSheet: View {
    let template: Template
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(template.instructions ?? "Mẫu này không có hướng dẫn.")
                    .frame(maxWidth: .infinity, alignment: .leading).padding()
            }
            .navigationTitle("Hướng dẫn")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
    }
}
