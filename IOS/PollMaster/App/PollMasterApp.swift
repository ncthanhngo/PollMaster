import SwiftUI
import PollMasterCore

@main
struct PollMasterApp: App {
    @State private var env = AppEnvironment()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ThemeHost(highContrast: env.settings.highContrast, oneHand: env.settings.oneHand) { RootView() }
                // an explicit tint turns every .bordered button into a light brand fill instead of grey
                .tint(Color.accentColor)
                .appearance(env.settings.appearance)
                .environment(env)
                .task { env.applyDebugLaunchArguments() }
                .onOpenURL { url in if url.pathExtension.lowercased() == ProjectBundle.fileExtension { env.openIncoming(url) } }
                .alert("Lỗi", isPresented: Binding(get: { env.errorMessage != nil }, set: { if !$0 { env.errorMessage = nil } })) {
                    Button("OK") { env.errorMessage = nil }
                } message: { Text(env.errorMessage ?? "") }
                .alert("Thông báo", isPresented: Binding(get: { env.infoMessage != nil }, set: { if !$0 { env.infoMessage = nil } })) {
                    Button("OK") { env.infoMessage = nil }
                } message: { Text(env.infoMessage ?? "") }
                .onChange(of: phase) { _, p in if p != .active { env.reload() }; if p == .background { env.backupOpenEditors() } }
        }
    }
}
