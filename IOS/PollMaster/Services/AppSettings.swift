import Foundation
import Observation
import PollMasterCore

enum PhotoQuality: String, CaseIterable, Identifiable {
    case high, medium
    var id: String { rawValue }
    var title: String { self == .high ? "Cao" : "Trung bình" }
    var maxSide: CGFloat { self == .high ? 2560 : 1600 }
    var jpegQuality: CGFloat { self == .high ? 0.85 : 0.65 }
}

/// Local preferences backed by UserDefaults.
@MainActor @Observable
final class AppSettings {
    private let defaults = UserDefaults.standard

    var surveyorName: String { didSet { defaults.set(surveyorName, forKey: "surveyorName") } }
    var photoQuality: PhotoQuality { didSet { defaults.set(photoQuality.rawValue, forKey: "photoQuality") } }
    var exportFormats: Set<ExportFormat> {
        didSet { defaults.set(exportFormats.map(\.rawValue).sorted().joined(separator: ","), forKey: "exportFormats") }
    }

    /// Label and input on one row for short fields (ux-spec D).
    var compactMode: Bool { didSet { defaults.set(compactMode, forKey: "compactMode") } }
    var highContrast: Bool { didSet { defaults.set(highContrast, forKey: "highContrast") } }
    var appearance: AppearanceMode { didSet { defaults.set(appearance.rawValue, forKey: "appearance") } }
    /// One-hand / glove mode (ux-spec L17).
    var oneHand: Bool { didSet { defaults.set(oneHand, forKey: "oneHand") } }
    /// The surveyor name was asked once at the first record creation (ux-spec L9).
    var surveyorAsked: Bool { didSet { defaults.set(surveyorAsked, forKey: "surveyorAsked") } }
    var autoBackup: Bool { didSet { defaults.set(autoBackup, forKey: "autoBackup") } }

    init() {
        compactMode = defaults.object(forKey: "compactMode") as? Bool ?? true
        highContrast = defaults.bool(forKey: "highContrast")
        appearance = AppearanceMode(rawValue: defaults.string(forKey: "appearance") ?? "") ?? .light
        oneHand = defaults.bool(forKey: "oneHand")
        surveyorAsked = defaults.bool(forKey: "surveyorAsked")
        autoBackup = defaults.object(forKey: "autoBackup") as? Bool ?? true
        surveyorName = defaults.string(forKey: "surveyorName") ?? ""
        photoQuality = PhotoQuality(rawValue: defaults.string(forKey: "photoQuality") ?? "") ?? .high
        let saved = (defaults.string(forKey: "exportFormats") ?? "").split(separator: ",").compactMap { ExportFormat(rawValue: String($0)) }
        exportFormats = saved.isEmpty ? [.pdf, .xlsx] : Set(saved)
    }
}
