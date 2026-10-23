import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum CaptureKind { case photo, video }

/// UIImagePickerController wrapper for taking a photo or recording a video.
struct CameraPicker: UIViewControllerRepresentable {
    let kind: CaptureKind
    var onImage: (UIImage) -> Void
    var onVideo: (URL) -> Void

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.mediaTypes = [kind == .photo ? UTType.image.identifier : UTType.movie.identifier]
        if kind == .video { p.cameraCaptureMode = .video; p.videoQuality = .typeHigh }
        p.delegate = context.coordinator
        return p
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let url = info[.mediaURL] as? URL { parent.onVideo(url) }
            else if let img = info[.originalImage] as? UIImage { parent.onImage(img) }
            picker.dismiss(animated: true)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) }
    }
}
