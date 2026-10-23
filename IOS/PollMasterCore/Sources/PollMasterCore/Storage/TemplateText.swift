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

    /// Ready-to-send message asking the AI to fix the template.
    public static func fixRequest(_ lines: [String]) -> String {
        "Mẫu JSON bị app PollMaster từ chối với các lỗi sau, hãy sửa và trả lại toàn bộ JSON:\n" + lines.map { "- \($0)" }.joined(separator: "\n")
    }
}
