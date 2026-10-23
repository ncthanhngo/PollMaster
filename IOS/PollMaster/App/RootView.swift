import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var surveyorName = ""

    var body: some View {
        @Bindable var env = env
        TabView(selection: $env.selectedTab) {
            RecordsListView()
                .tabItem { Label("Phiếu khảo sát", systemImage: "list.clipboard") }
                .tag(AppTab.records)
            ProjectsView()
                .tabItem { Label("Dự án", systemImage: "folder") }
                .tag(AppTab.projects)
            TemplatesView()
                .tabItem { Label("Mẫu", systemImage: "doc.text") }
                .tag(AppTab.templates)
            SettingsView()
                .tabItem { Label("Cài đặt", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .sheet(item: $env.incomingBundle) { ProjectImportSheet(incoming: $0) }
        .sheet(item: $env.addToProjectsFor) { AddToProjectsSheet(template: $0) }
        .alert("Tên người khảo sát", isPresented: Binding(get: { env.askSurveyorFor != nil }, set: { _ in })) {
            TextField("Họ tên", text: $surveyorName)
            Button("Bỏ qua") { env.answerSurveyor("") }
            Button("Lưu") { env.answerSurveyor(surveyorName) }
        } message: { Text("Tự điền vào HD-surveyor của các phiếu sau. Đổi lại được trong Cài đặt.") }
    }
}
