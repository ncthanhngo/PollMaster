import SwiftUI
import PollMasterCore

/// Decimal text entry that keeps in-progress text ("1.", "-") while the stored value is a number.
struct NumberInput: View {
    var placeholder: String = ""
    var integer: Bool
    @Binding var value: JSONValue?
    var focus: FocusSlot?
    @State private var text = ""

    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(integer ? .numbersAndPunctuation : .decimalPad)
            .textFieldStyle(.roundedBorder)
            .submitLabel(.next)
            .focusSlot(focus)
            .onAppear { text = format(value) }
            .onChange(of: text) { _, new in commit(new) }
            .onChange(of: value) { _, new in
                if parse(text) != new?.double { text = format(new) }
            }
    }

    private func parse(_ s: String) -> Double? {
        let t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return Double(t)
    }

    private func format(_ v: JSONValue?) -> String { v?.double.map { ValueFormatter.number($0) } ?? "" }

    private func commit(_ s: String) {
        guard let d = parse(s) else { if s.isEmpty { value = nil }; return }
        let n: JSONValue = .number(integer ? d.rounded() : d)
        if value != n { value = n }
    }
}

struct FocusSlot {
    var state: FocusState<String?>.Binding
    var key: String
}

extension View {
    @ViewBuilder func focusSlot(_ slot: FocusSlot?) -> some View {
        if let slot { focused(slot.state, equals: slot.key) } else { self }
    }
}
