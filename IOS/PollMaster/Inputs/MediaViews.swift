import SwiftUI
import AVKit
import PollMasterCore

enum ThumbnailLoader {
    /// Small thumbnails by file; a file name is never reused for other content except after `forget` (the file was deleted).
    nonisolated(unsafe) private static let cache: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.countLimit = 300
        return c
    }()

    static func cached(_ url: URL) -> UIImage? { cache.object(forKey: url as NSURL) }
    static func forget(_ url: URL) { cache.removeObject(forKey: url as NSURL) }

    static func load(url: URL, video: Bool) async -> UIImage? {
        if let hit = cached(url) { return hit }
        let image: UIImage?
        if video {
            let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: 400, height: 400)
            image = (try? await gen.image(at: .zero).image).map { UIImage(cgImage: $0) }
        } else {
            image = ImageTools.cgImage(at: url, maxPixel: 400).map { UIImage(cgImage: $0) }
        }
        if let image { cache.setObject(image, forKey: url as NSURL) }
        return image
    }
}

struct Thumbnail: View {
    let url: URL
    let video: Bool
    var size: CGFloat = 92
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.tertiarySystemFill)
            if let image = image ?? ThumbnailLoader.cached(url) { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: video ? "video" : "photo").secondaryText() }
            if video { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white).shadow(radius: 2) }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: PMRadius.s))
        .task(id: url) { image = await ThumbnailLoader.load(url: url, video: video) }
    }
}

/// Full-screen photo (pinch to 5x, drag when zoomed, double tap 1x / 2.5x) or video.
struct MediaViewer: View {
    let url: URL
    let video: Bool
    var onDelete: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete = false
    @State private var image: UIImage?
    @State private var failed = false
    @State private var player: AVPlayer?
    @State private var base: CGFloat = 1
    @State private var offset: CGSize = .zero
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var drag: CGSize = .zero

    private static let maxScale: CGFloat = 5

    var body: some View {
        NavigationStack {
            Group {
                if video {
                    if let player { VideoPlayer(player: player) }
                } else if let image {
                    zoomable(image)
                } else if failed {
                    Text("Không mở được tệp").secondaryText()
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
            .task { await load() }
            .onDisappear { player?.pause() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } }
                if onDelete != nil {
                    ToolbarItem(placement: .destructiveAction) { Button("Xóa", role: .destructive) { confirmDelete = true } }
                }
            }
            .confirmationDialog("Xóa tệp này?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Xóa", role: .destructive) { onDelete?(); dismiss() }
            }
        }
    }

    /// Loaded once, down-sampled to ~3x the screen (a full camera frame is far more than the screen can show).
    private func load() async {
        if video {
            if player == nil { player = AVPlayer(url: url) }
            return
        }
        guard image == nil else { return }
        let screen = UIScreen.main.bounds.size
        let side = Int(max(screen.width, screen.height) * 3)
        let url = url
        image = await Task.detached { ImageTools.cgImage(at: url, maxPixel: side).map { UIImage(cgImage: $0) } }.value
        failed = image == nil
    }

    private func zoomable(_ img: UIImage) -> some View {
        GeometryReader { geo in
            let scale = min(max(base * pinch, 1), Self.maxScale)
            Image(uiImage: img).resizable().scaledToFit()
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(clamped(CGSize(width: offset.width + drag.width, height: offset.height + drag.height), scale, geo.size))
                .gesture(MagnifyGesture()
                    .updating($pinch) { value, state, _ in state = value.magnification }
                    .onEnded { value in
                        base = min(max(base * value.magnification, 1), Self.maxScale)
                        offset = base == 1 ? .zero : clamped(offset, base, geo.size)
                    })
                .simultaneousGesture(DragGesture()
                    .updating($drag) { value, state, _ in if base > 1 { state = value.translation } }
                    .onEnded { value in
                        guard base > 1 else { return }
                        offset = clamped(CGSize(width: offset.width + value.translation.width, height: offset.height + value.translation.height), base, geo.size)
                    })
                .onTapGesture(count: 2) {
                    withAnimation(.snappy) {
                        base = base > 1 ? 1 : 2.5
                        offset = .zero
                    }
                }
        }
    }

    /// The zoomed picture may move only as far as its edges reach the screen edges.
    private func clamped(_ o: CGSize, _ scale: CGFloat, _ size: CGSize) -> CGSize {
        let mx = size.width * (scale - 1) / 2, my = size.height * (scale - 1) / 2
        return CGSize(width: min(max(o.width, -mx), mx), height: min(max(o.height, -my), my))
    }
}
