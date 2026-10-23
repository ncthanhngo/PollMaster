import SwiftUI
import UIKit
import PollMasterCore

/// Colours that depend on light/dark and the "Tương phản cao" setting.
struct PMTheme: Equatable {
    var highContrast = false
    var dark = false
    /// One-hand / glove mode (ux-spec L17): touch targets of at least 56 pt.
    var oneHand = false
    var minTouch: CGFloat { oneHand ? 56 : 0 }

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
    /// "Cần sửa" and checker notes: orange that still reads as text (>= 4.5:1) on the card.
    var attention: Color { dark ? Color(red: 1, green: 0.66, blue: 0.35) : Color(red: 0.7, green: 0.33, blue: 0) }
}

/// The only corner radii of the app: small (inputs, chips, badges), medium (cards, buttons), large (sheets, hero blocks).
enum PMRadius {
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
}

/// Spacing scale (4 pt grid).
enum PMSpace {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 16
    static let l: CGFloat = 24
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
    var oneHand = false
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var size

    private var bumped: DynamicTypeSize {
        let all = DynamicTypeSize.allCases
        guard highContrast || oneHand, let i = all.firstIndex(of: size) else { return size }
        return all[min(i + 1, all.count - 1)]
    }

    var body: some View {
        content
            .environment(\.pmTheme, PMTheme(highContrast: highContrast, dark: scheme == .dark, oneHand: oneHand))
            .dynamicTypeSize(bumped)
    }
}

extension AppearanceMode {
    var title: String { self == .light ? "Sáng" : (self == .dark ? "Tối" : "Theo hệ thống") }
}

/// Applies the appearance to SwiftUI (`preferredColorScheme`) and to every window, so sheets, alerts and share sheets follow too.
private struct AppearanceModifier: ViewModifier {
    let mode: AppearanceMode
    private var style: UIUserInterfaceStyle { mode == .light ? .light : (mode == .dark ? .dark : .unspecified) }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(mode == .light ? .light : (mode == .dark ? .dark : nil))
            .onAppear(perform: apply)
            .onChange(of: mode) { apply() }
    }

    private func apply() {
        for scene in UIApplication.shared.connectedScenes {
            (scene as? UIWindowScene)?.windows.forEach { $0.overrideUserInterfaceStyle = style }
        }
    }
}

/// One-tap sun/moon button for the tab roots: flips the displayed scheme and saves the concrete choice.
struct AppearanceToggleButton: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(scheme == .dark ? "Giao diện sáng" : "Giao diện tối", systemImage: scheme == .dark ? "sun.max" : "moon") {
            env.settings.appearance = AppearanceMode.toggled(displayingDark: scheme == .dark)
        }
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
            .frame(minHeight: theme.minTouch)
            .background(theme.inputBackground, in: RoundedRectangle(cornerRadius: PMRadius.s))
            .overlay(RoundedRectangle(cornerRadius: PMRadius.s).stroke(theme.highContrast ? theme.border : .clear, lineWidth: theme.borderWidth))
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
            .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: PMRadius.m))
            // flat: the white card on the grey screen is the separation; an outline only for state or high contrast
            .overlay {
                if stroke != nil || theme.highContrast {
                    RoundedRectangle(cornerRadius: PMRadius.m).stroke(stroke ?? theme.border, lineWidth: theme.borderWidth)
                }
            }
    }
}

extension View {
    func appearance(_ mode: AppearanceMode) -> some View { modifier(AppearanceModifier(mode: mode)) }
    func secondaryText() -> some View { modifier(SecondaryText()) }
    /// Replaces `.roundedBorder`: visible fill + border on cards in light, dark and high contrast.
    func inputBox() -> some View { modifier(InputBox()) }
    func cardBox(stroke: Color? = nil, padding: CGFloat = 12) -> some View { modifier(CardBox(stroke: stroke, padding: padding)) }
}
