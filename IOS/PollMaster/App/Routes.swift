import Foundation

enum AppTab: Hashable { case records, projects, templates, settings }

/// Navigation destinations inside the "Phiếu khảo sát" stack.
enum Route: Hashable {
    case editor(String)
    case section(record: String, index: Int, focus: String?)
    case row(record: String, table: String, row: String)
    case review(record: String)
    /// "Điền lần lượt": one missing field at a time.
    case fill(record: String)

    var recordId: String {
        switch self {
        case .editor(let id), .review(let id), .fill(let id): return id
        case .section(let id, _, _): return id
        case .row(let id, _, _): return id
        }
    }
}
