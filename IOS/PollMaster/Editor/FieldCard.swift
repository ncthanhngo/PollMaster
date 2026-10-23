import SwiftUI
import PollMasterCore

/// One field: code badge, label, input by type, parts, KĐĐ reason, note, validation message.
struct FieldCard: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    var showCode = true
    @State private var showFormula = false
    @State private var editingNote = false

    private var notMeasurable: Bool { fv.flag == .notMeasurable }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FieldHeader(field: field, fv: $fv, editingNote: $editingNote, locked: scope.locked, showCode: showCode)
            if let m = field.method { Text(m).font(.caption).foregroundStyle(.secondary) }
            if field.isComputed {
                ComputedBlock(field: field, value: scope.computedValue(field.id), showFormula: $showFormula)
            } else {
                Group {
                    FieldInput(field: field, fv: $fv, scope: scope)
                    PartsBlock(field: field, fv: $fv, scope: scope)
                    if field.attachPhoto {
                        MediaInput(field: field, video: false, fieldValue: $fv, scope: scope)
                    }
                }
                .opacity(notMeasurable ? 0.4 : 1)
                .disabled(notMeasurable || field.readonly || scope.locked)
                if notMeasurable {
                    TextField("Lý do không đo được (bắt buộc)", text: Binding(get: { fv.reason ?? "" }, set: { fv.reason = $0 }))
                        .textFieldStyle(.roundedBorder).disabled(scope.locked)
                }
            }
            if editingNote || !(fv.note ?? "").isEmpty {
                TextField("Ghi chú", text: Binding(get: { fv.note ?? "" }, set: { fv.note = $0 }), axis: .vertical)
                    .font(.footnote).textFieldStyle(.roundedBorder).disabled(scope.locked)
            }
            if let e = scope.errorText(field) {
                Label(e, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.red)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        .overlay { if scope.errorText(field) != nil { RoundedRectangle(cornerRadius: 12).stroke(Color.red.opacity(0.5), lineWidth: 1) } }
        .id(field.id)
        .onDisappear { scope.touch(field) }
    }
}

private struct FieldHeader: View {
    let field: Field
    @Binding var fv: FieldValue
    @Binding var editingNote: Bool
    let locked: Bool
    let showCode: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if showCode {
                Text(field.id).font(.caption.monospaced().weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 5))
            }
            if fv.flag == .estimate { flagBadge("~") }
            if fv.flag == .notMeasurable { flagBadge("KĐĐ") }
            (Text(field.label) + (field.required ? Text(" *").foregroundColor(.red) : Text("")))
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
            if field.isComputed {
                Image(systemName: "function").foregroundStyle(.secondary)
            } else if !locked && !field.readonly {
                Menu {
                    if field.flagsEnabled {
                        Button(fv.flag == .estimate ? "Bỏ ước lượng (~)" : "Ước lượng (~)", systemImage: "tilde") {
                            fv.flag = fv.flag == .estimate ? nil : .estimate
                        }
                        Button(fv.flag == .notMeasurable ? "Bỏ không đo được" : "Không đo được (KĐĐ)", systemImage: "nosign") {
                            fv.flag = fv.flag == .notMeasurable ? nil : .notMeasurable
                        }
                    }
                    Button("Thêm ghi chú", systemImage: "note.text") { editingNote = true }
                    Button("Xóa giá trị", systemImage: "trash", role: .destructive) {
                        fv = FieldValue(note: fv.note)
                    }
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }
            }
        }
    }

    private func flagBadge(_ t: String) -> some View {
        Text(t).font(.caption.weight(.bold)).foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.orange, in: RoundedRectangle(cornerRadius: 5))
    }
}

private struct ComputedBlock: View {
    let field: Field
    let value: JSONValue?
    @Binding var showFormula: Bool

    var body: some View {
        let text = ValueFormatter.displayComputed(field, value, includeUnit: true)
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "function").foregroundStyle(.secondary)
                Text(text.isEmpty ? "—" : text).font(.body.monospacedDigit())
                Spacer()
            }
            .padding(10).background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
            .onTapGesture { showFormula.toggle() }
            if showFormula, let f = field.formula {
                Text("ƒx  \(f)").font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
            }
        }
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
        case .gps: GPSInput(value: v, location: scope.location)
        case .photo: MediaInput(field: field, video: false, fieldValue: $fv, scope: scope)
        case .video: MediaInput(field: field, video: true, fieldValue: $fv, scope: scope)
        case .signature:
            Text("Ký tại màn Kiểm tra & hoàn thành.").font(.footnote).foregroundStyle(.secondary)
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
                Text("Lần \(i + 1)").font(.caption2).foregroundStyle(.secondary)
                ScalarInput(field: field, value: Binding(get: { fv.sample(i).isNull ? nil : fv.sample(i) },
                                                         set: { fv.setSample(i, count: n, to: $0) }),
                            other: other, focus: slot("\(field.id)#\(i)"), location: scope.location, showUnit: false)
            }
        }
        if horizontal {
            HStack(alignment: .top, spacing: 8) { cells }
            if let u = field.unit { Text(u).font(.caption).foregroundStyle(.secondary) }
        } else {
            VStack(alignment: .leading, spacing: 8) { cells }
        }
    }
}

private struct PartsBlock: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope

    var body: some View {
        let parts = PartVisibility.visibleParts(of: field, value: fv, lookup: scope.lookup)
        if !parts.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), alignment: .top)], alignment: .leading, spacing: 8) {
                ForEach(parts) { p in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.label + (p.unit.map { " (\($0))" } ?? "")).font(.caption2).foregroundStyle(.secondary)
                        ScalarInput(field: p.asField,
                                    value: Binding(get: { fv.parts?[p.id] }, set: { new in
                                        var d = fv.parts ?? [:]
                                        d[p.id] = new
                                        fv.parts = d
                                    }),
                                    other: .constant(nil),
                                    focus: scope.focus.map { FocusSlot(state: $0, key: "\(field.id).\(p.id)") },
                                    location: scope.location, showUnit: false)
                    }
                }
            }
        }
    }
}
