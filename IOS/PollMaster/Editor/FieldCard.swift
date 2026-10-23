import SwiftUI
import PollMasterCore

/// One field: code badge, label, flag toggles, input by type, parts, photo reference, KĐĐ reason, note, warnings, errors.
struct FieldCard: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    var showCode = true
    @State private var showFormula = false
    @State private var editingNote = false
    @State private var addingReview = false
    @State private var pickingSite = false
    @State private var pendingSite: Site?
    @Environment(\.pmTheme) private var theme

    private var notMeasurable: Bool { fv.flag == .notMeasurable }
    private var warnings: [String] { scope.warnings(field, fv) }
    private var error: String? { scope.errorText(field) }
    private var showsReview: Bool { scope.tableId == nil && (fv.review != nil || addingReview) }

    /// Short single-value fields share one row: label left, input right (~40 %).
    private var compactRow: Bool {
        scope.compact && [.number, .integer, .text].contains(field.type) && field.samples == nil && (field.parts ?? []).isEmpty
            && !field.attachPhoto && field.lookup == nil && field.prefix == nil && !notMeasurable
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showsReview {
                ReviewBox(text: Binding(get: { fv.review ?? "" }, set: { fv.review = $0.isEmpty ? nil : $0 }),
                          editable: scope.reviewMode, canResolve: !scope.locked) { addingReview = false }
            }
            if compactRow {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        // the code badge shares the line of the method hint, leaving the full width to the label
                        let codeOnHintLine = showCode && field.method != nil
                        FieldTitle(field: field, fv: fv, showCode: showCode && !codeOnHintLine)
                        if codeOnHintLine || field.method != nil {
                            HStack(spacing: 6) {
                                if codeOnHintLine { CodeBadge(id: field.id) }
                                if let m = field.method { Text(m).font(.caption).secondaryText().lineLimit(1) }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    input.containerRelativeFrame(.horizontal) { w, _ in min(w * 0.30, 200) }
                    FieldMenu(field: field, fv: $fv, editingNote: $editingNote, addingReview: $addingReview, scope: scope, withChips: false)
                }
            } else {
                HStack(alignment: .center, spacing: 6) {
                    FieldTitle(field: field, fv: fv, showCode: showCode).frame(maxWidth: .infinity, alignment: .leading)
                    FieldMenu(field: field, fv: $fv, editingNote: $editingNote, addingReview: $addingReview, scope: scope, withChips: true)
                }
                if let m = field.method { Text(m).font(.caption).secondaryText() }
                if field.isComputed {
                    ComputedBlock(field: field, value: scope.computedValue(field.id), showFormula: $showFormula)
                } else {
                    input
                    lookupButton
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
            ForEach(warnings, id: \.self) { w in
                Label("\(w) — kiểm tra lại", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(theme.warning)
            }
            if let e = error { Label(e, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(theme.alert) }
        }
        .cardBox(stroke: error != nil ? theme.alert.opacity(0.7) : (showsReview ? .orange : nil), padding: compactRow ? 8 : 12)
        .id(field.id)
        .simultaneousGesture(TapGesture().onEnded { scope.visit(field) })
        .onDisappear { scope.touch(field) }
        .sheet(isPresented: $pickingSite) { SitePicker(sites: scope.sites, onPick: choose) }
        .confirmationDialog(conflictTitle, isPresented: Binding(get: { pendingSite != nil }, set: { if !$0 { pendingSite = nil } }),
                            titleVisibility: .visible) {
            Button("Ghi đè") { fill(overwrite: true) }
            Button("Chỉ điền ô trống") { fill(overwrite: false) }
            Button("Hủy", role: .cancel) {}
        }
    }

    @ViewBuilder private var input: some View {
        if !field.isComputed {
            Group {
                FieldInput(field: field, fv: $fv, scope: scope)
                PartsBlock(field: field, fv: $fv, scope: scope)
                if field.attachPhoto { MediaInput(field: field, video: false, fieldValue: $fv, scope: scope) }
            }
            .opacity(notMeasurable ? 0.4 : 1)
            .disabled(notMeasurable || field.readonly || scope.locked)
        }
    }

    @ViewBuilder private var lookupButton: some View {
        if field.lookup != nil, !scope.locked, !field.isComputed {
            Button { pickingSite = true } label: {
                Label("Chọn từ danh sách", systemImage: "list.bullet.rectangle")
            }
            .buttonStyle(.bordered)
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

    private var conflictTitle: String {
        guard let site = pendingSite, let lk = field.lookup else { return "" }
        return "Ghi đè \(scope.lookupConflicts(lk, site).joined(separator: ", "))?"
    }

    private func choose(_ site: Site) {
        fv.v = .string(site.code)
        guard let lk = field.lookup else { return }
        if scope.lookupConflicts(lk, site).isEmpty { scope.applyLookup(lk, site, true) } else { pendingSite = site }
    }

    private func fill(overwrite: Bool) {
        if let site = pendingSite, let lk = field.lookup { scope.applyLookup(lk, site, overwrite) }
        pendingSite = nil
    }
}

/// Main input of a field, dispatching on type and samples.
struct FieldInput: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope

    private func slot(_ key: String) -> FocusSlot? { scope.focus.map { FocusSlot(state: $0, key: key) } }
    private var v: Binding<JSONValue?> { Binding(get: { fv.v }, set: { fv.v = $0 }) }
    private var other: Binding<[String: String]?> { Binding(get: { fv.other }, set: { fv.other = $0 }) }

    var body: some View {
        switch field.type {
        case .composite: EmptyView()
        case .sessionTime: SessionTimeInput(value: v)
        case .gps: GPSInput(field: field, value: v, location: scope.location, plannedDistance: scope.plannedDistance(field))
        case .photo: MediaInput(field: field, video: false, fieldValue: $fv, scope: scope)
        case .video: MediaInput(field: field, video: true, fieldValue: $fv, scope: scope)
        case .audio: AudioInput(field: field, fieldValue: $fv, scope: scope)
        case .signature:
            Text("Ký tại màn Kiểm tra & hoàn thành.").font(.footnote).secondaryText()
        default:
            if let n = field.samples { samples(n) }
            else {
                ScalarInput(field: field, value: v, other: other, focus: slot(field.id), location: scope.location)
            }
        }
    }

    @ViewBuilder private func samples(_ n: Int) -> some View {
        let horizontal = [.number, .integer, .time, .text, .elapsed, .compass].contains(field.type)
        let cells = ForEach(0..<n, id: \.self) { i in
            VStack(alignment: .leading, spacing: 2) {
                Text("Lần \(i + 1)").font(.caption2).secondaryText()
                ScalarInput(field: field, value: Binding(get: { fv.sample(i).isNull ? nil : fv.sample(i) },
                                                         set: { fv.setSample(i, count: n, to: $0) }),
                            other: other, focus: slot("\(field.id)#\(i)"), location: scope.location, showUnit: false)
            }
            .frame(maxWidth: .infinity)
        }
        if horizontal {
            HStack(alignment: .top, spacing: 8) { cells }
            if let u = field.unit { Text(u).font(.caption).secondaryText() }
        } else {
            VStack(alignment: .leading, spacing: 8) { cells }
        }
    }
}
