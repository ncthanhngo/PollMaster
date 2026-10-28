import SwiftUI
import PollMasterCore

/// date / time / datetime / month entry; empty until the user picks something.
struct DateTimeInput: View {
    let type: FieldType
    @Binding var value: JSONValue?

    private var pattern: String {
        switch type {
        case .date: return "yyyy-MM-dd"
        case .time: return "HH:mm"
        case .month: return "yyyy-MM"
        default: return ""
        }
    }

    private var date: Date? {
        guard let s = value?.string else { return nil }
        return type == .datetime ? ISODate.parse(s) : ISODate.parse(s, pattern: pattern)
    }

    private func encode(_ d: Date) -> JSONValue {
        .string(type == .datetime ? ISODate.string(d) : ISODate.format(d, pattern))
    }

    var body: some View {
        if type == .month {
            MonthInput(value: $value)
        } else if let d = date {
            HStack {
                DatePicker("", selection: Binding(get: { d }, set: { value = encode($0) }), displayedComponents: components)
                    .labelsHidden()
                Spacer()
                Button { value = nil } label: { Image(systemName: "xmark.circle.fill").secondaryText().hitSlop(14) }
                    .buttonStyle(.plain).accessibilityLabel("Xóa ngày")
            }
        } else {
            Button(placeholder, systemImage: type == .time ? "clock" : "calendar") { value = encode(Date()) }
                .fieldAction()
        }
    }

    private var components: DatePickerComponents {
        switch type {
        case .date: return .date
        case .time: return .hourAndMinute
        default: return [.date, .hourAndMinute]
        }
    }

    private var placeholder: String {
        switch type {
        case .date: return "Chọn ngày"
        case .time: return "Chọn giờ"
        default: return "Chọn ngày giờ"
        }
    }
}

struct MonthInput: View {
    @Binding var value: JSONValue?

    private var parts: (year: Int, month: Int)? {
        guard let s = value?.string else { return nil }
        let p = s.split(separator: "-").compactMap { Int($0) }
        return p.count == 2 ? (p[0], p[1]) : nil
    }

    var body: some View {
        let now = Calendar.current.dateComponents([.year, .month], from: Date())
        if let cur = parts {
            HStack {
                Picker("Tháng", selection: Binding(get: { cur.month }, set: { set(cur.year, $0) })) {
                    ForEach(1...12, id: \.self) { Text("Tháng \($0)").tag($0) }
                }
                Picker("Năm", selection: Binding(get: { cur.year }, set: { set($0, cur.month) })) {
                    ForEach(((now.year ?? 2026) - 30)...((now.year ?? 2026) + 2), id: \.self) { Text(String($0)).tag($0) }
                }
                Button { value = nil } label: { Image(systemName: "xmark.circle.fill").secondaryText().hitSlop(14) }
                    .buttonStyle(.plain).accessibilityLabel("Xóa ngày")
            }
        } else {
            Button("Chọn tháng", systemImage: "calendar") { set(now.year ?? 2026, now.month ?? 1) }
                .fieldAction()
        }
    }

    private func set(_ y: Int, _ m: Int) { value = .string(String(format: "%04d-%02d", y, m)) }
}

/// One line "18:41 → …" with ✎ opening a sheet to edit start and end (ux-spec L11).
struct SessionTimeInput: View {
    @Binding var value: JSONValue?
    @State private var editing = false

    var body: some View {
        let st = SessionTime(value) ?? SessionTime(start: Date())
        HStack(spacing: 8) {
            Image(systemName: "clock").secondaryText()
            Text("\(ISODate.format(st.start, "HH:mm")) → \(st.end.map { ISODate.format($0, "HH:mm") } ?? "…")")
                .font(.body.monospacedDigit().weight(.medium))
            Spacer()
            Button { editing = true } label: { Image(systemName: "pencil").frame(minWidth: 44, minHeight: 44) }
                .buttonStyle(.bordered).accessibilityLabel("Sửa giờ")
        }
        .sheet(isPresented: $editing) { SessionTimeSheet(value: $value) }
    }
}

private struct SessionTimeSheet: View {
    @Binding var value: JSONValue?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let st = SessionTime(value) ?? SessionTime(start: Date())
        NavigationStack {
            Form {
                DatePicker("Bắt đầu", selection: Binding(get: { st.start }, set: { var n = st; n.start = $0; value = n.json }))
                if let end = st.end {
                    DatePicker("Kết thúc", selection: Binding(get: { end }, set: { var n = st; n.end = $0; value = n.json }))
                    Button("Xóa giờ kết thúc", role: .destructive) { var n = st; n.end = nil; value = n.json }
                } else {
                    Text("Kết thúc: tự ghi khi bấm Hoàn thành").font(.footnote).secondaryText()
                }
            }
            .navigationTitle("Giờ khảo sát")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}

/// mm:ss entry for `elapsed` fields; stored as seconds.
struct ElapsedInput: View {
    @Binding var value: JSONValue?
    var focus: FocusSlot?
    @State private var text = ""

    var body: some View {
        TextField("mm:ss", text: $text)
            .keyboardType(.numbersAndPunctuation)
            .textFieldStyle(.plain).inputBox()
            .focusSlot(focus)
            .onAppear { text = value?.double.map(ValueFormatter.mmss) ?? "" }
            .onChange(of: text) { _, new in
                if let s = Self.parse(new) { if value?.double != s { value = .number(s) } }
                else if new.isEmpty { value = nil }
            }
            .onChange(of: value) { _, new in
                if Self.parse(text) != new?.double { text = new?.double.map(ValueFormatter.mmss) ?? "" }
            }
    }

    static func parse(_ s: String) -> Double? {
        let p = s.split(separator: ":", omittingEmptySubsequences: false).map { Double($0.trimmingCharacters(in: .whitespaces)) }
        if p.count == 1, let v = p[0] { return v }
        if p.count == 2, let m = p[0], let sec = p[1] { return m * 60 + sec }
        return nil
    }
}
