import SwiftUI
import PollMasterCore

/// `−  n  +` for integer fields with `stepper` (step 1, honours min/max); the number itself can still be typed.
struct StepperInput: View {
    let field: Field
    @Binding var value: JSONValue?
    var focus: FocusSlot?
    @Environment(\.pmTheme) private var theme

    private var current: Int? { value?.double.map { Int($0.rounded()) } }
    private var lower: Int { field.min.map { Int($0.rounded(.up)) } ?? 0 }

    var body: some View {
        HStack(spacing: 8) {
            stepButton("minus", enabled: current.map { field.min == nil || $0 > lower } ?? true) {
                set(current.map { $0 - 1 } ?? lower)
            }
            NumberInput(placeholder: "Nhập…", integer: true, unit: field.unit, value: $value, focus: focus)
                .multilineTextAlignment(.center)
            stepButton("plus", enabled: current.map { field.max == nil || Double($0) < field.max! } ?? true) {
                set(current.map { $0 + 1 } ?? max(lower, 1))
            }
        }
    }

    private func set(_ n: Int) {
        let clamped = Swift.min(Swift.max(n, lower), field.max.map { Int($0) } ?? Int.max)
        value = .int(clamped)
    }

    private func stepButton(_ icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.title3.weight(.semibold))
                .frame(width: Swift.max(48, theme.minTouch), height: Swift.max(44, theme.minTouch))
                .background(theme.unselectedChip, in: RoundedRectangle(cornerRadius: PMRadius.s))
                .overlay(RoundedRectangle(cornerRadius: PMRadius.s).stroke(theme.highContrast ? theme.border : .clear, lineWidth: theme.borderWidth))
        }
        .buttonStyle(.plain).disabled(!enabled)
        .accessibilityLabel(icon == "plus" ? "Tăng" : "Giảm")
    }
}

/// One-tap chips `min…max` for integer fields with `chipsRange`; "Khác" opens a number box for values outside the range.
struct ChipsRangeInput: View {
    let field: Field
    let numbers: [Int]
    @Binding var value: JSONValue?
    var focus: FocusSlot?
    @State private var typing = false

    private var current: Int? { value?.double.map { Int($0.rounded()) } }
    private var outside: Bool { current.map { !numbers.contains($0) } ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 5), spacing: 6) {
                ForEach(numbers, id: \.self) { n in
                    Chip(title: "\(n)", selected: current == n && !typing, fillWidth: true, minHeight: 44) {
                        typing = false
                        value = current == n ? nil : .int(n)
                    }
                }
                Chip(title: "Khác", selected: typing || outside, fillWidth: true, minHeight: 44) { typing.toggle() }
            }
            if typing || outside {
                NumberInput(placeholder: "Nhập…", integer: true, unit: field.unit, value: $value, focus: focus)
            }
        }
    }
}
