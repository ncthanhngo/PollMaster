import SwiftUI
import PollMasterCore

/// Main input of a field, dispatching on type and samples.
struct FieldInput: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    var round: Int?

    private func slot(_ key: String) -> FocusSlot? { scope.focus.map { FocusSlot(state: $0, key: key) } }
    private var v: Binding<JSONValue?> { Binding(get: { fv.v }, set: { fv.v = $0 }) }
    private var other: Binding<[String: String]?> { Binding(get: { fv.other }, set: { fv.other = $0 }) }

    var body: some View {
        switch field.type {
        case .composite: EmptyView()
        case .sessionTime: SessionTimeInput(value: v)
        case .gps: GPSInput(field: field, value: v, location: scope.location, plannedDistance: scope.plannedDistance(field))
        case .photo: MediaInput(field: field, video: false, fieldValue: $fv, scope: scope)
        case .video: MediaInput(field: field, video: true, fieldValue: $fv, scope: scope)
        case .audio: AudioInput(field: field, fieldValue: $fv, scope: scope)
        case .signature:
            Text("Ký tại màn Kiểm tra & hoàn thành.").font(.footnote).secondaryText()
        default:
            if let n = field.samples { samples(n) }
            else if field.type == .integer, let cr = field.chipsRange {
                ChipsRangeInput(field: field, numbers: cr.values(scope.lookup), value: v, focus: slot(field.id))
            }
            else if field.type == .integer, field.stepper { StepperInput(field: field, value: v, focus: slot(field.id)) }
            else {
                ScalarInput(field: field, value: v, other: other, focus: slot(field.id), location: scope.location)
            }
        }
    }

    @ViewBuilder private func samples(_ n: Int) -> some View {
        let horizontal = [.number, .integer, .time, .text, .elapsed, .compass].contains(field.type)
        let rounds = round.map { [$0] } ?? Array(0..<n)
        let cells = ForEach(rounds, id: \.self) { i in
            VStack(alignment: .leading, spacing: 2) {
                if round == nil { Text("Lần \(i + 1)").font(.caption2).secondaryText() }
                ScalarInput(field: field, value: Binding(get: { fv.sample(i).isNull ? nil : fv.sample(i) },
                                                         set: { fv.setSample(i, count: n, to: $0) }),
                            other: other, focus: slot("\(field.id)#\(i)"), location: scope.location)
            }
            .frame(maxWidth: .infinity)
        }
        if horizontal {
            HStack(alignment: .top, spacing: 8) { cells }
        } else {
            VStack(alignment: .leading, spacing: 8) { cells }
        }
    }
}
