import Foundation

/// Helpers for templates pasted as text (e.g. an AI reply) and for reporting import errors back to the AI.
public enum TemplateText {
    /// JSON object from raw text: the first ```json fence if any, else the whole text; then cut to the outermost {...}.
    public static func extractJSON(_ text: String) -> String? {
        var body = text
        if let open = text.range(of: "```json", options: .caseInsensitive) {
            let rest = text[open.upperBound...]
            body = String(rest[..<(rest.range(of: "```")?.lowerBound ?? rest.endIndex)])
        }
        guard let start = body.firstIndex(of: "{"), let end = body.lastIndex(of: "}"), start < end else { return nil }
        return String(body[start...end])
    }

    /// One entry per problem (parse errors carry the JSON path).
    public static func errorLines(_ error: Error) -> [String] {
        switch error {
        case TemplateError.invalid(let list): return list
        case TemplateError.parse(let m): return [m]
        default: return [error.localizedDescription]
        }
    }

    /// The same template JSON with only `title`, `shortName` and `color` replaced (edited in the library: id and version stay,
    /// so records and projects keep pointing at it). An empty short name or colour removes the key (the defaults apply).
    public static func edited(_ data: Data, title: String, shortName: String?, color: String?) throws -> Data {
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw TemplateError.invalid(["Tên mẫu không được để trống."]) }
        let short = shortName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let hex = color?.nilIfEmpty
        if let hex, !Template.isHexColor(hex) { throw TemplateError.invalid(["Màu phải có dạng #RRGGBB."]) }
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw TemplateError.parse("Mẫu không phải đối tượng JSON.") }
        object["title"] = name
        object["shortName"] = short
        object["color"] = hex
        return try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
    }

    /// Ready-to-send message asking the AI to fix the template.
    public static func fixRequest(_ lines: [String]) -> String {
        "Mẫu JSON bị app PollMaster từ chối với các lỗi sau, hãy sửa và trả lại toàn bộ JSON:\n" + lines.map { "- \($0)" }.joined(separator: "\n")
    }
}
