import SwiftUI
import PollMasterCore

struct SettingsView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var used: Int64 = 0
    @State private var backingUp = false
    @State private var backupMessage: String?
    @State private var confirmLogout = false

    var body: some View {
        @Bindable var settings = env.settings
        NavigationStack {
            Form {
                if let a = env.account {
                    Section {
                        HStack(spacing: 12) {
                            badge("building.2.fill", .teal)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(a.name).foregroundStyle(Color.primary)
                                Text([a.email, a.org].compactMap { $0 }.joined(separator: " · ")).font(.caption).secondaryText()
                            }
                        }
                        Button(role: .destructive) { confirmLogout = true } label: { row("Đăng xuất", "rectangle.portrait.and.arrow.right", .red) }
                    } header: { Text("Tài khoản ERP") } footer: {
                        Text("Dự án lấy từ ERP. Đăng xuất không xóa phiếu trên máy.")
                    }
                }
                Section {
                    HStack(spacing: 12) { badge("person.fill", .blue); TextField("Tên người khảo sát mặc định", text: $settings.surveyorName) }
                } header: { Text("Người khảo sát") } footer: { Text("Tự điền vào trường HD-surveyor khi tạo phiếu mới.") }
                Section {
                    Picker(selection: $settings.appearance) {
                        ForEach(AppearanceMode.allCases, id: \.self) { Text($0.title).tag($0) }
                    } label: { row("Giao diện", "circle.lefthalf.filled", .indigo) }
                    Toggle(isOn: $settings.compactMode) { row("Chế độ gọn", "rectangle.compress.vertical", .teal) }
                    Toggle(isOn: $settings.highContrast) { row("Tương phản cao", "sun.max.fill", .orange) }
                    Toggle(isOn: $settings.oneHand) { row("Chế độ một tay / đeo găng", "hand.raised.fill", .pink) }
                } header: { Text("Hiển thị") } footer: {
                    Text("Gọn: trường ngắn có nhãn và ô nhập cùng một hàng. Tương phản cao: nền trắng/đen tinh, viền đậm, chữ lớn hơn, dùng ngoài trời. Một tay / đeo găng: vùng chạm lớn, chữ lớn hơn, thanh hành động ở cuối màn hình; nhấn giữ thẻ trường để mở menu.")
                }
                Section("Ảnh") {
                    Picker(selection: $settings.photoQuality) {
                        ForEach(PhotoQuality.allCases) { Text($0.title).tag($0) }
                    } label: { row("Chất lượng ảnh", "photo.fill", .purple) }
                }
                Section {
                    Toggle(isOn: $settings.autoBackup) { row("Tự động sao lưu", "arrow.triangle.2.circlepath", .green) }
                    LabeledContent { Text("Tệp > PollMaster > PollMaster Backup > mã dự án") } label: { row("Thư mục", "folder.fill", .blue) }
                    Button { runBackup() } label: {
                        HStack { row("Sao lưu tất cả ngay", "externaldrive.fill.badge.checkmark", .accentColor); if backingUp { Spacer(); ProgressView() } }
                    }
                    .disabled(backingUp)
                } header: { Text("Sao lưu") } footer: {
                    Text(backupMessage ?? "Mỗi phiếu có một tệp .zip (JSON + media) trong thư mục của dự án, xem được trong app Tệp.")
                }
                Section("Thông tin") {
                    LabeledContent { Text(ByteCountFormatter.string(fromByteCount: used, countStyle: .file)) } label: { row("Dung lượng đã dùng", "internaldrive.fill", .gray) }
                    LabeledContent { Text(version) } label: { row("Phiên bản", "info.circle.fill", .gray) }
                }
            }
            .navigationTitle("Cài đặt")
            .toolbar { ToolbarItem(placement: .primaryAction) { AppearanceToggleButton() } }
            .task { used = env.usedStorage() }
            .confirmationDialog("Đăng xuất khỏi ERP?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Đăng xuất", role: .destructive) { Task { await env.logout() } }
            } message: { Text("Phiếu vẫn nằm trên máy và hiện lại khi đăng nhập tài khoản có dự án đó.") }
        }
    }

    private func badge(_ icon: String, _ tint: Color) -> some View {
        Image(systemName: icon).font(.footnote.weight(.semibold)).foregroundStyle(.white)
            .frame(width: 28, height: 28).background(tint, in: RoundedRectangle(cornerRadius: PMRadius.s)).accessibilityHidden(true)
    }

    /// Settings-app style row label: coloured icon square + title.
    private func row(_ title: String, _ icon: String, _ tint: Color) -> some View {
        HStack(spacing: 12) { badge(icon, tint); Text(title).foregroundStyle(Color.primary) }
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
