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

/// Project identity: its photo, else white initials on the project colour (rounded square, like an app icon).
struct ProjectAvatar: View {
    let project: Project?
    var size: CGFloat = 40
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
        Group {
            if let p = project, let img = env.projectAvatars[p.id] {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                Text(project.map { Project.initials(of: $0.name) } ?? "∗")
                    .font(.system(size: size * 0.4, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    .minimumScaleFactor(0.5)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(project?.tint ?? .gray)
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
    }
}

/// "2 nháp · 1 cần sửa · 5 hoàn thành" counters of a project, split the same way as the project detail statistics.
struct ProjectCounts: View {
    let stats: ProjectStats
    var body: some View {
        // one short line everywhere (cards, switcher), same wording as the statistics
        Text("\(stats.draft) nháp" + (stats.returned > 0 ? " · \(stats.returned) cần sửa" : "") + " · \(stats.completed) hoàn thành")
            .lineLimit(1).minimumScaleFactor(0.85)
            .font(.caption).secondaryText()
    }
}

extension ProjectStats {
    /// "Hoạt động gần nhất: dd/MM HH:mm" (or "Chưa có phiếu").
    var latestText: String {
        latest.map { "Gần nhất \(ISODate.format($0, "dd/MM HH:mm"))" } ?? "Chưa có phiếu"
    }
}
