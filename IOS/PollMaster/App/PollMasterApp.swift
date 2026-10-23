import SwiftUI

@main
struct PollMasterApp: App {
    @State private var env = AppEnvironment()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ThemeHost(highContrast: env.settings.highContrast) { RootView() }
                .environment(env)
                .task { env.applyDebugLaunchArguments() }
                .alert("Lỗi", isPresented: Binding(get: { env.errorMessage != nil }, set: { if !$0 { env.errorMessage = nil } })) {
                    Button("OK") { env.errorMessage = nil }
                } message: { Text(env.errorMessage ?? "") }
                .onChange(of: phase) { _, p in if p != .active { env.reload() }; if p == .background { env.backupOpenEditors() } }
        }
    }
}
