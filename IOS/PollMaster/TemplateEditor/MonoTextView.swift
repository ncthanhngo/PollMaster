import SwiftUI
import UIKit

/// Plain monospaced text box for JSON: no smart quotes, dashes or autocorrection (they would break the JSON); "Xong" dismisses the keyboard.
struct MonoTextView: UIViewRepresentable {
    @Binding var text: String

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.font = UIFontMetrics(forTextStyle: .footnote).scaledFont(for: .monospacedSystemFont(ofSize: 13, weight: .regular))
        v.adjustsFontForContentSizeCategory = true
        v.autocorrectionType = .no; v.autocapitalizationType = .none; v.smartQuotesType = .no; v.smartDashesType = .no; v.smartInsertDeleteType = .no
        v.spellCheckingType = .no
        v.backgroundColor = .secondarySystemBackground
        v.layer.cornerRadius = 10
        v.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
        v.delegate = context.coordinator
        let bar = UIToolbar(frame: CGRect(x: 0, y: 0, width: 100, height: 44))
        bar.items = [UIBarButtonItem(systemItem: .flexibleSpace), UIBarButtonItem(title: "Xong", style: .done, target: v, action: #selector(UIResponder.resignFirstResponder))]
        v.inputAccessoryView = bar
        v.text = text
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        if v.text != text { v.text = text }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        let parent: MonoTextView
        init(_ p: MonoTextView) { parent = p }
        func textViewDidChange(_ v: UITextView) { parent.text = v.text }
    }
}
