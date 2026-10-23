import SwiftUI
import PollMasterCore

/// Everything a field card needs from its surroundings (record body or a table row).
struct FieldScope {
    /// Table the fields belong to (media names use `{tableId}-{columnId}`), nil for record fields.
    var tableId: String?
    var lookup: (String) -> FieldValue?
    var errorText: (Field) -> String?
    var touch: (Field) -> Void
    var computedValue: (String) -> JSONValue?
    var media: MediaService
    var location: LocationService
    var locked: Bool
    var focus: FocusState<String?>.Binding?
    var compact = false
    /// Checker mode: notes on cards can be written even though the record is locked.
    var reviewMode = false
    var sites: [Site] = []
    /// Project of the record being edited (where an imported site list goes).
    var projectId: String?
    var warnings: (Field, FieldValue?) -> [String] = { _, _ in [] }
    var visit: (Field) -> Void = { _ in }
    /// Referenced photo/video field (`photoRef`) with a binding to its value.
    var reference: (String) -> (field: Field, value: Binding<FieldValue>)? = { _ in nil }
    var photoUsers: (String) -> [String] = { _ in [] }
    var plannedDistance: (Field) -> Double? = { _ in nil }
    var lookupConflicts: (Lookup, Site) -> [String] = { _, _ in [] }
    var applyLookup: (Lookup, Site, Bool) -> Void = { _, _, _ in }
}

/// Keyboard "Tiếp" order: keys of text-like inputs in display order.
enum FocusOrder {
    static let textual: Set<FieldType> = [.text, .multiline, .number, .integer, .compass, .elapsed]

    static func keys(for fields: [Field], lookup: (String) -> FieldValue?) -> [String] {
        var out: [String] = []
        for f in fields where !f.isComputed {
            if textual.contains(f.type) {
                if let n = f.samples { out += (0..<n).map { "\(f.id)#\($0)" } } else { out.append(f.id) }
            }
            let fv = lookup(f.id) ?? FieldValue()
            for p in PartVisibility.visibleParts(of: f, value: fv, lookup: lookup) where textual.contains(p.type) {
                out.append("\(f.id).\(p.id)")
            }
        }
        return out
    }
}

extension FocusOrder {
    /// Field id behind a focus key ("B-02", "B-02#1", "B-11.diag").
    static func baseId(_ key: String) -> String {
        key.split(whereSeparator: { $0 == "#" || $0 == "." }).first.map(String.init) ?? key
    }

    /// Number inputs that may hold negatives get a "±" key on the keyboard toolbar.
    static func isSigned(key: String, fields: [Field]) -> Bool {
        guard let f = fields.first(where: { $0.id == baseId(key) }) else { return false }
        if key.contains(".") {
            return f.parts?.contains { key.hasSuffix(".\($0.id)") && [.number, .integer].contains($0.type) && ($0.warnMin ?? 0) < 0 } ?? false
        }
        return [.number, .integer].contains(f.type) && ((f.min ?? 0) < 0 || (f.warnMin ?? 0) < 0)
    }
}

/// Asks once when the user leaves a number field whose value is outside `warnMin...warnMax`.
struct WarnOnLeave: ViewModifier {
    let model: RecordEditorModel
    let focused: FocusState<String?>.Binding
    /// Resolves a focus key to the field, its value and a key identifying it for "accepted" bookkeeping.
    let resolve: (String) -> (field: Field, value: FieldValue?, id: String)?
    @State private var pending: (key: String, id: String, message: String)?

    func body(content: Content) -> some View {
        content
            .onChange(of: focused.wrappedValue) { old, _ in
                guard let old, let r = resolve(old), !model.isAccepted(r.id),
                      let msg = Warnings.messages(for: r.field, r.value).first else { return }
                pending = (old, r.id, msg)
            }
            .alert("Giá trị bất thường", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })) {
                Button("Sửa lại") { if let p = pending { focused.wrappedValue = p.key }; pending = nil }
                Button("Giữ nguyên") { if let p = pending { model.accept(p.id) }; pending = nil }
            } message: { Text("\(pending?.message ?? ""). Kiểm tra lại giá trị đã nhập?") }
    }
}
