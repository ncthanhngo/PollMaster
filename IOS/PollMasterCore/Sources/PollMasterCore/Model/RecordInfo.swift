import Foundation

/// Facts shown in the records list.
public enum RecordInfo {
    private static let imageExt: Set<String> = ["jpg", "jpeg", "png", "heic"]

    /// First image of the first `photo` field that has one (template order, header first).
    public static func firstPhoto(_ t: Template, _ r: Record) -> Media? {
        for f in t.allFields where f.type == .photo {
            if let m = r.values[f.id]?.mediaInV.first(where: { imageExt.contains(($0.file as NSString).pathExtension.lowercased()) }) { return m }
        }
        return nil
    }

    /// `{template.id} · {record code}` (no code -> just the id).
    public static func subtitle(_ t: Template, _ r: Record) -> String {
        guard let f = t.recordCodeField else { return t.id }
        let code = ValueFormatter.display(f, r.values[f.id])
        return code.isEmpty ? t.id : "\(t.id) · \(code)"
    }
}
