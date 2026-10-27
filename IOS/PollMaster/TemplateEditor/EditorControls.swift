import SwiftUI
import PollMasterCore

extension TemplateEditorModel {
    /// Text of `key` on the node at `p`; empty text removes the key (the core keeps it for id/label/title).
    func str(_ p: DocPath, _ key: String) -> Binding<String> {
        Binding(get: { self.text(p, key) }, set: { self.set(p, key, .string($0)) })
    }

    /// On = `true`, off = the key is removed.
    func flag(_ p: DocPath, _ key: String) -> Binding<Bool> {
        Binding(get: { self.object(p)[key]?.bool ?? false }, set: { self.set(p, key, $0 ? .bool(true) : nil) })
    }

    func num(_ p: DocPath, _ key: String) -> Binding<Double?> {
        Binding(get: { self.object(p)[key]?.double }, set: { self.set(p, key, $0.map(JSONValue.number)) })
    }
}

/// A row of a list in the editor: icon, code, name, kind; ↑ ↓ and a ⋯ menu stay on the row (below it at the largest text sizes).
struct EditorRow<MenuContent: View>: View {
    var icon: String
    var code: String?
    var title: String
    var subtitle: String
    var canUp: Bool
    var canDown: Bool
    var issue: TemplateIssue.Severity?
    var open: () -> Void
    var up: () -> Void
    var down: () -> Void
    @ViewBuilder var menu: () -> MenuContent
    @Environment(\.dynamicTypeSize) private var size
    @Environment(\.pmTheme) private var theme

    var body: some View {
        let layout = size.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6)) : AnyLayout(HStackLayout(spacing: 4))
        layout {
            Button(action: open) {
                HStack(spacing: 10) {
                    IconBadge(systemImage: icon, size: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title.isEmpty ? "(chưa đặt tên)" : title).font(.subheadline.weight(.medium)).lineLimit(2).multilineTextAlignment(.leading)
                        HStack(spacing: 6) {
                            if let code, !code.isEmpty { Text(code).font(.caption.monospaced().weight(.semibold)) }
                            Text(subtitle).font(.caption).lineLimit(1)
                        }
                        .secondaryText()
                    }
                    Spacer(minLength: 0)
                    if let issue {
                        Image(systemName: issue == .error ? "exclamationmark.circle.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(issue == .error ? theme.alert : theme.warning)
                            .accessibilityLabel(issue == .error ? "Có lỗi" : "Có cảnh báo")
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            HStack(spacing: 0) {
                arrow("arrow.up", "Lên", canUp, up)
                arrow("arrow.down", "Xuống", canDown, down)
                Menu { menu() } label: { Image(systemName: "ellipsis.circle").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .accessibilityLabel("Thêm thao tác cho \(title)")
            }
        }
    }

    private func arrow(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.borderless).disabled(!on).accessibilityLabel(label)
    }
}

/// Number box that keeps what is being typed ("1.") and writes only when it reads as a number; empty removes the value.
struct NumField: View {
    let title: String
    @Binding var value: Double?
    var placeholder = ""
    @State private var text = ""
    @State private var loaded = false
    @FocusState private var focused: Bool

    var body: some View {
        LabeledContent {
            TextField(placeholder, text: $text).multilineTextAlignment(.trailing).keyboardType(.numbersAndPunctuation).focused($focused)
                .onChange(of: text) { _, t in commit(t) }
        } label: { Text(title) }
        .onAppear { if !loaded { text = Self.format(value); loaded = true } }
        .onChange(of: value) { _, v in if !focused { text = Self.format(v) } }
    }

    private func commit(_ t: String) {
        let s = t.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if s.isEmpty { if value != nil { value = nil } } else if let d = Double(s), d != value { value = d }
    }

    static func format(_ v: Double?) -> String {
        guard let v else { return "" }
        return v == v.rounded() && abs(v) < 1e12 ? String(Int(v)) : String(v)
    }
}

/// A code on a form: locked (with a padlock) for codes the base version already has.
struct IdField: View {
    let model: TemplateEditorModel
    let path: DocPath
    var title = "Mã"
    @State private var locked: Bool

    init(model: TemplateEditorModel, path: DocPath, title: String = "Mã") {
        self.model = model; self.path = path; self.title = title
        _locked = State(initialValue: model.isBaseId(path))
    }

    var body: some View {
        if locked {
            LabeledContent(title) { LockedValue(text: model.text(path, "id")) }
                .accessibilityHint("Mã có ở bản gốc nên không đổi được. Sửa trong JSON nâng cao nếu thật cần.")
        } else {
            LabeledContent(title) {
                TextField("Mã", text: model.str(path, "id")).multilineTextAlignment(.trailing).font(.body.monospaced())
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            }
        }
    }
}

/// A value that cannot change: padlock then the text.
struct LockedValue: View {
    let text: String
    var body: some View {
        HStack(spacing: 6) { Image(systemName: "lock.fill").font(.footnote); Text(text).font(.body.monospaced()) }.secondaryText()
    }
}
