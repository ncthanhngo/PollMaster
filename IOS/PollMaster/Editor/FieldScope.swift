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
