import Foundation

enum AppTab: Hashable { case records, templates, settings }

/// Navigation destinations inside the "Phiếu khảo sát" stack.
enum Route: Hashable {
    case editor(String)
    case section(record: String, index: Int, focus: String?)
    case row(record: String, table: String, row: String)
    case review(record: String)

    var recordId: String {
        switch self {
        case .editor(let id), .review(let id): return id
        case .section(let id, _, _): return id
        case .row(let id, _, _): return id
        }
    }
}
