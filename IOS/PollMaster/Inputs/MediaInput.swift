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

/// Thumbnail grid with "Chụp" / "Thư viện" for photo, video and attachPhoto fields.
struct MediaInput: View {
    let field: Field
    let video: Bool
    @Binding var fieldValue: FieldValue
    let scope: FieldScope
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var capture: CaptureKind?
    @State private var viewing: Media?
    @State private var error: String?

    private var list: [Media] {
        get { field.isMedia ? fieldValue.mediaInV : (fieldValue.media ?? []) }
    }

    private func store(_ new: [Media]) {
        if field.isMedia { fieldValue.mediaInV = new } else { fieldValue.media = new.isEmpty ? nil : new }
    }

    private var mediaFieldId: String { RecordNaming.mediaFieldId(table: scope.tableId, column: field.id) }
    private var remaining: Int? { field.maxCount.map { max($0 - list.count, 0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !list.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], alignment: .leading, spacing: 8) {
                    ForEach(list, id: \.file) { m in
                        Button { viewing = m } label: { Thumbnail(url: scope.media.url(m), video: video) }
                            .buttonStyle(.plain)
                            .contextMenu { Button("Xóa", systemImage: "trash", role: .destructive) { remove(m) } }
                    }
                }
            }
            HStack {
                if CameraPicker.isAvailable {
                    Button { capture = video ? .video : .photo } label: { Label("Chụp", systemImage: video ? "video" : "camera") }
                        .buttonStyle(.bordered)
                }
                PhotosPicker(selection: $pickerItems, maxSelectionCount: remaining.map { max($0, 1) },
                             matching: video ? .videos : .images) { Label("Thư viện", systemImage: "photo.on.rectangle") }
                    .buttonStyle(.bordered)
                if let n = field.minCount { Text("\(list.count)/\(n)").font(.caption).foregroundStyle(.secondary) }
            }
            .disabled(remaining == 0)
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
        .fullScreenCover(item: $capture, content: { kind in
            CameraPicker(kind: kind, onImage: { add(image: $0) }, onVideo: { add(videoURL: $0, camera: true) }).ignoresSafeArea()
        })
        .fullScreenCover(item: $viewing) { m in
            MediaViewer(url: scope.media.url(m), video: video, onDelete: { remove(m) })
        }
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await importPicked(items); pickerItems = [] }
        }
    }

    private func importPicked(_ items: [PhotosPickerItem]) async {
        for item in items {
            do {
                if video {
                    guard let m = try await item.loadTransferable(type: PickedMovie.self) else { throw MediaFailure.unreadable }
                    add(videoURL: m.url, camera: false)
                    try? FileManager.default.removeItem(at: m.url)
                } else {
                    guard let d = try await item.loadTransferable(type: Data.self) else { throw MediaFailure.unreadable }
                    let m = try scope.media.saveImage(d, fieldId: mediaFieldId, location: nil)
                    store(list + [m])
                }
            } catch { self.error = error.localizedDescription }
        }
    }

    private func add(image: UIImage) {
        do {
            let m = try scope.media.saveImage(image, fieldId: mediaFieldId, location: scope.location.lastKnown)
            store(list + [m]); error = nil
        } catch { self.error = error.localizedDescription }
    }

    private func add(videoURL: URL, camera: Bool) {
        do {
            let m = try scope.media.saveVideo(from: videoURL, fieldId: mediaFieldId, location: camera ? scope.location.lastKnown : nil)
            store(list + [m]); error = nil
        } catch { self.error = error.localizedDescription }
    }

    private func remove(_ m: Media) {
        scope.media.remove(m)
        store(list.filter { $0 != m })
    }
}

extension CaptureKind: Identifiable { var id: Int { self == .photo ? 0 : 1 } }
