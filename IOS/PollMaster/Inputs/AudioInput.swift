import SwiftUI
import AVFoundation
import PollMasterCore

/// Record / stop button and the list of recordings (play, delete) for `audio` fields.
@MainActor @Observable
final class AudioController: NSObject, AVAudioPlayerDelegate {
    var recording = false
    var elapsed: TimeInterval = 0
    var playing: String?
    var error: String?
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var tick: Task<Void, Never>?
    private var tempURL: URL?

    func start() async {
        error = nil
        guard await AVAudioApplication.requestRecordPermission() else {
            error = "Chưa được cấp quyền micro. Bật trong Cài đặt > PollMaster."; return
        }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try AVAudioSession.sharedInstance().setActive(true)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).m4a")
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100,
                                           AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue]
            let r = try AVAudioRecorder(url: url, settings: settings)
            guard r.record() else { throw MediaFailure.unreadable }
            recorder = r; tempURL = url; recording = true; elapsed = 0
            tick = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(250))
                    self?.elapsed = self?.recorder?.currentTime ?? 0
                }
            }
        } catch { self.error = "Không ghi âm được: \(error.localizedDescription)" }
    }

    /// Stops and returns the temporary file (nil when nothing was recorded).
    func stop() -> URL? {
        tick?.cancel()
        recorder?.stop()
        recorder = nil; recording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        defer { tempURL = nil }
        return elapsed > 0.3 ? tempURL : nil
    }

    func toggle(_ url: URL, id: String) {
        if playing == id { player?.stop(); playing = nil; return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            player = try AVAudioPlayer(contentsOf: url)
            player?.delegate = self
            player?.play(); playing = id
        } catch { self.error = "Không phát được bản ghi." }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.playing = nil }
    }

    func stopAll() { player?.stop(); playing = nil; if recording { _ = stop() } }
}

struct AudioInput: View {
    let field: Field
    @Binding var fieldValue: FieldValue
    let scope: FieldScope
    @State private var audio = AudioController()
    @Environment(\.pmTheme) private var theme

    private var list: [Media] { fieldValue.mediaInV }
    private var mediaFieldId: String { RecordNaming.mediaFieldId(table: scope.tableId, column: field.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Button { Task { await toggleRecording() } } label: {
                    ZStack {
                        Circle().fill(audio.recording ? Color.red : theme.unselectedChip).frame(width: 56, height: 56)
                            .overlay(Circle().stroke(Color.red, lineWidth: 2))
                        if audio.recording { RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 20, height: 20) }
                        else { Circle().fill(Color.red).frame(width: 24, height: 24) }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(audio.recording ? "Dừng ghi âm" : "Ghi âm")
                if audio.recording {
                    Text(ValueFormatter.mmss(audio.elapsed)).font(.title3.monospacedDigit().weight(.semibold)).foregroundStyle(.red)
                } else {
                    Text(list.isEmpty ? "Bấm để ghi âm" : "Ghi thêm").font(.subheadline).secondaryText()
                }
            }
            ForEach(list, id: \.file) { m in
                HStack(spacing: 10) {
                    Button { audio.toggle(scope.media.url(m), id: m.file) } label: {
                        Image(systemName: audio.playing == m.file ? "stop.circle.fill" : "play.circle.fill").font(.title2)
                    }
                    .buttonStyle(.plain)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(m.fileName).font(.footnote).lineLimit(1)
                        Text(duration(m)).font(.caption).secondaryText()
                    }
                    Spacer()
                    Button(role: .destructive) { remove(m) } label: { Image(systemName: "trash") }.buttonStyle(.plain).foregroundStyle(.red)
                }
                .inputBox()
            }
            if let e = audio.error { Text(e).font(.caption).foregroundStyle(theme.alert) }
        }
        .onDisappear { audio.stopAll() }
    }

    private func duration(_ m: Media) -> String {
        guard let p = try? AVAudioPlayer(contentsOf: scope.media.url(m)) else { return "—" }
        return ValueFormatter.mmss(p.duration)
    }

    private func toggleRecording() async {
        if audio.recording {
            guard let url = audio.stop() else { return }
            do {
                let m = try scope.media.saveAudio(from: url, fieldId: mediaFieldId, location: scope.location.lastKnown)
                try? FileManager.default.removeItem(at: url)
                fieldValue.mediaInV = list + [m]
            } catch { audio.error = error.localizedDescription }
        } else { await audio.start() }
    }

    private func remove(_ m: Media) {
        if audio.playing == m.file { audio.stopAll() }
        scope.media.remove(m)
        fieldValue.mediaInV = list.filter { $0 != m }
    }
}
