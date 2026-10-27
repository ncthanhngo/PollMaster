import SwiftUI
import PollMasterCore

/// "Xem trước": the real record screens on a scratch record in the temp folder. Nothing is synced, backed up or counted; closing deletes it.
struct TemplatePreviewView: View {
    let model: TemplateEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pmTheme) private var theme
    @State private var scratch: URL?
    @State private var failure: String?

    var body: some View {
        VStack(spacing: 0) {
            banner
            if let m = env.previewModel {
                NavigationStack(path: Bindable(env).previewPath) {
                    EditorRootView(model: m).navigationDestination(for: Route.self) { RouteDestination(route: $0) }
                }
            } else if let failure {
                ContentUnavailableView("Không xem trước được", systemImage: "exclamationmark.triangle", description: Text(failure))
            } else { ProgressView().frame(maxHeight: .infinity) }
        }
        .onAppear(perform: start)
        .onDisappear(perform: cleanUp)
    }

    private var banner: some View {
        HStack(spacing: 10) {
            Image(systemName: "eye.fill")
            Text("Xem trước — không lưu").font(.subheadline.weight(.semibold)).lineLimit(2)
            Spacer(minLength: 8)
            Button("Đóng") { dismiss() }.font(.subheadline.weight(.semibold)).buttonStyle(.bordered).tint(.white).frame(minHeight: 44)
        }
        .foregroundStyle(.white).padding(.horizontal).padding(.vertical, 4)
        .frame(maxWidth: .infinity).background(theme.highContrast ? Color.black : Color(red: 0.55, green: 0.33, blue: 0.0))
    }

    private func start() {
        guard env.previewModel == nil else { return }
        do {
            let t = try TemplateLoader.decode(model.doc.data())
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pm-preview-\(UUID().uuidString)", isDirectory: true)
            let pid = model.draft.projectId
            let r = RecordFactory.make(template: t, surveyorName: env.settings.surveyorName, projectId: pid, creator: env.creator)
            let m = RecordEditorModel(template: t, record: r, store: RecordStore(root: dir), location: env.location, settings: env.settings, backups: env.backups,
                                      sites: { [unowned env] in env.sites(forProject: pid) }, project: { [unowned env] in env.project(id: $0) })
            m.isPreview = true
            scratch = dir
            env.previewPath = []
            env.previewModel = m
        } catch { failure = TemplateText.errorLines(error).joined(separator: "\n") }
    }

    private func cleanUp() {
        env.previewModel?.discard()
        env.previewModel = nil
        env.previewPath = []
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
        scratch = nil
    }
}
