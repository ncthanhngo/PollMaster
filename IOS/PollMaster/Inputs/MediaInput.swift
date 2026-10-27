import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import PollMasterCore

private struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(received.file.pathExtension)")
            try FileManager.default.copyItem(at: received.file, to: tmp)
            return PickedMovie(url: tmp)
        }
    }
}

/// Runs picked / captured media one after another, so file names are taken in order and items land in the order they came.
@MainActor private final class MediaQueue {
    private var tail: Task<Void, Never>?

    func enqueue(_ work: @escaping @MainActor () async -> Void) {
        let previous = tail
        tail = Task {
            await previous?.value
            await work()
        }
    }
}

/// Thumbnail grid with "Chụp" / "Thư viện" for photo, video and attachPhoto fields.
struct MediaInput: View {
    @Environment(\.pmTheme) private var theme
    let field: Field
    let video: Bool
    @Binding var fieldValue: FieldValue
    let scope: FieldScope
    /// Reduced layout: small thumbnails + one capture button with this title (photoRef, "Quay video lượt này").
    var captureTitle: String?
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var capture: CaptureKind?
    @State private var viewing: Media?
    @State private var error: String?
    @State private var queue = MediaQueue()
    /// Items being decoded / encoded / copied in the background.
    @State private var working = 0

    private var list: [Media] {
        get { field.isMedia ? fieldValue.mediaInV : (fieldValue.media ?? []) }
    }

    private func store(_ new: [Media]) {
        if field.isMedia { fieldValue.mediaInV = new } else { fieldValue.media = new.isEmpty ? nil : new }
    }

    private var hasCamera: Bool { CameraPicker.isAvailable }
    private var mediaFieldId: String { RecordNaming.mediaFieldId(table: scope.tableId, column: field.id) }
    private var remaining: Int? { field.maxCount.map { max($0 - list.count, 0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !list.isEmpty {
                let size: CGFloat = captureTitle == nil ? 92 : 52
                LazyVGrid(columns: [GridItem(.adaptive(minimum: size), spacing: 8)], alignment: .leading, spacing: 8) {
                    ForEach(Array(list.enumerated()), id: \.element.file) { i, m in
                        Button { viewing = m } label: { Thumbnail(url: scope.media.url(m), video: video, size: size) }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(video ? "Video" : "Ảnh") \(i + 1)")
                            .contextMenu { Button("Xóa", systemImage: "trash", role: .destructive) { remove(m) } }
                    }
                }
            }
            let cam = hasCamera
            HStack {
                if cam {
                    Button { capture = video ? .video : .photo } label: {
                        Label(captureTitle ?? "Chụp", systemImage: video ? "video" : "camera").frame(minHeight: captureTitle == nil ? 0 : 44)
                    }
                    .buttonStyle(.bordered)
                }
                PhotosPicker(selection: $pickerItems, maxSelectionCount: remaining.map { max($0, 1) },
                             matching: video ? .videos : .images) {
                    if captureTitle != nil, cam { Image(systemName: "photo.on.rectangle").frame(minHeight: 28) }
                    else { Label(captureTitle ?? "Thư viện", systemImage: "photo.on.rectangle").frame(minHeight: captureTitle == nil ? 0 : 28) }
                }
                .buttonStyle(.bordered)
                if let n = field.minCount { Text("\(list.count)/\(n)").font(.caption).secondaryText() }
            }
            .disabled(remaining == 0)
            if working > 0 {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Đang xử lý…").font(.caption).secondaryText() }
            }
            if let error { Text(error).font(.caption).foregroundStyle(theme.alert) }
        }
        .fullScreenCover(item: $capture, content: { kind in
            CameraPicker(kind: kind, onImage: { add(image: $0) }, onVideo: { add(videoURL: $0, camera: true) }).ignoresSafeArea()
        })
        .fullScreenCover(item: $viewing) { m in
            MediaViewer(url: scope.media.url(m), video: video, onDelete: { remove(m) })
        }
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            queue.enqueue { await importPicked(items); pickerItems = [] }
        }
    }

    private func importPicked(_ items: [PhotosPickerItem]) async {
        let fieldId = mediaFieldId
        for item in items {
            do {
                if video {
                    guard let m = try await item.loadTransferable(type: PickedMovie.self) else { throw MediaFailure.unreadable }
                    await save { svc in try svc.saveVideo(from: m.url, fieldId: fieldId, location: nil) }
                    try? FileManager.default.removeItem(at: m.url)
                } else {
                    guard let d = try await item.loadTransferable(type: Data.self) else { throw MediaFailure.unreadable }
                    await save { svc in try svc.saveImage(d, fieldId: fieldId, location: nil) }
                }
            } catch { self.error = error.localizedDescription }
        }
    }

    private func add(image: UIImage) {
        let place = scope.location.lastKnown, fieldId = mediaFieldId
        queue.enqueue { await save { svc in try svc.saveImage(image, fieldId: fieldId, location: place) } }
    }

    private func add(videoURL: URL, camera: Bool) {
        let place = camera ? scope.location.lastKnown : nil, fieldId = mediaFieldId
        queue.enqueue { await save { svc in try svc.saveVideo(from: videoURL, fieldId: fieldId, location: place) } }
    }

    /// Does the slow part (decode, JPEG encode, file copy) on a background thread, then adds the result to the field.
    private func save(_ job: @escaping @Sendable (MediaService) throws -> Media) async {
        working += 1
        defer { working -= 1 }
        let svc = scope.media
        do {
            let m = try await Task.detached(priority: .userInitiated) { try job(svc) }.value
            store(list + [m]); error = nil
        } catch { self.error = error.localizedDescription }
    }

    private func remove(_ m: Media) {
        scope.media.remove(m)
        store(list.filter { $0 != m })
    }
}

extension CaptureKind: Identifiable { var id: Int { self == .photo ? 0 : 1 } }
