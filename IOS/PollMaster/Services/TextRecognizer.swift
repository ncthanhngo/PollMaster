import Foundation
import Vision

/// On-device text recognition (Vision) for label photos: Vietnamese + Latin when the OS supports it, else its default languages.
enum TextRecognizer {
    /// Lines of text found in the image files, top to bottom per image, without duplicates.
    static func lines(in files: [URL]) async -> [String] {
        await Task.detached(priority: .userInitiated) {
            var out: [String] = []
            for url in files {
                for line in recognize(url) where !out.contains(line) { out.append(line) }
            }
            return out
        }.value
    }

    private static func recognize(_ url: URL) -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        let supported = (try? request.supportedRecognitionLanguages()) ?? []
        if let vi = ["vi-VT", "vi"].first(where: supported.contains) {
            request.recognitionLanguages = [vi, "en-US"].filter(supported.contains)
        }
        do { try VNImageRequestHandler(url: url).perform([request]) } catch { return [] }
        return (request.results ?? [])
            .sorted { $0.boundingBox.maxY > $1.boundingBox.maxY }
            .compactMap { $0.topCandidates(1).first?.string.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
