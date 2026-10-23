import SwiftUI
import UIKit

/// Colours that depend on light/dark and the "Tương phản cao" setting.
struct PMTheme: Equatable {
    var highContrast = false
    var dark = false

    var screenBackground: Color { highContrast ? (dark ? .black : .white) : Color(.systemGroupedBackground) }
    var cardBackground: Color { highContrast ? (dark ? .black : .white) : Color(.secondarySystemGroupedBackground) }
    /// Inputs stay distinguishable from the card in both modes (no pure black on grey in dark).
    var inputBackground: Color { highContrast ? cardBackground : Color(.tertiarySystemGroupedBackground) }
    var border: Color { highContrast ? (dark ? .white : .black) : Color(.separator) }
    var borderWidth: CGFloat { highContrast ? 2 : 1 }
    /// WCAG AA (>= 4.5:1) on the card background in light and dark.
    var secondaryText: Color {
        if highContrast { return dark ? .white : .black }
        return dark ? Color(red: 0.71, green: 0.71, blue: 0.75) : Color(red: 0.33, green: 0.33, blue: 0.37)
    }
    var unselectedChip: Color { highContrast ? cardBackground : Color(.tertiarySystemFill) }
    var warning: Color { dark ? Color(red: 1, green: 0.8, blue: 0.3) : Color(red: 0.62, green: 0.38, blue: 0) }
    var success: Color { dark ? Color(red: 0.4, green: 0.85, blue: 0.5) : Color(red: 0.1, green: 0.5, blue: 0.2) }
    var alert: Color { dark ? Color(red: 1, green: 0.45, blue: 0.4) : Color(red: 0.75, green: 0.1, blue: 0.1) }
}

private struct ThemeKey: EnvironmentKey { static let defaultValue = PMTheme() }

extension EnvironmentValues {
    var pmTheme: PMTheme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

/// Injects the theme (and, in high contrast, one larger Dynamic Type step ~ +10 %) below it.
struct ThemeHost<Content: View>: View {
    let highContrast: Bool
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var size

    private var bumped: DynamicTypeSize {
        let all = DynamicTypeSize.allCases
        guard highContrast, let i = all.firstIndex(of: size) else { return size }
        return all[min(i + 1, all.count - 1)]
    }

    var body: some View {
        content
            .environment(\.pmTheme, PMTheme(highContrast: highContrast, dark: scheme == .dark))
            .dynamicTypeSize(bumped)
    }
}

private struct SecondaryText: ViewModifier {
    @Environment(\.pmTheme) private var theme
    func body(content: Content) -> some View { content.foregroundStyle(theme.secondaryText) }
}

private struct InputBox: ViewModifier {
    @Environment(\.pmTheme) private var theme
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(theme.inputBackground, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border, lineWidth: theme.borderWidth))
    }
}

private struct CardBox: ViewModifier {
    @Environment(\.pmTheme) private var theme
    var stroke: Color?
    var padding: CGFloat = 12
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12).padding(.vertical, padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(stroke ?? theme.border, lineWidth: theme.borderWidth))
    }
}

extension View {
    func secondaryText() -> some View { modifier(SecondaryText()) }
    /// Replaces `.roundedBorder`: visible fill + border on cards in light, dark and high contrast.
    func inputBox() -> some View { modifier(InputBox()) }
    func cardBox(stroke: Color? = nil, padding: CGFloat = 12) -> some View { modifier(CardBox(stroke: stroke, padding: padding)) }
}
