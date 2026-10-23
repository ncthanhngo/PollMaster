import Foundation

#if DEBUG
import UIKit
import PollMasterCore

extension AppEnvironment {
    /// Simulator verification aids (launch arguments, DEBUG builds only):
    /// `-pmCreate <templateId>` new record and open it; `-pmFill 1` fill its required fields; `-pmSection <n>` push a section;
    /// `-pmRow 1` add a row to the first quick-entry table and open it; `-pmReview 1` complete it and open the checker mode review screen;
    /// `-pmSeed 1` add a few varied records for the list; `-pmTab records|templates|settings`; `-pmAppearance light|dark|system` sets the stored appearance.
    func applyDebugLaunchArguments() {
        let d = UserDefaults.standard
        if let a = d.string(forKey: "pmAppearance").flatMap(AppearanceMode.init) { settings.appearance = a }
        if let tab = d.string(forKey: "pmTab") { selectedTab = ["templates": .templates, "settings": .settings][tab] ?? .records }
        if d.bool(forKey: "pmSeed"), let t = newestTemplates.first(where: { $0.id == "BB-TDP-01" }) { seed(t); reload() }
        guard let tid = d.string(forKey: "pmCreate"), let t = newestTemplates.first(where: { $0.id == tid }) else { return }
        var r = RecordFactory.make(template: t, surveyorName: settings.surveyorName)
        if d.bool(forKey: "pmFill") || d.bool(forKey: "pmReview") { fillRequired(t, &r) }
        if d.bool(forKey: "pmRow"), let tb = t.allTables.first(where: { $0.quickEntry && $0.stopwatch != nil }) {
            let id = TableOps.addRow(&r, t, table: tb)
            r.tables[tb.id]?[0].values["kb"] = FieldValue(v: "S04")
            if let e = t.pairedTable(of: tb) { r.tables[e.id]?[0].values["kb"] = FieldValue(v: "S04") }
            try? recordStore.save(r); reload(); open(r.id)
            recordsPath.append(.row(record: r.id, table: tb.id, row: id))
            return
        }
        if d.bool(forKey: "pmReview") {
            RecordFactory.complete(&r, template: t)
            r.values["B-02"]?.review = "Đo lại: ảnh P08 chưa thấy rõ thước."
        }
        do { try recordStore.save(r) } catch { errorMessage = error.localizedDescription; return }
        reload(); open(r.id)
        if d.bool(forKey: "pmReview") {
            editor(for: r.id)?.reviewMode = true
            recordsPath.append(.review(record: r.id))
            if d.object(forKey: "pmSection") != nil { recordsPath.append(.section(record: r.id, index: d.integer(forKey: "pmSection"), focus: nil)) }
        } else if d.object(forKey: "pmSection") != nil {
            recordsPath.append(.section(record: r.id, index: d.integer(forKey: "pmSection"), focus: nil))
        }
    }

    private func fillRequired(_ t: Template, _ r: inout Record) {
        for f in t.allFields where f.required && !f.isComputed {
            var fv = r.values[f.id] ?? FieldValue()
            switch f.type {
            case .text, .multiline: fv.v = .string(f.id == "A-01" ? "TDP-014" : (f.id == "A-03" ? "12 Lê Lợi, Q.1" : "Mẫu"))
            case .number, .integer: fv.v = .number(max(f.min ?? 1, f.warnMin ?? 1))
            case .singleChoice: fv.v = .string(f.options![0].value)
            case .multiChoice: fv.v = .array([.string(f.options![0].value)])
            case .yesNo: fv.v = true
            case .rating: fv.v = 3
            case .date: fv.v = .string("2026-10-23")
            case .month: fv.v = .string("2026-10")
            case .gps: fv.v = GPSValue(lat: 10.7769, lng: 106.7009, accuracy: 8).json
            case .composite: fv.parts = Dictionary(uniqueKeysWithValues: (f.parts ?? []).map { ($0.id, JSONValue.number(1)) })
            default: break
            }
            if let n = f.samples { fv.v = .array(Array(repeating: f.type == .time ? .string("10:00") : .number(max(f.min ?? 1, 1)), count: n)) }
            r.values[f.id] = fv
        }
        r.values["HD-code"] = FieldValue(v: .string("001/A"))
    }

    private func seed(_ t: Template) {
        var done = RecordFactory.make(template: t)
        fillRequired(t, &done)
        done.values["A-01"] = FieldValue(v: .string("TDP-007"))
        let rel = "media/photo.jpg"
        let url = recordStore.fileURL(done.id, rel)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let img = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 200)).image { c in
            UIColor.systemTeal.setFill(); c.fill(CGRect(x: 0, y: 0, width: 200, height: 200))
        }
        try? img.jpegData(compressionQuality: 0.8)?.write(to: url)
        var photo = FieldValue(); photo.mediaInV = [Media(file: rel)]
        done.values["P01-04"] = photo
        RecordFactory.complete(&done, template: t)
        done.backupAt = Date()
        var returned = RecordFactory.make(template: t)
        returned.values["A-01"] = FieldValue(v: .string("TDP-021"))
        returned.values["A-03"] = FieldValue(v: .string("5 Nguyễn Huệ"))
        returned.values["B-02"] = FieldValue(v: .number(1500), review: "Đo lại chiều cao khoang")
        returned.returned = true
        let fresh = RecordFactory.make(template: t, now: Date().addingTimeInterval(-3600))
        for r in [done, returned, fresh] { try? recordStore.save(r) }
    }
}
#else
extension AppEnvironment { func applyDebugLaunchArguments() {} }
#endif
