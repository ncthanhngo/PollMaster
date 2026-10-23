import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        @Bindable var env = env
        TabView(selection: $env.selectedTab) {
            RecordsListView()
                .tabItem { Label("Phiếu khảo sát", systemImage: "list.clipboard") }
                .tag(AppTab.records)
            TemplatesView()
                .tabItem { Label("Mẫu", systemImage: "doc.text") }
                .tag(AppTab.templates)
            SettingsView()
                .tabItem { Label("Cài đặt", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
    }
}
