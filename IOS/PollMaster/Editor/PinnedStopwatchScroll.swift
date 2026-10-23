import SwiftUI
import PollMasterCore

/// Collapses once the scroll offset passes ~40 pt and expands near the top. The scroll view keeps its size either way,
/// so the change cannot feed back into the offset. Needs iOS 18 scroll geometry; on iOS 17 the block stays expanded.
private struct CollapseOnScroll: ViewModifier {
    @Binding var collapsed: Bool

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
                if y > 40 { collapsed = true } else if y < 10 { collapsed = false }
            }
        } else { content }
    }
}

/// A row's scroll area, with the stopwatch pinned above it when the table has one. The pinned block collapses to a thin strip
/// after scrolling, and a mark with a `cues` entry raises a reminder banner for 8 s (ux-spec L1, schema §8).
struct PinnedStopwatchScroll<Content: View>: View {
    let model: RecordEditorModel
    let stopwatch: (table: TableDef, row: Row)?
    @ViewBuilder var content: Content
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var collapsed = false
    @State private var headerHeight: CGFloat = 0
    @State private var cue: ActiveCue?

    private struct ActiveCue: Equatable {
        let id = UUID()
        let text: String
        let field: String
    }

    var body: some View {
        ZStack(alignment: .top) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        // room for the pinned block while the form is at the top
                        if stopwatch != nil { Color.clear.frame(height: headerHeight) }
                        content
                        Color.clear.frame(height: 1).id("pm-bottom")
                    }
                    .padding()
                    .frame(maxWidth: 760)
                    .frame(maxWidth: .infinity)
                }
                .modifier(CollapseOnScroll(collapsed: $collapsed))
                .scrollDismissesKeyboard(.interactively)
                #if DEBUG
                .task {
                    guard UserDefaults.standard.bool(forKey: "pmScrollDown") else { return }
                    try? await Task.sleep(for: .milliseconds(600))
                    withAnimation { proxy.scrollTo("pm-bottom", anchor: .bottom) }
                }
                #endif
            }
            if let sw = stopwatch, let def = sw.table.stopwatch {
                VStack(spacing: 6) {
                    StopwatchBlock(model: model, table: sw.table, row: sw.row, columns: def.columns, compact: collapsed) { col in
                        if let c = def.cue(for: col) { withAnimation { cue = ActiveCue(text: c.text, field: c.field) } }
                    }
                    if let cue { banner(cue) }
                }
                .padding(.horizontal).padding(.vertical, 6)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
                .background(collapsed ? AnyShapeStyle(.bar) : AnyShapeStyle(theme.screenBackground))
                .background(GeometryReader { g in
                    Color.clear.onChange(of: g.size.height, initial: true) { _, h in if !collapsed { headerHeight = h } }
                })
            }
        }
        .background(theme.screenBackground)
        .task(id: cue?.id) {
            guard cue != nil else { return }
            try? await Task.sleep(for: .seconds(8))
            if !Task.isCancelled { withAnimation { cue = nil } }
        }
    }

    private func banner(_ c: ActiveCue) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "bell.badge.fill").foregroundStyle(.orange)
            Text(c.text).font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
            Button("Mở \(c.field)") { open(c) }.buttonStyle(.borderedProminent).controlSize(.small)
            Button { withAnimation { cue = nil } } label: { Image(systemName: "xmark") }.buttonStyle(.plain).accessibilityLabel("Đóng")
        }
        .padding(10)
        .background(Color.orange.opacity(0.18), in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.orange, lineWidth: 1))
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    private func open(_ c: ActiveCue) {
        cue = nil
        guard let idx = model.sectionIndex(containing: c.field) else { return }
        env.recordsPath.append(.section(record: model.record.id, index: idx, focus: c.field))
    }
}
