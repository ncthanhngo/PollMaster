import Foundation
import PollMasterCore

/// Vietnamese names for the editor's pickers and for JSON keys quoted in messages.
enum EditorLabels {
    /// Types a person picks from; `computed` and `signature` are left to JSON (they need a formula / are not for columns).
    static let fieldTypes: [(type: String, name: String)] = [
        ("text", "Văn bản ngắn"), ("multiline", "Văn bản dài"), ("number", "Số"), ("integer", "Số nguyên"),
        ("singleChoice", "Chọn một"), ("multiChoice", "Chọn nhiều"), ("yesNo", "Có / Không"), ("rating", "Đánh giá sao"),
        ("date", "Ngày"), ("time", "Giờ"), ("datetime", "Ngày giờ"), ("month", "Tháng"), ("sessionTime", "Thời gian phiên"),
        ("gps", "Toạ độ GPS"), ("compass", "La bàn"), ("photo", "Ảnh"), ("video", "Video"), ("audio", "Ghi âm"),
        ("elapsed", "Bấm giờ"), ("composite", "Nhiều phần"), ("computed", "Tự tính"), ("signature", "Chữ ký"),
    ]

    static func typeName(_ t: String) -> String { fieldTypes.first { $0.type == t }?.name ?? t }

    static let keyNames: [String: String] = [
        "id": "mã", "label": "nhãn", "title": "tên", "type": "kiểu", "options": "lựa chọn", "formula": "công thức", "min": "nhỏ nhất", "max": "lớn nhất",
        "warnMin": "cảnh báo thấp", "warnMax": "cảnh báo cao", "columns": "cột", "items": "mục con", "rows": "dòng", "photoRef": "ảnh tham chiếu",
        "showWhen": "điều kiện hiện", "lookup": "tra cứu", "stopwatch": "bấm giờ", "parts": "các phần", "version": "phiên bản", "role": "vai trò",
        "chipsRange": "nút chọn nhanh", "roundEntry": "nhập theo lần",
    ]

    /// `options[2].value` -> "lựa chọn 3 · value".
    static func keyName(_ steps: ArraySlice<PathKey>) -> String {
        var out: [String] = []
        for s in steps {
            switch s {
            case .key(let k): out.append(keyNames[k] ?? k)
            case .index(let i): if !out.isEmpty { out[out.count - 1] += " \(i + 1)" }
            }
        }
        return out.joined(separator: " · ")
    }

    /// "A-01 · Chọn một" for a row subtitle.
    static func nodeKind(_ o: [String: JSONValue]) -> String {
        switch o["kind"]?.string {
        case "heading": return "Tiêu đề nhóm"
        case "table": return "Bảng · \((o["columns"]?.array ?? []).count) cột"
        default: return typeName(o["type"]?.string ?? "text")
        }
    }

    static func nodeIcon(_ o: [String: JSONValue]) -> String {
        switch o["kind"]?.string {
        case "heading": return "text.alignleft"
        case "table": return "tablecells"
        default:
            switch o["type"]?.string ?? "text" {
            case "number", "integer", "computed": return "number"
            case "singleChoice", "multiChoice": return "checklist"
            case "photo", "video": return "camera"
            case "gps": return "location"
            case "date", "time", "datetime", "month", "sessionTime": return "calendar"
            case "yesNo": return "checkmark.circle"
            default: return "textformat"
            }
        }
    }
}
