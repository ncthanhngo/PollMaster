import SwiftUI
import PollMasterCore

struct SettingsView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var used: Int64 = 0
    @State private var backingUp = false
    @State private var backupMessage: String?

    var body: some View {
        @Bindable var settings = env.settings
        NavigationStack {
            Form {
                Section {
                    TextField("Tên người khảo sát mặc định", text: $settings.surveyorName)
                } header: { Text("Người khảo sát") } footer: { Text("Tự điền vào trường HD-surveyor khi tạo phiếu mới.") }
                Section {
                    Toggle("Chế độ gọn", isOn: $settings.compactMode)
                    Toggle("Tương phản cao", isOn: $settings.highContrast)
                } header: { Text("Hiển thị") } footer: {
                    Text("Gọn: trường ngắn có nhãn và ô nhập cùng một hàng. Tương phản cao: nền trắng/đen tinh, viền đậm, chữ lớn hơn, dùng ngoài trời.")
                }
                Section("Ảnh") {
                    Picker("Chất lượng ảnh", selection: $settings.photoQuality) {
                        ForEach(PhotoQuality.allCases) { Text($0.title).tag($0) }
                    }
                }
                Section {
                    Toggle("Tự động sao lưu", isOn: $settings.autoBackup)
                    LabeledContent("Thư mục", value: "Tệp > PollMaster > PollMaster Backup")
                    Button { runBackup() } label: {
                        HStack { Text("Sao lưu tất cả ngay"); if backingUp { Spacer(); ProgressView() } }
                    }
                    .disabled(backingUp)
                } header: { Text("Sao lưu") } footer: {
                    Text(backupMessage ?? "Mỗi phiếu có một tệp .zip (JSON + media) trong thư mục này, xem được trong app Tệp.")
                }
                Section("Danh sách điểm") {
                    NavigationLink { SitesView() } label: {
                        LabeledContent("Danh sách điểm", value: env.sites.isEmpty ? "Chưa có" : "\(env.sites.count) điểm")
                    }
                }
                Section("Thông tin") {
                    LabeledContent("Dung lượng đã dùng", value: ByteCountFormatter.string(fromByteCount: used, countStyle: .file))
                    LabeledContent("Phiên bản", value: version)
                }
            }
            .navigationTitle("Cài đặt")
            .task { used = env.usedStorage() }
        }
    }

    private func runBackup() {
        backingUp = true; backupMessage = nil
        Task {
            let r = await env.backupAll()
            backupMessage = r.failed == 0 ? "Đã sao lưu \(r.done) phiếu." : "Đã sao lưu \(r.done) phiếu, lỗi \(r.failed) phiếu."
            used = env.usedStorage()
            backingUp = false
        }
    }

    private var version: String {
        let i = Bundle.main.infoDictionary
        return "\(i?["CFBundleShortVersionString"] as? String ?? "1.0") (\(i?["CFBundleVersion"] as? String ?? "1"))"
    }
}
