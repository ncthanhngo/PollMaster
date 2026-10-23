import SwiftUI
import PollMasterCore

struct SectionView: View {
    let model: RecordEditorModel
    let index: Int
    let focus: String?
    @Environment(AppEnvironment.self) private var env
    @FocusState private var focused: String?

    private var title: String { model.sectionTitles[index].title }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if model.isLocked { LockedBanner(model: model) }
                    if let note = model.sectionNote(index) { Text(note).font(.footnote).foregroundStyle(.secondary) }
                    ForEach(model.items(ofSection: index)) { item in
                        switch item {
                        case .field(let f):
                            FieldCard(field: f, fv: model.binding(f.id), scope: model.scope(focus: $focused))
                        case .heading(let h):
                            VStack(alignment: .leading, spacing: 2) {
                                Text(h.title).font(.headline)
                                if let n = h.note { Text(n).font(.footnote).foregroundStyle(.secondary) }
                            }
                            .padding(.top, 8)
                        case .table(let t):
                            TableSectionView(model: model, table: t).id(t.id)
                        }
                    }
                    navigation
                }
                .padding()
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Button("Tiếp") { advance() }
                    Spacer()
                    Button("Xong") { focused = nil }
                }
            }
            .task {
                guard let focus else { return }
                try? await Task.sleep(for: .milliseconds(150))
                withAnimation { proxy.scrollTo(focus, anchor: .top) }
            }
        }
        .onDisappear { model.flush() }
    }

    private var navigation: some View {
        HStack {
            Button("‹ Mục trước") { go(index - 1) }.disabled(index == 0)
            Spacer()
            Text("\(index + 1)/\(model.sectionTitles.count)").font(.caption).foregroundStyle(.secondary)
            Spacer()
            Button("Mục sau ›") { go(index + 1) }.disabled(index >= model.sectionTitles.count - 1)
        }
        .buttonStyle(.bordered)
        .padding(.top, 8)
    }

    private func go(_ i: Int) {
        focused = nil
        env.recordsPath.removeLast()
        env.recordsPath.append(.section(record: model.record.id, index: i, focus: nil))
    }

    private func advance() {
        let fields = model.items(ofSection: index).compactMap { if case .field(let f) = $0 { return f } else { return nil } }
        let keys = FocusOrder.keys(for: fields, lookup: { model.record.values[$0] })
        guard let cur = focused, let i = keys.firstIndex(of: cur), i + 1 < keys.count else { focused = nil; return }
        focused = keys[i + 1]
    }
}
