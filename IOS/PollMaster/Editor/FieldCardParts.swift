import SwiftUI
import PollMasterCore

/// Small code badge, label with red asterisk right after it, active flag badges.
struct FieldTitle: View {
    let field: Field
    let fv: FieldValue
    @Environment(\.pmTheme) private var theme

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            (Text(field.label).fontWeight(.semibold) + (field.required ? Text(" *").foregroundColor(theme.alert) : Text("")))
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
            if field.isComputed { Image(systemName: "function").secondaryText() }
            if fv.flag == .estimate { badge("~ Ước lượng") }
            if fv.flag == .notMeasurable { badge("KĐĐ") }
        }
    }

    private func badge(_ t: String) -> some View {
        StatusPill(text: t, color: theme.warning)
    }
}

/// Small, pale field code ("B-02").
struct CodeBadge: View {
    let id: String
    @Environment(\.pmTheme) private var theme
    var body: some View {
        Text(id).font(.system(size: 11, design: .monospaced)).secondaryText()
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(theme.inputBackground.opacity(0.7), in: RoundedRectangle(cornerRadius: PMRadius.s))
    }
}

/// Actions of the field menu: ~ / KĐĐ, note, clear. Used by the ⋯ menu and, in one-hand mode, by the long-press menu.
struct FieldMenuItems: View {
    let field: Field
    @Binding var fv: FieldValue
    @Binding var editingNote: Bool

    var body: some View {
        if field.flagsEnabled {
            Button(fv.flag == .estimate ? "Bỏ ước lượng (~)" : "Ước lượng (~)", systemImage: "tilde") { flip(.estimate) }
            Button(fv.flag == .notMeasurable ? "Bỏ không đo được" : "Không đo được (KĐĐ)", systemImage: "nosign") { flip(.notMeasurable) }
        }
        Button("Thêm ghi chú", systemImage: "note.text") { editingNote = true }
        Button("Xóa giá trị", systemImage: "trash", role: .destructive) { fv = FieldValue(note: fv.note, review: fv.review) }
    }

    private func flip(_ f: FieldFlag) { fv.flag = fv.flag == f ? nil : f }
}

/// The ⋯ menu (ước lượng, KĐĐ, note, clear); in review mode a button for the checker note.
/// One-hand mode drops it: the same actions live in the long-press menu of the card.
struct FieldMenu: View {
    let field: Field
    @Binding var fv: FieldValue
    @Binding var editingNote: Bool
    @Binding var addingReview: Bool
    let scope: FieldScope
    @Environment(\.pmTheme) private var theme

    var body: some View {
        if field.isComputed || field.readonly { EmptyView() }
        else if scope.reviewMode {
            if scope.tableId == nil, fv.review == nil {
                Button { addingReview = true } label: { Image(systemName: "text.bubble").font(.title3).frame(minWidth: 44, minHeight: 44) }
                    .accessibilityLabel("Thêm ghi chú kiểm tra")
            }
        } else if !scope.locked && !theme.oneHand {
            Menu { FieldMenuItems(field: field, fv: $fv, editingNote: $editingNote) }
            label: {
                Image(systemName: "ellipsis").font(.body.weight(.semibold)).foregroundStyle(theme.secondaryText)
                    .frame(minWidth: 36, minHeight: 36).contentShape(Rectangle())
            }
            .accessibilityLabel("Tùy chọn trường: ước lượng, không đo được, ghi chú")
        }
    }
}

/// Orange box "Kiểm tra: …" on top of the card.
struct ReviewBox: View {
    @Binding var text: String
    let editable: Bool
    let canResolve: Bool
    var onClear: () -> Void
    @Environment(\.pmTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if editable {
                TextField("Ghi chú kiểm tra", text: $text, axis: .vertical).textFieldStyle(.plain).inputBox()
                Button("Xóa ghi chú", role: .destructive) { text = ""; onClear() }.font(.caption)
            } else {
                Text("Kiểm tra: \(text)").font(.footnote.weight(.medium)).foregroundStyle(Color.primary)
                if canResolve {
                    Button("Đã xử lý", systemImage: "checkmark") { text = ""; onClear() }.font(.caption.weight(.semibold)).buttonStyle(.bordered)
                }
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.attention.opacity(0.14), in: RoundedRectangle(cornerRadius: PMRadius.s))
        .overlay(RoundedRectangle(cornerRadius: PMRadius.s).stroke(theme.attention, lineWidth: 1))
    }
}

struct ComputedBlock: View {
    let field: Field
    let value: JSONValue?
    @Binding var showFormula: Bool
    @Environment(\.pmTheme) private var theme

    var body: some View {
        let text = ValueFormatter.displayComputed(field, value, includeUnit: true)
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "function").secondaryText()
                Text(text.isEmpty ? "—" : text).font(.body.monospacedDigit())
                Spacer()
            }
            .padding(10).background(theme.inputBackground, in: RoundedRectangle(cornerRadius: PMRadius.s))
            .contentShape(Rectangle())
            .onTapGesture { showFormula.toggle() }
            if showFormula, let f = field.formula {
                Text("ƒx  \(f)").font(.caption.monospaced()).secondaryText().textSelection(.enabled)
            }
        }
    }
}

/// Sub-inputs: 2-column grid; 3 columns on wide screens with >= 3 parts, and on phones when >= 3 parts are all numbers
/// (L x W x H in one row, ux-spec L12). In the 3-column row the unit moves into the label to keep the boxes wide enough.
struct PartsBlock: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let parts = PartVisibility.visibleParts(of: field, value: fv, lookup: scope.lookup)
        if !parts.isEmpty {
            let numeric = parts.count >= 3 && parts.allSatisfy { $0.type == .number || $0.type == .integer }
            let cols = parts.count >= 3 && (sizeClass == .regular || numeric) ? 3 : 2
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: cols), alignment: .leading, spacing: 8) {
                ForEach(parts) { p in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(numeric && p.unit != nil ? "\(p.label) (\(p.unit!))" : p.label).font(.caption2).secondaryText().lineLimit(1)
                        ScalarInput(field: p.asField,
                                    value: Binding(get: { fv.parts?[p.id] }, set: { new in
                                        var d = fv.parts ?? [:]
                                        d[p.id] = new
                                        fv.parts = d
                                    }),
                                    other: .constant(nil),
                                    focus: scope.focus.map { FocusSlot(state: $0, key: "\(field.id).\(p.id)") },
                                    location: scope.location, showUnit: !numeric)
                    }
                }
            }
        }
    }
}
