import Foundation

/// User choice for the app's light/dark look. Default is `.light` (not the system setting).
public enum AppearanceMode: String, CaseIterable, Sendable {
    case light, dark, system

    /// Concrete mode after a one-tap toggle: flips what is currently displayed (so `.system` becomes light or dark).
    public static func toggled(displayingDark: Bool) -> AppearanceMode { displayingDark ? .light : .dark }
}
