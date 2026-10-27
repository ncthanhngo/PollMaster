import SwiftUI
import PollMasterCore

/// SF Symbol on a tinted rounded square: leading visual of list rows (templates, records, settings).
extension View {
    /// Hit area `slop` points wider than the control on the given sides, without taking room in the layout
    /// (small controls reach the 44 pt minimum of ux-spec).
    func hitSlop(_ slop: CGFloat, _ edges: Edge.Set = .all) -> some View {
        padding(edges, slop).contentShape(Rectangle()).padding(edges, -slop)
    }
}

struct IconBadge: View {
    let systemImage: String
    var tint: Color = .accentColor
    var size: CGFloat = 40
    @Environment(\.pmTheme) private var theme

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(theme.highContrast ? 0 : 0.14), in: RoundedRectangle(cornerRadius: size * 0.26))
            .overlay {
                if theme.highContrast { RoundedRectangle(cornerRadius: size * 0.26).stroke(theme.border, lineWidth: theme.borderWidth) }
            }
            .accessibilityHidden(true)
    }
}

/// Small capsule status label ("Nháp", "Hoàn thành", "v1.2"); `neutral` keeps the text in the secondary colour.
struct StatusPill: View {
    let text: String
    var color: Color = .gray
    var neutral = false
    @Environment(\.pmTheme) private var theme

    var body: some View {
        Text(text).font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.16), in: Capsule())
            .overlay(Capsule().stroke(color.opacity(theme.highContrast ? 1 : 0.3), lineWidth: 1))
            .foregroundStyle(neutral ? theme.secondaryText : color)
    }
}

/// Big number over a short caption, used in header summaries.
struct StatTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.bold)).monospacedDigit()
            Text(label).font(.caption).secondaryText()
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .cardBox(padding: 0)
    }
}

extension View {
    /// Pins a full-width prominent action above the home indicator, over a material bar.
    func bottomAction<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        safeAreaInset(edge: .bottom) {
            VStack(spacing: 6) { bar() }
                .padding(.horizontal).padding(.top, 10).padding(.bottom, 6)
                .frame(maxWidth: .infinity)
                .background(.bar)
        }
    }
}

/// "Đã gửi ERP dd-MM-yyyy HH:mm" or "Có thay đổi chưa gửi" (schema §9.6); nothing for a record never sent.
struct ERPMarker: View {
    let uploadedAt: Date?
    let unsent: Bool
    @Environment(\.pmTheme) private var theme

    var body: some View {
        if let text = ERPUpload.marker(uploadedAt: uploadedAt, hasUnsentChanges: unsent) {
            Text(text).font(.caption).foregroundStyle(unsent ? theme.warning : theme.secondaryText).lineLimit(1)
        }
    }
}
