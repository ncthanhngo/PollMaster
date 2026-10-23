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

    init() {
        surveyorName = defaults.string(forKey: "surveyorName") ?? ""
        photoQuality = PhotoQuality(rawValue: defaults.string(forKey: "photoQuality") ?? "") ?? .high
        let saved = (defaults.string(forKey: "exportFormats") ?? "").split(separator: ",").compactMap { ExportFormat(rawValue: String($0)) }
        exportFormats = saved.isEmpty ? [.pdf, .xlsx] : Set(saved)
    }
}
