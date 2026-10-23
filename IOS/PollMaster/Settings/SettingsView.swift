import SwiftUI

struct SettingsView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var used: Int64 = 0

    var body: some View {
        @Bindable var settings = env.settings
        NavigationStack {
            Form {
                Section {
                    TextField("Tên người khảo sát mặc định", text: $settings.surveyorName)
                } header: { Text("Người khảo sát") } footer: { Text("Tự điền vào trường HD-surveyor khi tạo phiếu mới.") }
                Section("Ảnh") {
                    Picker("Chất lượng ảnh", selection: $settings.photoQuality) {
                        ForEach(PhotoQuality.allCases) { Text($0.title).tag($0) }
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

    private var version: String {
        let i = Bundle.main.infoDictionary
        return "\(i?["CFBundleShortVersionString"] as? String ?? "1.0") (\(i?["CFBundleVersion"] as? String ?? "1"))"
    }
}
