import SwiftUI
import PollMasterCore

extension Color {
    /// `#RRGGBB`; nil for anything else.
    init?(hex: String?) {
        guard let hex, hex.hasPrefix("#"), hex.count == 7, let v = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

extension Project {
    var tint: Color { Color(hex: color) ?? .gray }
}

struct ProjectDot: View {
    let project: Project?
    var size: CGFloat = 12
    var body: some View {
        Circle().fill(project?.tint ?? .gray).frame(width: size, height: size).accessibilityHidden(true)
    }
}

/// "2 Nháp · 1 Cần sửa · 5 Hoàn thành" counters of a project, split the same way as the project detail statistics.
struct ProjectCounts: View {
    let stats: ProjectStats
    var body: some View {
        HStack(spacing: 10) {
            Label("\(stats.draft) Nháp", systemImage: "pencil")
            if stats.returned > 0 { Label("\(stats.returned) Cần sửa", systemImage: "arrow.uturn.backward") }
            Label("\(stats.completed) Hoàn thành", systemImage: "checkmark.circle")
        }
        .font(.caption).secondaryText().labelStyle(.titleAndIcon)
    }
}

extension ProjectStats {
    /// "Hoạt động gần nhất: dd/MM HH:mm" (or "Chưa có phiếu").
    var latestText: String {
        latest.map { "Gần nhất \(ISODate.format($0, "dd/MM HH:mm"))" } ?? "Chưa có phiếu"
    }
}
