import SwiftUI
import PollMasterCore

/// Resolves a navigation route to the matching editor screen.
struct RouteDestination: View {
    let route: Route
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Group {
            destination
        }
        .toolbar(.hidden, for: .tabBar)      // the whole editing flow runs full screen
    }

    @ViewBuilder private var destination: some View {
        if let model = env.editor(for: route.recordId) {
            switch route {
            case .editor: EditorRootView(model: model)
            case .section(_, let index, let focus): SectionView(model: model, index: index, focus: focus)
            case .row(_, let table, let row): RowFormView(model: model, tableId: table, rowId: row)
            case .review: ReviewView(model: model)
            case .fill: FillOneByOneView(model: model)
            }
        } else {
            ContentUnavailableView("Không mở được phiếu", systemImage: "exclamationmark.triangle",
                                   description: Text("Phiếu hoặc mẫu của phiếu không còn trên máy."))
        }
    }
}
