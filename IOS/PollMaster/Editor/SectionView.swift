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
    /// Selected round (0-based) per heading id with `roundEntry`.
    @State private var rounds: [String: Int] = [:]

    private var title: String { model.sectionTitles[index].title }
    private var sectionId: String { model.sectionTitles[index].id }
    private var items: [Item] { model.items(ofSection: index) }
    private var fields: [Field] { items.compactMap { if case .field(let f) = $0 { return f } else { return nil } } }
    private var hasTable: Bool { items.contains { if case .table = $0 { return true } else { return false } } }
    private func isComputed(_ i: Item) -> Bool { if case .field(let f) = i { return f.isComputed } else { return false } }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // lazy: only the blocks near the screen are built (a big section re-rendered everything on every keystroke)
                LazyVStack(alignment: .leading, spacing: PMSpace.m) {
                    if model.isLocked { LockedBanner(model: model) }
                    if let note = model.sectionNote(index) { Text(note).font(.footnote).secondaryText().padding(.bottom, 4) }
                    ForEach(segments(hasTable ? items.filter { !isComputed($0) } : items)) { segmentView($0) }
                    if hasTable {
                        let results = items.filter(isComputed)
                        if !results.isEmpty {
                            Text("Kết quả").font(.footnote.weight(.semibold)).secondaryText().textCase(.uppercase).padding(.top, 10)
                            FieldGroupCard(data: results) { itemView($0) }
                        }
                    }
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
                    Button("Tiếp") { advance(proxy) }
                    if let f = focused, FocusOrder.isSigned(key: f, fields: fields) {
                        Button("±") { NotificationCenter.default.post(name: .pmToggleSign, object: nil) }
                    }
                    Spacer()
                    Button("Xong") { focused = nil }
                }
            }
            .task {
                guard let focus else { return }
                selectFirstEmptyRound(for: focus)
                // lazy content: have the block holding the field built first, then settle on the field itself
                if let block = blockId(containing: focus) { proxy.scrollTo(block, anchor: .top) }
                try? await Task.sleep(for: .milliseconds(150))
                withAnimation { proxy.scrollTo(focus, anchor: .top) }
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .modifier(WarnOnLeave(model: model, focused: $focused) { key in
            guard let f = fields.first(where: { $0.id == FocusOrder.baseId(key) }) else { return nil }
            return (f, model.record.values[f.id], f.id)
        })
        .modifier(SectionToolSheets(model: model, sectionId: sectionId, askKDD: $askKDD, askCopy: $askCopy))
        .onChange(of: focused) { _, new in
            if let new { model.setPosition(section: sectionId, field: FocusOrder.baseId(new)) }
        }
        .onDisappear {
            fields.forEach { model.touch($0.id) }       // marks stay hidden until the section is left
            model.flush(); Task { await model.backup() }
        }
    }

    /// A heading with the fields after it (one grouped block), or a table on its own.
    private struct Segment: Identifiable {
        let id: String
        var heading: Item?
        var fields: [Item] = []
        var table: Item?
    }

    private func segments(_ list: [Item]) -> [Segment] {
        var out: [Segment] = []
        for item in list {
            switch item {
            case .heading: out.append(Segment(id: item.id, heading: item))
            case .table: out.append(Segment(id: item.id, table: item))
            case .field:
                if let last = out.last, last.table == nil { out[out.count - 1].fields.append(item) }
                else { out.append(Segment(id: "g-" + item.id, fields: [item])) }
            }
        }
        return out
    }

    @ViewBuilder private func segmentView(_ seg: Segment) -> some View {
        VStack(alignment: .leading, spacing: PMSpace.s) {
            if let h = seg.heading { itemView(h) }
            if !seg.fields.isEmpty { FieldGroupCard(data: seg.fields) { itemView($0) } }
            if let t = seg.table { itemView(t) }
        }
        .padding(.top, seg.heading == nil ? 0 : PMSpace.s)
    }

    @ViewBuilder private func itemView(_ item: Item) -> some View {
        switch item {
        case .field(let f):
            if let h = RoundEntryRules.heading(owning: f.id, in: items), let entry = h.roundEntry, f.samples != nil {
                let round = min(rounds[h.id] ?? 0, entry.samples - 1)
                FieldCard(field: f, fv: roundBinding(f, entry, round), scope: model.scope(focus: $focused, sectionId: sectionId), round: round,
                          touchOnDisappear: false)
            } else {
                FieldCard(field: f, fv: model.binding(f.id), scope: model.scope(focus: $focused, sectionId: sectionId), touchOnDisappear: false)
            }
        case .heading(let h):
            VStack(alignment: .leading, spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(h.title).font(.title3.weight(.bold))
                    if let n = h.note { Text(n).font(.footnote).secondaryText() }
                }
                .padding(.horizontal, PMSpace.xs)
                if let entry = h.roundEntry {
                    Picker("Lần đo", selection: Binding(get: { rounds[h.id] ?? 0 }, set: { rounds[h.id] = $0 })) {
                        ForEach(0..<entry.samples, id: \.self) { Text("Lần \($0 + 1)").tag($0) }
                    }
                    .pickerStyle(.segmented).controlSize(theme.oneHand ? .large : .regular)
                }
            }
        case .table(let t):
            TableSectionView(model: model, table: t, sectionId: sectionId).id(t.id)
        }
    }

    /// Edits go to the model as usual; the first value of a round also stamps the current time into the entry's `timeField`.
    private func roundBinding(_ f: Field, _ entry: RoundEntry, _ round: Int) -> Binding<FieldValue> {
        Binding(get: { model.record.value(f.id) }, set: { new in
            model.setValue(f.id, new)
            guard let time = RoundEntryRules.timeToFill(entry, round: round, editedFieldId: f.id, editedHasValue: !new.sample(round).isBlank,
                                                        time: model.record.values[entry.timeField ?? ""]),
                  let timeField = entry.timeField else { return }
            var tv = model.record.value(timeField)
            tv.setSample(round, count: entry.samples, to: .string(time))
            model.setValue(timeField, tv)
        })
    }

    /// Opened from a stopwatch cue: land on the first round of the target field that is still empty.
    private func selectFirstEmptyRound(for fieldId: String) {
        guard let h = RoundEntryRules.heading(owning: fieldId, in: items), let entry = h.roundEntry else { return }
        rounds[h.id] = RoundEntryRules.firstEmptyRound(samples: entry.samples, value: model.record.values[fieldId])
    }

    /// Pinned bottom bar: previous / check (k/N) / next, so the next section never needs a scroll to the end.
    /// One-hand mode also moves the section's add-row button here and uses larger controls.
    private var bottomBar: some View {
        VStack(spacing: 8) {
            if theme.oneHand, !model.isLocked, let t = items.compactMap({ item -> TableDef? in if case .table(let t) = item, !t.isFixed { return t } else { return nil } }).first {
                AddRowButton(model: model, table: t, sectionId: sectionId)
            }
            HStack(spacing: 8) {
                Button { go(index - 1) } label: { Text("‹ Mục trước").lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity, minHeight: 44) }.disabled(index == 0)
                Button {
                    model.showAllErrors = true
                    env.recordsPath.append(.review(record: model.record.id))
                } label: {
                    VStack(spacing: 0) {
                        Text("Kiểm tra").lineLimit(1)
                        Text("\(index + 1)/\(model.sectionTitles.count)").font(.caption2).secondaryText()
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
                Button { go(index + 1) } label: { Text("Mục sau ›").lineLimit(1).minimumScaleFactor(0.7).frame(maxWidth: .infinity, minHeight: 44) }.disabled(index >= model.sectionTitles.count - 1)
            }
            .buttonStyle(.bordered).controlSize(theme.oneHand ? .large : .regular)
        }
        .padding(.horizontal).padding(.vertical, 8)
        .background(.bar)
    }

    private func go(_ i: Int) {
        focused = nil
        env.recordsPath.removeLast()
        env.recordsPath.append(.section(record: model.record.id, index: i, focus: nil))
    }

    /// Id of the lazily built block (heading group, field group or table) that holds the field or table `id`.
    private func blockId(containing id: String) -> String? {
        segments(hasTable ? items.filter { !isComputed($0) } : items).first {
            $0.heading?.id == id || $0.table?.id == id || $0.fields.contains { $0.id == id }
        }?.id
    }

    private func advance(_ proxy: ScrollViewProxy) {
        let keys = FocusOrder.keys(for: fields, lookup: { model.record.values[$0] })
        guard let cur = focused, let i = keys.firstIndex(of: cur), i + 1 < keys.count else { focused = nil; return }
        let next = keys[i + 1]
        let block = blockId(containing: FocusOrder.baseId(next))
        guard block != blockId(containing: FocusOrder.baseId(cur)), let block else { focused = next; return }
        // the next field sits in a block that may not be built yet: scroll to it, then focus
        proxy.scrollTo(block, anchor: .top)
        Task {
            try? await Task.sleep(for: .milliseconds(120))
            focused = next
        }
    }
}
