import SwiftUI
import PollMasterCore

/// One field: code badge, label, flag toggles, input by type, parts, photo reference, KĐĐ reason, note, warnings, errors.
struct FieldCard: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    var showCode = true
    /// roundEntry: show only this 0-based round of a `samples` field.
    var round: Int?
    /// "Điền lần lượt" shows the full card, never the one-row layout.
    var allowCompact = true
    /// Leaving the screen marks the field as visited (error marks). A lazy list passes false: scrolling past is not leaving.
    var touchOnDisappear = true
    @State private var showFormula = false
    @State private var editingNote = false
    @State private var addingReview = false
    @Environment(\.pmTheme) private var theme

    private var notMeasurable: Bool { fv.flag == .notMeasurable }
    private var warnings: [String] { scope.warnings(field, fv) }
    private var error: String? { scope.errorText(field) }
    private var showsReview: Bool { scope.tableId == nil && (fv.review != nil || addingReview) }

    /// Short single-value fields share one row: label left, input right (~30 %). Only number / integer and `short` text (ux-spec L3).
    private var compactRow: Bool {
        let shortKind = field.type == .number || (field.type == .integer && !field.stepper && field.chipsRange == nil) || (field.type == .text && field.short)
        return allowCompact && scope.compact && shortKind && field.samples == nil && (field.parts ?? []).isEmpty
            && !field.attachPhoto && field.lookup == nil && field.prefix == nil && !notMeasurable && field.photoRef == nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showsReview {
                ReviewBox(text: Binding(get: { fv.review ?? "" }, set: { fv.review = $0.isEmpty ? nil : $0 }),
                          editable: scope.reviewMode, canResolve: !scope.locked) { addingReview = false }
            }
            if compactRow {
                // label and input share the first line; the meta line runs full width below so the label keeps its room
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .center, spacing: 8) {
                        FieldTitle(field: field, fv: fv).frame(maxWidth: .infinity, alignment: .leading)
                        input.frame(width: 132)
                        FieldMenu(field: field, fv: $fv, editingNote: $editingNote, addingReview: $addingReview, scope: scope)
                    }
                    metaLine
                    hintLine
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    VStack(alignment: .leading, spacing: 3) {
                        FieldTitle(field: field, fv: fv)
                        metaLine
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    FieldMenu(field: field, fv: $fv, editingNote: $editingNote, addingReview: $addingReview, scope: scope)
                }
                hintLine
                if field.isComputed {
                    ComputedBlock(field: field, value: computedValue, showFormula: $showFormula)
                } else {
                    input
                    LookupButton(field: field, fv: $fv, scope: scope)
                    assistButtons
                }
            }
            if notMeasurable {
                TextField("Lý do không đo được (bắt buộc)", text: Binding(get: { fv.reason ?? "" }, set: { fv.reason = $0 }))
                    .textFieldStyle(.plain).inputBox().disabled(scope.locked)
            }
            if editingNote || !(fv.note ?? "").isEmpty {
                TextField("Ghi chú", text: Binding(get: { fv.note ?? "" }, set: { fv.note = $0 }), axis: .vertical)
                    .font(.footnote).textFieldStyle(.plain).inputBox().disabled(scope.locked)
            }
            photoRef
            ForEach(Array(warnings.enumerated()), id: \.offset) { _, w in
                Label("\(w) — kiểm tra lại", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(theme.warning)
            }
            if let e = error { Label(e, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(theme.alert) }
        }
        .fieldContainer(stroke: error != nil ? theme.alert : (showsReview ? theme.attention : nil), compact: compactRow)
        .contextMenu {
            if theme.oneHand && !scope.locked && !scope.reviewMode && !field.isComputed && !field.readonly {
                FieldMenuItems(field: field, fv: $fv, editingNote: $editingNote)
            }
        }
        .id(field.id)
        .simultaneousGesture(TapGesture().onEnded { scope.visit(field) })
        .onDisappear { if touchOnDisappear { scope.touch(field) } }
    }

    @ViewBuilder private var input: some View {
        if !field.isComputed {
            Group {
                FieldInput(field: field, fv: $fv, scope: scope, round: round)
                PartsBlock(field: field, fv: $fv, scope: scope)
                if field.attachPhoto { MediaInput(field: field, video: false, fieldValue: $fv, scope: scope) }
            }
            .opacity(notMeasurable ? 0.4 : 1)
            .disabled(notMeasurable || field.readonly || scope.locked)
        }
    }

    /// One secondary line under the label: code · method · usual range (was up to three grey lines).
    @ViewBuilder private var metaLine: some View {
        let text = [field.method, usualRange].compactMap { $0 }.joined(separator: " · ")
        if showCode || !text.isEmpty {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                if showCode { CodeBadge(id: field.id) }
                if !text.isEmpty { Text(text).font(.footnote).secondaryText().lineLimit(2) }
            }
        }
    }

    /// Hint under the label, once (never as a placeholder: boxes can be narrow).
    @ViewBuilder private var hintLine: some View {
        if let h = field.hint, !h.isEmpty { Text(h).font(.footnote).secondaryText().fixedSize(horizontal: false, vertical: true) }
    }

    /// "Thường 1000–2500 mm" from warnMin/warnMax (typical values, falling back to min/max): the expected magnitude at a glance.
    private var usualRange: String? {
        guard field.type == .number || field.type == .integer, !field.isComputed,
              let lo = field.warnMin ?? field.min, let hi = field.warnMax ?? field.max else { return nil }
        func short(_ d: Double) -> String { d == d.rounded() ? String(Int(d)) : String(d) }
        return "Thường \(short(lo))–\(short(hi))\(field.unit.map { " \($0)" } ?? "")"
    }

    /// Computed field with samples inside a round group shows the value of the selected round only.
    private var computedValue: JSONValue? {
        let v = scope.computedValue(field.id)
        guard let round, case .array(let a)? = v else { return v }
        return round < a.count ? a[round] : .null
    }

    /// OCR (text / multiline with photoRef) and dictation (multiline).
    @ViewBuilder private var assistButtons: some View {
        if !notMeasurable, !field.readonly, !scope.locked, field.type == .text || field.type == .multiline {
            let ocr = field.photoRef != nil
            let mic = field.type == .multiline
            if ocr || mic {
                HStack(spacing: 8) {
                    if ocr { OCRButton(field: field, fv: $fv, scope: scope) }
                    if mic { DictationButton(fv: $fv) }
                }
            }
        }
    }

    @ViewBuilder private var photoRef: some View {
        if let ref = field.photoRef, let r = scope.reference(ref) {
            MediaInput(field: r.field, video: r.field.type == .video, fieldValue: r.value, scope: refScope, captureTitle: "Chụp \(ref)").controlSize(.small)
                .disabled(scope.locked)
        }
        let users = field.isMedia ? scope.photoUsers(field.id) : []
        if !users.isEmpty { Text("Dùng cho: \(users.joined(separator: ", "))").font(.caption).secondaryText() }
    }

    /// Reference media is named after the referenced record field, not after a table column.
    private var refScope: FieldScope { var s = scope; s.tableId = nil; return s }
}
