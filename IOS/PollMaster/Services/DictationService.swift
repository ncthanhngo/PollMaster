import Foundation
import AVFoundation
import Observation
import Speech

/// Speech to text (vi-VN), on-device when the recogniser supports it. One dictation at a time.
@MainActor @Observable
final class DictationService {
    private(set) var listening = false
    var error: String?

    @ObservationIgnored private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "vi-VN"))
    @ObservationIgnored private let engine = AVAudioEngine()
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?

    /// No recogniser for vi-VN on this device / OS: the mic button stays hidden.
    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    /// Starts listening; `onText` receives the whole transcription so far (partial results too).
    func start(onText: @escaping @MainActor (String) -> Void) async {
        guard !listening, let recognizer, recognizer.isAvailable else { return }
        error = nil
        guard await Self.authorized() else { error = "Chưa cấp quyền micro hoặc nhận dạng giọng nói."; return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        request = req
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let input = engine.inputNode
            input.removeTap(onBus: 0)
            Self.feed(input, to: req)
            engine.prepare()
            try engine.start()
        } catch {
            self.error = "Không bật được micro: \(error.localizedDescription)"
            finish()
            return
        }
        listening = true
        task = Self.recognize(recognizer, req) { [weak self] text, done in
            Task { @MainActor in
                guard let self else { return }
                if let text { onText(text) }
                if done { self.finish() }
            }
        }
    }

    func stop() {
        request?.endAudio()
        finish(cancelTask: false)
    }

    private func finish(cancelTask: Bool = true) {
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        if cancelTask { task?.cancel() }
        request = nil; task = nil; listening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // The system calls these callbacks on its own queues (audio render thread, speech queue). Closures written in this
    // @MainActor class would be main-actor isolated and Swift 6 traps when they run off the main thread, so they are
    // made in nonisolated functions.

    /// Microphone buffers go straight to the request, on the audio thread.
    nonisolated private static func feed(_ input: AVAudioInputNode, to req: SFSpeechAudioBufferRecognitionRequest) {
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in req.append(buffer) }
    }

    /// `update(text, done)` on the recogniser's queue: the transcription so far, and whether it ended (final or error).
    nonisolated private static func recognize(_ recognizer: SFSpeechRecognizer, _ req: SFSpeechAudioBufferRecognitionRequest,
                                              update: @escaping @Sendable (String?, Bool) -> Void) -> SFSpeechRecognitionTask {
        recognizer.recognitionTask(with: req) { result, err in
            update(result?.bestTranscription.formattedString, result?.isFinal == true || err != nil)
        }
    }

    nonisolated private static func authorized() async -> Bool {
        let speech = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) }
        }
        guard speech else { return false }
        return await AVAudioApplication.requestRecordPermission()
    }
}
