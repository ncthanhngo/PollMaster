import Foundation

#if DEBUG
import UIKit
import PollMasterCore

extension AppEnvironment {
    /// Simulator verification aids (launch arguments, DEBUG builds only):
    /// `-pmCreate <templateId>` new record and open it; `-pmFill 1` fill its required fields; `-pmSection <n>` push a section;
    /// `-pmRow 1` add a row to the first quick-entry table and open it; `-pmReview 1` complete it and open the checker mode review screen;
    /// `-pmSeed 1` add a few varied records for the list; `-pmTab records|templates|settings`; `-pmAppearance light|dark|system` sets the stored appearance.
    /// `-pmFocus <fieldId>` scroll the pushed section to it; `-pmPartial 1` fill only the header and section A; `-pmReviewDraft 1` open the
    /// review screen of the draft; `-pmFillStep 1` open "Điền lần lượt"; `-pmMap 1` records tab as map; `-pmSites 1` install sample planned sites;
    /// `-pmOneHand 0|1` set one-hand mode; `-pmScrollDown 1` scroll a stopwatch form to its end.
    /// Projects: `-pmSeedProjects 1` two sample projects with sites/records; `-pmProject <code>` current project; `-pmProjectDetail <code>`;
    /// `-pmSwitcher 1` open the project switcher; `-pmImportBundle <path>` preview a .pmproject.
    /// `-pmAccount 1` signed in as a sample ERP user (no network); `-pmLogin 1` show the sign-in screen.
    func applyDebugLaunchArguments() {
        let d = UserDefaults.standard
        if d.bool(forKey: "pmAccount") { account = ERPAccount(name: "Nguyễn Văn A", email: "a@evselab.com", org: "EVSELab") }
        if d.bool(forKey: "pmLogin") { account = nil }
        if let a = d.string(forKey: "pmAppearance").flatMap(AppearanceMode.init) { settings.appearance = a }
        if d.object(forKey: "pmOneHand") != nil { settings.oneHand = d.bool(forKey: "pmOneHand") }
        if d.bool(forKey: "pmMap") { recordsShowMap = true }
        if d.bool(forKey: "pmSeedProjects") { seedProjects() }
        if d.bool(forKey: "pmSites"), let p = currentProject ?? projects.first { try? projectStore.sites(p.id).save(sampleSites); reloadNow() }
        if let code = d.string(forKey: "pmProject") { currentProjectId = projects.first { $0.code == code }?.id }
        if let code = d.string(forKey: "pmProjectDetail"), let p = projects.first(where: { $0.code == code }) { selectedTab = .projects; projectsPath = [ProjectRoute(id: p.id)] }
        if d.bool(forKey: "pmSwitcher") { showProjectSwitcher = true }
        if let path = d.string(forKey: "pmImportBundle") { openIncoming(URL(fileURLWithPath: path)) }
        if let tab = d.string(forKey: "pmTab") { selectedTab = ["templates": .templates, "settings": .settings, "projects": .projects][tab] ?? .records }
        if d.bool(forKey: "pmSeed"), let t = newestTemplates.first(where: { $0.id == "BB-TDP-01" }) { seed(t); reloadNow() }
        guard let tid = d.string(forKey: "pmCreate"), let t = newestTemplates.first(where: { $0.id == tid }) else { return }
        var r = RecordFactory.make(template: t, surveyorName: settings.surveyorName)
        if d.bool(forKey: "pmFill") || d.bool(forKey: "pmReview") { fillRequired(t, &r) }
        if d.bool(forKey: "pmPartial") { fillRequired(t, &r, fields: t.header + (t.sections.first?.fields ?? [])) }
        if d.bool(forKey: "pmRow"), let tb = t.allTables.first(where: { $0.quickEntry && $0.stopwatch != nil }) {
            let id = TableOps.addRow(&r, t, table: tb)
            r.tables[tb.id]?[0].values["kb"] = FieldValue(v: "S04")
            if let e = t.pairedTable(of: tb) { r.tables[e.id]?[0].values["kb"] = FieldValue(v: "S04") }
            try? recordStore.save(r); reloadNow(); open(r.id)
            recordsPath.append(.row(record: r.id, table: tb.id, row: id))
            return
        }
        if d.bool(forKey: "pmReview") {
            RecordFactory.complete(&r, template: t)
            r.values["B-02"]?.review = "Đo lại: ảnh P08 chưa thấy rõ thước."
        }
        do { try recordStore.save(r) } catch { errorMessage = error.localizedDescription; return }
        reloadNow(); open(r.id)
        let focus = d.string(forKey: "pmFocus")
        if d.bool(forKey: "pmReviewDraft") || d.bool(forKey: "pmFillStep") {
            if d.bool(forKey: "pmFillStep") { recordsPath.append(.fill(record: r.id)) } else { recordsPath.append(.review(record: r.id)) }
        } else if d.bool(forKey: "pmReview") {
            editor(for: r.id)?.reviewMode = true
            recordsPath.append(.review(record: r.id))
            if d.object(forKey: "pmSection") != nil { recordsPath.append(.section(record: r.id, index: d.integer(forKey: "pmSection"), focus: nil)) }
        } else if d.object(forKey: "pmSection") != nil {
            recordsPath.append(.section(record: r.id, index: d.integer(forKey: "pmSection"), focus: focus))
        }
    }

    private var sampleSites: [Site] {
        [Site(code: "TDP-007", address: "3 Hai Bà Trưng, Q.1", lat: 10.7769, lng: 106.7009),
         Site(code: "TDP-021", address: "5 Nguyễn Huệ, Q.1", lat: 10.7820, lng: 106.6950),
         Site(code: "TDP-014", address: "12 Lê Lợi, Q.1", lat: 10.7905, lng: 106.7080),
         Site(code: "TDP-033", address: "88 Trần Hưng Đạo, Q.5", lat: 10.7640, lng: 106.6900),
         Site(code: "TDP-040", address: "20 Nguyễn Thị Thập, Q.7", lat: 10.7380, lng: 106.7200)]
    }

    private func fillRequired(_ t: Template, _ r: inout Record, fields: [Field]? = nil) {
        for f in (fields ?? t.allFields) where f.required && !f.isComputed {
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

    /// Three ERP-like projects (two company, one internal) with the sample template, sites and a few records each.
    private func seedProjects() {
        guard let t = newestTemplates.first(where: { $0.id == "BB-TDP-01" }) else { return }
        let seeds = [ERPProject(id: "seed-hn", code: "VGREEN-HN", name: "Khảo sát tủ đổi pin Hà Nội", type: "company", customerName: "V-Green"),
                     ERPProject(id: "seed-dn", code: "EVN-DN", name: "Trạm sạc Đà Nẵng", type: "company", customerName: "EVN"),
                     ERPProject(id: "seed-lab", code: "LAB-01", name: "Thử nghiệm phòng lab", type: "internal")]
        let fresh = seeds.filter { projectStore.load($0.id) == nil }
        _ = try? ERPProjectSync.apply(seeds, to: projectStore)
        for (i, e) in seeds.enumerated() where fresh.contains(e) {
            try? projectStore.addTemplate(TemplateRef(t), to: e.id)
            if i == 0 { try? projectStore.sites(e.id).save(sampleSites) }
            seed(t, projectId: e.id, count: i == 0 ? 3 : 1)
        }
        reloadNow()
        currentProjectId = seeds[0].id
    }

    private func seed(_ t: Template, projectId: String? = nil, count: Int = 3) {
        var done = RecordFactory.make(template: t, projectId: projectId)
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
        var returned = RecordFactory.make(template: t, projectId: projectId)
        returned.values["A-01"] = FieldValue(v: .string("TDP-021"))
        returned.values["A-03"] = FieldValue(v: .string("5 Nguyễn Huệ"))
        returned.values["B-02"] = FieldValue(v: .number(1500), review: "Đo lại chiều cao khoang")
        returned.returned = true
        returned.values["A-04"] = FieldValue(v: GPSValue(lat: 10.7820, lng: 106.6950, accuracy: 6).json)
        var fresh = RecordFactory.make(template: t, now: Date().addingTimeInterval(-3600), projectId: projectId)
        fresh.values["A-04"] = FieldValue(v: GPSValue(lat: 10.7700, lng: 106.7100, accuracy: 9).json)
        for r in [done, returned, fresh].prefix(count) { try? recordStore.save(r) }
    }
}
#else
extension AppEnvironment { func applyDebugLaunchArguments() {} }
#endif
