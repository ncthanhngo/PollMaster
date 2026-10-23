import SwiftUI
import UIKit
import PollMasterCore

/// T0 + successive marks (T1..Tn); the clock runs off the stored start time so it survives app kills.
struct StopwatchBlock: View {
    let model: RecordEditorModel
    let table: TableDef
    let row: Row
    let columns: [String]
    /// Thin strip `mm:ss · [Ghi T4] · ↶` once the form is scrolled (ux-spec L1).
    var compact = false
    /// Called after a mark was recorded (cue banner).
    var onMark: (String) -> Void = { _ in }
    @Environment(\.pmTheme) private var theme

    private func isFilled(_ col: String) -> Bool { row.values[col]?.v?.double != nil }
    private var nextColumn: String? { columns.first { !isFilled($0) } }
    private var running: Bool { row.stopwatchStart != nil && nextColumn != nil }

    var body: some View {
        if compact { strip } else { full }
    }

    private var strip: some View {
        HStack(spacing: 10) {
            TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                Text(clock(ctx.date)).font(.system(size: 22, weight: .semibold, design: .monospaced))
            }
            Spacer(minLength: 4)
            if row.stopwatchStart == nil {
                Button { mutate { $0.stopwatchStart = Date() } } label: { Text("Bắt đầu (T0)").frame(minHeight: 32) }
                    .buttonStyle(.borderedProminent)
            } else if let next = nextColumn {
                Button { mark(next) } label: { Text("Ghi \(next)").font(.headline).frame(minWidth: 90, minHeight: 32) }
                    .buttonStyle(.borderedProminent)
            } else {
                Label("Đủ \(columns.count) mốc", systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.subheadline)
            }
            Button { undo() } label: { Image(systemName: "arrow.uturn.backward").frame(minWidth: 32, minHeight: 32) }
                .buttonStyle(.bordered).disabled(row.stopwatchStart == nil).accessibilityLabel("Hoàn tác mốc")
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.border, lineWidth: theme.borderWidth))
        .disabled(model.isLocked)
        .modifier(KeepAwake(running: running))
    }

    private var full: some View {
        VStack(spacing: 12) {
            TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                Text(clock(ctx.date)).font(.system(size: 56, weight: .semibold, design: .monospaced))
                    .frame(maxWidth: .infinity)
            }
            if row.stopwatchStart == nil {
                Button { mutate { $0.stopwatchStart = Date() } } label: { Text("Bắt đầu (T0)").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large).frame(minHeight: 56)
            } else if let next = nextColumn {
                Button { mark(next) } label: { Text("Ghi mốc \(next)").font(.title3.weight(.semibold)).frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large).frame(minHeight: 56)
            } else {
                Label("Đã ghi đủ \(columns.count) mốc", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            }
            Button("Hoàn tác mốc", systemImage: "arrow.uturn.backward") { undo() }
                .buttonStyle(.bordered).disabled(row.stopwatchStart == nil)
        }
        .padding(14)
        .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.border, lineWidth: theme.borderWidth))
        .disabled(model.isLocked)
        .modifier(KeepAwake(running: running))
    }

    private func clock(_ now: Date) -> String {
        guard let start = row.stopwatchStart else { return "00:00" }
        if nextColumn == nil, let last = columns.last, let s = row.values[last]?.v?.double { return ValueFormatter.mmss(s) }
        return ValueFormatter.mmss(max(0, now.timeIntervalSince(start)))
    }

    private func mutate(_ body: @escaping (inout Row) -> Void) { model.mutateRow(table.id, row.id, body) }

    private func mark(_ col: String) {
        guard let start = row.stopwatchStart else { return }
        let seconds = Date().timeIntervalSince(start).rounded()
        mutate { r in
            var v = r.values[col] ?? FieldValue()
            v.v = .number(max(0, seconds))
            r.values[col] = v
        }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onMark(col)
    }

    private func undo() {
        if let last = columns.last(where: isFilled) {
            mutate { r in
                var v = r.values[last] ?? FieldValue()
                v.v = nil
                r.values[last] = v.isBlank ? nil : v
            }
        } else {
            mutate { $0.stopwatchStart = nil }
        }
    }
}

/// Keeps the screen on while the stopwatch runs.
private struct KeepAwake: ViewModifier {
    let running: Bool
    func body(content: Content) -> some View {
        content
            .onAppear { UIApplication.shared.isIdleTimerDisabled = running }
            .onChange(of: running) { _, now in UIApplication.shared.isIdleTimerDisabled = now }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}
