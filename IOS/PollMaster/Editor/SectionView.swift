import SwiftUI
import PollMasterCore

struct SectionView: View {
    let model: RecordEditorModel
    let index: Int
    let focus: String?
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @FocusState private var focused: String?
    @State private var askKDD = false
    @State private var askCopy = false

    private var title: String { model.sectionTitles[index].title }
    private var sectionId: String { model.sectionTitles[index].id }
    private var items: [Item] { model.items(ofSection: index) }
    private var fields: [Field] { items.compactMap { if case .field(let f) = $0 { return f } else { return nil } } }
    private var hasTable: Bool { items.contains { if case .table = $0 { return true } else { return false } } }
    private func isComputed(_ i: Item) -> Bool { if case .field(let f) = i { return f.isComputed } else { return false } }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if model.isLocked { LockedBanner(model: model) }
                    if let note = model.sectionNote(index) { Text(note).font(.footnote).secondaryText().padding(.bottom, 4) }
                    ForEach(hasTable ? items.filter { !isComputed($0) } : items) { item in itemView(item) }
                    if hasTable {
                        let results = items.filter(isComputed)
                        if !results.isEmpty {
                            Text("Kết quả").font(.footnote.weight(.semibold)).secondaryText().textCase(.uppercase).padding(.top, 10)
                            ForEach(results) { itemView($0) }
                        }
                    }
                    navigation
                }
                .padding()
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(theme.screenBackground)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Đánh dấu KĐĐ các trường còn trống", systemImage: "nosign") { askKDD = true }
                        Button("Lấy dữ liệu mục này từ phiếu khác", systemImage: "doc.on.doc") { askCopy = true }
                    } label: { Image(systemName: "ellipsis.circle") }
                        .disabled(model.isLocked)
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Button("Tiếp") { advance() }
                    if let f = focused, FocusOrder.isSigned(key: f, fields: fields) {
                        Button("±") { NotificationCenter.default.post(name: .pmToggleSign, object: nil) }
                    }
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
        .modifier(WarnOnLeave(model: model, focused: $focused) { key in
            guard let f = fields.first(where: { $0.id == FocusOrder.baseId(key) }) else { return nil }
            return (f, model.record.values[f.id], f.id)
        })
        .modifier(SectionToolSheets(model: model, sectionId: sectionId, askKDD: $askKDD, askCopy: $askCopy))
        .onChange(of: focused) { _, new in
            if let new { model.setPosition(section: sectionId, field: FocusOrder.baseId(new)) }
        }
        .onDisappear { model.flush(); Task { await model.backup() } }
    }

    @ViewBuilder private func itemView(_ item: Item) -> some View {
        switch item {
        case .field(let f):
            FieldCard(field: f, fv: model.binding(f.id), scope: model.scope(focus: $focused, sectionId: sectionId))
        case .heading(let h):
            VStack(alignment: .leading, spacing: 2) {
                Text(h.title).font(.headline)
                if let n = h.note { Text(n).font(.footnote).secondaryText() }
            }
            .padding(.top, 8)
        case .table(let t):
            TableSectionView(model: model, table: t, sectionId: sectionId).id(t.id)
        }
    }

    private var navigation: some View {
        HStack {
            Button("‹ Mục trước") { go(index - 1) }.disabled(index == 0)
            Spacer()
            Text("\(index + 1)/\(model.sectionTitles.count)").font(.caption).secondaryText()
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
        let keys = FocusOrder.keys(for: fields, lookup: { model.record.values[$0] })
        guard let cur = focused, let i = keys.firstIndex(of: cur), i + 1 < keys.count else { focused = nil; return }
        focused = keys[i + 1]
    }
}
