import Foundation
@testable import PollMasterCore

enum Fixtures {
    static let sharedDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("shared")

    static func templateData() throws -> Data { try Data(contentsOf: sharedDir.appendingPathComponent("templates/bb-tdp-01.json")) }
    static func template() throws -> Template { try TemplateLoader.decode(try templateData()) }

    static func tempDir() -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("pm-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /// A record where every required field of BB-TDP-01 is filled.
    static func filledRecord(_ t: Template) -> Record {
        var r = RecordFactory.make(template: t)
        func fill(_ f: Field, into r: inout Record) {
            guard f.required, !f.isComputed else { return }
            var fv = r.values[f.id] ?? FieldValue()
            switch f.type {
            case .text, .multiline: fv.v = "x"
            case .number, .integer: fv.v = .number(max(f.min ?? 1, 1))
            case .singleChoice: fv.v = .string(f.options![0].value)
            case .multiChoice: fv.v = .array([.string(f.options![0].value)])
            case .yesNo: fv.v = false
            case .rating: fv.v = 3
            case .date, .month, .time, .datetime: fv.v = "2026-01-01"
            case .gps: fv.v = GPSValue(lat: 10, lng: 106, accuracy: 5).json
            case .compass, .elapsed: fv.v = 90
            case .composite: fv.parts = Dictionary(uniqueKeysWithValues: (f.parts ?? []).map { ($0.id, JSONValue.number(1)) })
            default: break
            }
            if let n = f.samples { fv.v = .array(Array(repeating: f.type == .yesNo ? .bool(true) : (f.type == .time ? .string("10:00") : .number(max(f.min ?? 1, 1))), count: n)) }
            r.values[f.id] = fv
        }
        t.allFields.forEach { fill($0, into: &r) }
        return r
    }
}
