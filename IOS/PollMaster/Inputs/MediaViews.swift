import SwiftUI
import AVKit
import PollMasterCore

enum ThumbnailLoader {
    static func load(url: URL, video: Bool) async -> UIImage? {
        if video {
            let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: 400, height: 400)
            return (try? await gen.image(at: .zero).image).map { UIImage(cgImage: $0) }
        }
        return ImageTools.cgImage(at: url, maxPixel: 400).map { UIImage(cgImage: $0) }
    }
}

struct Thumbnail: View {
    let url: URL
    let video: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.tertiarySystemFill)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: video ? "video" : "photo").foregroundStyle(.secondary) }
            if video { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white).shadow(radius: 2) }
        }
        .frame(width: 92, height: 92)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: url) { image = await ThumbnailLoader.load(url: url, video: video) }
    }
}

struct MediaViewer: View {
    let url: URL
    let video: Bool
    var onDelete: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete = false
    @State private var scale: CGFloat = 1

    var body: some View {
        NavigationStack {
            Group {
                if video {
                    VideoPlayer(player: AVPlayer(url: url))
                } else if let img = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: img).resizable().scaledToFit()
                        .scaleEffect(scale)
                        .gesture(MagnifyGesture().onChanged { scale = max(1, $0.magnification) })
                } else {
                    Text("Không mở được tệp").foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
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
}
