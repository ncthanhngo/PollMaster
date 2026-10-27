import SwiftUI
import PollMasterCore

/// "Đã hoàn thành" notice with reopen / checker-mode buttons.
struct LockedBanner: View {
    let model: RecordEditorModel
    @State private var confirm = false
    @Environment(\.pmTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Đã hoàn thành lúc \(model.record.completedAt.map { ISODate.format($0, "dd/MM/yyyy HH:mm") } ?? "")",
                  systemImage: "checkmark.circle.fill").foregroundStyle(theme.success)
            HStack {
                Button("Mở lại") { confirm = true }.buttonStyle(.bordered)
                if !model.reviewMode { Button("Chế độ kiểm tra") { model.reviewMode = true }.buttonStyle(.bordered) }
            }
        }
        .confirmationDialog("Mở lại phiếu để chỉnh sửa? Trạng thái sẽ về Nháp.", isPresented: $confirm, titleVisibility: .visible) {
            Button("Mở lại") { model.reopen() }
        }
    }
}
