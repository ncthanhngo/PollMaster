import SwiftUI
import PollMasterCore

/// A record the device and the server both changed (or the server lost): the choice is the user's (ux-spec N). Opens by itself once.
struct ConflictBanner: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var info: ConflictInfo?
    @State private var asking = false
    @State private var working = false
    @State private var failure: String?
    @State private var opened = false

    private var id: String { model.record.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Xung đột đồng bộ", systemImage: "exclamationmark.icloud.fill").font(.subheadline.weight(.semibold)).foregroundStyle(theme.attention)
            Text(message).font(.footnote).secondaryText()
            if let failure { Text(failure).font(.footnote).foregroundStyle(theme.alert) }
            Button(working ? "Đang xử lý…" : "Chọn cách xử lý") { asking = true }.buttonStyle(.bordered).disabled(working)
        }
        .task(id: model.record.serverRev) {
            info = await env.conflictInfo(of: id)
            if !opened { opened = true; asking = true }
        }
        .alert("Xung đột đồng bộ", isPresented: $asking) { buttons } message: { Text(message) }
    }

    private var message: String {
        switch info {
        case .deleted: "Phiếu đã bị xoá trên máy chủ."
        case .edited(let by, let at):
            "Phiếu đã được \(by ?? "người khác") sửa" + (at.map { " lúc \(ISODate.format($0, "dd-MM HH:mm"))" } ?? "") + " trên máy khác."
        case nil: "Phiếu đã được sửa trên máy khác."
        }
    }

    @ViewBuilder private var buttons: some View {
        if info == .deleted {
            if env.canPost(to: model.record.projectId ?? "") { Button("Khôi phục") { env.restoreOnServer(id) } }
            Button("Xoá trên máy", role: .destructive) { env.delete(id); env.recordsPath = [] }
        } else {
            if !model.readOnly { Button("Giữ bản của tôi") { run { await env.keepMine(id) } } }
            Button("Lấy bản máy chủ", role: .destructive) { run { await env.takeServer(id) } }
        }
        Button("Để sau", role: .cancel) {}
    }

    private func run(_ work: @escaping () async -> String?) {
        working = true; failure = nil
        Task {
            failure = await work()
            working = false
        }
    }
}
