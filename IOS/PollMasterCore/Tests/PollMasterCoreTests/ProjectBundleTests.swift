import Testing
import Foundation
@testable import PollMasterCore

@Suite struct ProjectBundleTests {
    let src = Fixtures.tempDir(), dst = Fixtures.tempDir()
    let out = Fixtures.tempDir()

    /// Project "VGREEN-HN" on the source device with two templates and two sites.
    private func makeSource() throws -> (Project, URL) {
        let t1 = try Fixtures.install(Fixtures.templateData(), root: src)
        let t2 = try Fixtures.install(Fixtures.variantData(id: "BB-TDP-02", version: "1.0"), root: src)
        let p = try ProjectStore(root: src).create(Project(code: "VGREEN-HN", name: "Khảo sát Hà Nội", client: "V-Green",
                                                            color: "#1B6B5A", templates: [TemplateRef(t1), TemplateRef(t2)]))
        let sites = [Site(code: "TDP-001", address: "1 Phố, \"Cổ\"", lat: 21.03, lng: 105.85), Site(code: "TDP-002")]
        try ProjectStore(root: src).sites(p.id).save(sites)
        let file = out.appendingPathComponent(ProjectBundle.fileName(for: p))
        try ProjectBundle.make(p, templates: TemplateStore(root: src), sites: sites).write(to: file)
        return (p, file)
    }

    @Test func zipLayoutAndRoundTrip() throws {
        let (p, file) = try makeSource()
        #expect(file.lastPathComponent == "VGREEN-HN.pmproject")
        #expect(Set(try ZipBuilder.entryNames(file)) == ["project.json", "templates/BB-TDP-01@1.2.json", "templates/BB-TDP-02@1.0.json", "sites.csv"])
        let csv = try ZipBuilder.read(file, path: "sites.csv")
        #expect(csv.starts(with: [0xEF, 0xBB, 0xBF]))
        let bundle = try ProjectBundle.read(from: file)
        #expect(bundle.project.id == p.id && bundle.project.code == "VGREEN-HN" && bundle.project.name == p.name)
        #expect(bundle.project.templates == p.templates && bundle.templates.count == 2)
        #expect(bundle.sites == [Site(code: "TDP-001", address: "1 Phố, \"Cổ\"", lat: 21.03, lng: 105.85), Site(code: "TDP-002")])
    }

    @Test func bundleWithoutSitesOmitsCsv() throws {
        let t = try Fixtures.install(Fixtures.templateData(), root: src)
        let p = Project(code: "NOSITE", name: "x", templates: [TemplateRef(t)])
        let file = out.appendingPathComponent("a.pmproject")
        try ProjectBundle.make(p, templates: TemplateStore(root: src), sites: []).write(to: file)
        #expect(!(try ZipBuilder.entryNames(file)).contains("sites.csv"))
        #expect(try ProjectBundle.read(from: file).sites == nil)
    }

    @Test func makeFailsWhenTemplateMissing() throws {
        let p = Project(code: "X", name: "x", templates: [TemplateRef(id: "NOPE", version: "1")])
        #expect(throws: ProjectBundleError.self) { try ProjectBundle.make(p, templates: TemplateStore(root: src), sites: []) }
    }

    /// The ERP project the bundle is imported into on the receiving device.
    private func target(_ id: String = "erp-1", templates: [TemplateRef] = []) throws -> Project {
        try ProjectStore(root: dst).create(Project(id: id, code: "ERP-1", name: "Dự án ERP", kind: "company", templates: templates))
    }

    @Test func importIntoChosenProject() throws {
        let (p, file) = try makeSource()
        let erp = try target(templates: [TemplateRef(id: "OTHER", version: "1")])
        let importer = ProjectImporter(root: dst)
        let bundle = try ProjectBundle.read(from: file)
        let plan = importer.plan(bundle)
        #expect(plan.existing == nil && plan.conflicts.isEmpty && plan.newTemplates.count == 2)
        let r = try importer.apply(bundle, into: erp.id, overwriteTemplates: false)
        #expect(r.installed.count == 2 && r.droppedRefs.isEmpty && r.siteCount == 2)
        #expect(r.project.id == erp.id && r.project.name == "Dự án ERP" && r.project.code == "ERP-1")     // ERP identity stays
        #expect(TemplateStore(root: dst).exists(id: "BB-TDP-01", version: "1.2") && TemplateStore(root: dst).exists(id: "BB-TDP-02", version: "1.0"))
        #expect(ProjectStore(root: dst).sites(erp.id).load().count == 2)
        #expect(ProjectStore(root: dst).load(erp.id)?.templates == [TemplateRef(id: "OTHER", version: "1")] + p.templates)
        #expect(ProjectStore(root: dst).list().count == 1)
    }

    @Test func sameProjectIsProposedAndVersionsReplaced() throws {
        let (p, file) = try makeSource()
        let erp = try target(p.id, templates: [TemplateRef(id: "BB-TDP-01", version: "1.1")])
        let importer = ProjectImporter(root: dst)
        let rec = Record(templateId: "BB-TDP-01", templateVersion: "1.1", projectId: erp.id)
        try RecordStore(root: dst).save(rec)
        var changed = try ProjectBundle.read(from: file)
        changed.sites = [Site(code: "ONLY")]
        #expect(importer.plan(changed).existing?.id == erp.id)
        let r = try importer.apply(changed, into: erp.id, overwriteTemplates: false)
        #expect(r.project.templates == p.templates)                    // 1.1 replaced by the bundled 1.2, BB-TDP-02 added
        #expect(ProjectStore(root: dst).sites(erp.id).load().map(\.code) == ["ONLY"])
        #expect(RecordStore(root: dst).load(rec.id)?.projectId == erp.id)
        try ProjectStore(root: dst).setArchived(erp.id, true)
        #expect(importer.plan(changed).existing == nil)                 // hidden projects are not proposed
    }

    @Test func importNeedsAnExistingTarget() throws {
        let (_, file) = try makeSource()
        #expect(throws: ProjectError.notFound) { try ProjectImporter(root: dst).apply(ProjectBundle.read(from: file), into: "missing", overwriteTemplates: false) }
    }

    @Test func templateConflictNeedsOverwrite() throws {
        let (_, file) = try makeSource()
        _ = try Fixtures.install(Fixtures.variantData(title: "Bản địa phương"), root: dst)        // same id+version, other content
        let importer = ProjectImporter(root: dst)
        let bundle = try ProjectBundle.read(from: file)
        let plan = importer.plan(bundle)
        #expect(plan.conflicts.map(\.key) == ["BB-TDP-01@1.2"] && plan.newTemplates.map(\.key) == ["BB-TDP-02@1.0"])
        let erp = try target()
        let keep = try importer.apply(bundle, into: erp.id, overwriteTemplates: false)
        #expect(keep.skippedConflicts.count == 1 && TemplateStore(root: dst).load(id: "BB-TDP-01", version: "1.2")?.title == "Bản địa phương")
        let over = try importer.apply(bundle, into: erp.id, overwriteTemplates: true)
        #expect(over.skippedConflicts.isEmpty && TemplateStore(root: dst).load(id: "BB-TDP-01", version: "1.2")?.title != "Bản địa phương")
        #expect(importer.plan(bundle).conflicts.isEmpty)       // identical content is not a conflict
    }

    @Test func invalidBundlesListTheirProblems() throws {
        let junk = out.appendingPathComponent("junk.pmproject")
        try Data("not a zip".utf8).write(to: junk)
        #expect(throws: ProjectBundleError.self) { try ProjectBundle.read(from: junk) }

        var zip = ZipBuilder()
        zip.add("templates/bad.json", data: Data(#"{"id":"X"}"#.utf8))
        zip.add("sites.csv", data: Data("a,b\n1,2\n".utf8))
        let bad = out.appendingPathComponent("bad.pmproject")
        try zip.write(to: bad)
        do { _ = try ProjectBundle.read(from: bad); Issue.record("should throw") }
        catch let e as ProjectBundleError {
            #expect(e.lines.contains { $0.hasPrefix("templates/bad.json") })
            #expect(e.lines.contains { $0.contains("code") })       // sites.csv without a code column
            #expect(e.lines.contains("Thiếu project.json."))
        }
    }

    @Test func missingTemplateRefIsDropped() throws {
        let (_, file) = try makeSource()
        var bundle = try ProjectBundle.read(from: file)
        bundle.project.templates.append(TemplateRef(id: "GHOST", version: "9"))
        let r = try ProjectImporter(root: dst).apply(bundle, into: try target().id, overwriteTemplates: false)
        #expect(r.droppedRefs == [TemplateRef(id: "GHOST", version: "9")] && r.project.templates.count == 2)
    }
}

@Suite struct ProjectNamingTests {
    @Test func exportAndBackupNamesCarryProjectCode() throws {
        let t = try Fixtures.template()
        var r = Fixtures.filledRecord(t)
        r.values["HD-code"] = FieldValue(v: "001/A")
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let stamp = ISODate.format(now, "yyyyMMdd-HHmm")
        #expect(RecordNaming.exportBaseName(t, r, now: now, projectCode: "VGREEN-HN") == "VGREEN-HN_001-A_\(stamp)")
        #expect(RecordNaming.exportBaseName(t, r, now: now) == "001-A_\(stamp)")
        var noCode = r; noCode.values["HD-code"] = nil
        #expect(RecordNaming.exportBaseName(t, noCode, now: now, projectCode: "P") == "P_\(noCode.id)_\(stamp)")
        #expect(RecordExporter.backupFileName(t, r, projectCode: "P") == "P_001-A_\(r.id.prefix(8))_backup.zip")
        #expect(RecordExporter.backupFileName(t, r) == "001-A_\(r.id.prefix(8))_backup.zip")
        #expect(ProjectSummaryExport.fileName(Project(code: "VGREEN-HN", name: "x"), now: now) == "VGREEN-HN_tong-hop_\(stamp).xlsx")
        #expect(ProjectSummaryExport.recordsZipName(Project(code: "VGREEN-HN", name: "x"), now: now) == "VGREEN-HN_phieu_\(stamp).zip")
    }

    @Test func exportCarriesProjectBlockAndLine() throws {
        let t = try Fixtures.template()
        let r = Fixtures.filledRecord(t)
        let p = Project(code: "VGREEN-HN", name: "Khảo sát Hà Nội")
        let json = try #require(try JSONSerialization.jsonObject(with: ExportJSON.build(t, r, project: p)) as? [String: Any])
        let block = try #require(json["project"] as? [String: String])
        #expect(block == ["id": p.id, "code": "VGREEN-HN", "name": "Khảo sát Hà Nội"])
        #expect(try JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) is [String: Any])
        #expect((try JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) as? [String: Any])?["project"] == nil)

        let model = ExportModelBuilder.build(t, r, project: p)
        #expect(model.project?.label == "Khảo sát Hà Nội (VGREEN-HN)" && model.baseName.hasPrefix("VGREEN-HN_"))
        let sheet = try #require(XLSXExport.build(model).first { $0.path == "xl/worksheets/sheet1.xml" })
        let xml = String(decoding: sheet.data, as: UTF8.self)
        #expect(xml.contains("Khảo sát Hà Nội") && xml.contains("VGREEN-HN"))
        let plain = try #require(XLSXExport.build(ExportModelBuilder.build(t, r)).first { $0.path == "xl/worksheets/sheet1.xml" })
        #expect(!String(decoding: plain.data, as: UTF8.self).contains("Dự án"))
    }

    @Test func exporterWritesProjectNamedFiles() throws {
        let t = try Fixtures.template()
        let store = RecordStore(root: Fixtures.tempDir())
        let r = Fixtures.filledRecord(t)
        try store.save(r)
        let p = Project(code: "PRJ", name: "Dự án")
        let urls = try RecordExporter(store: store).export(t, r, formats: [.zip, .xlsx, .pdf, .docx], into: Fixtures.tempDir(), project: p)
        #expect(urls.allSatisfy { $0.lastPathComponent.hasPrefix("PRJ_") })
        let zip = try #require(urls.first { $0.pathExtension == "zip" })
        let export = try JSONSerialization.jsonObject(with: ZipBuilder.read(zip, path: "export.json")) as? [String: Any]
        #expect((export?["project"] as? [String: Any])?["code"] as? String == "PRJ")
        let rec = try JSONSerialization.jsonObject(with: ZipBuilder.read(zip, path: "record.json")) as? [String: Any]
        #expect(rec?["projectId"] == nil)       // the fixture record carries none
        let backup = try RecordExporter(store: store).writeBackup(t, r, into: Fixtures.tempDir(), project: p)
        #expect(backup.lastPathComponent.hasPrefix("PRJ_"))
    }
}
