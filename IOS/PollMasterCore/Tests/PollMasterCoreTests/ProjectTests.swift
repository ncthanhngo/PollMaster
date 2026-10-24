import Testing
import Foundation
@testable import PollMasterCore

@Suite struct ProjectModelTests {
    @Test func encodeDecodeRoundTrip() throws {
        var p = Project(code: "VGREEN-HN", name: "Khảo sát tủ đổi pin Hà Nội", client: "V-Green", description: "Đợt 1",
                        color: "#1B6B5A", templates: [TemplateRef(id: "BB-TDP-01", version: "1.2")])
        p.archived = true
        let data = try JSONCoding.makeEncoder().encode(p)
        let back = try JSONCoding.makeDecoder().decode(Project.self, from: data)
        #expect(back.id == p.id && back.code == p.code && back.name == p.name && back.client == "V-Green")
        #expect(back.templates == p.templates && back.archived && back.color == "#1B6B5A")
        let text = String(decoding: data, as: UTF8.self)
        #expect(text.contains("\"schemaVersion\" : 1"))
    }

    @Test func optionalKeysAreOmittedAndMissingOnesDefault() throws {
        let p = Project(code: "A", name: "A")
        let text = String(decoding: try JSONCoding.makeEncoder().encode(p), as: UTF8.self)
        #expect(!text.contains("client") && !text.contains("description") && !text.contains("color"))
        let min = try JSONCoding.makeDecoder().decode(Project.self, from: Data(#"{"id":"x","code":"AB"}"#.utf8))
        #expect(min.name == "AB" && min.templates.isEmpty && !min.archived && min.schemaVersion == 1)
    }

    @Test func codeFromVietnameseName() {
        #expect(ProjectCode.base(from: "Khảo sát tủ đổi pin Hà Nội") == "KHAO-SAT-TU-DOI")
        #expect(ProjectCode.base(from: "V-Green Hà Nội") == "V-GREEN-HA-NOI")
        #expect(ProjectCode.base(from: "  Đường   Điện (đợt 2)! ") == "DUONG-DIEN-DOT-2")
        #expect(ProjectCode.base(from: "Chung") == "CHUNG")
        #expect(ProjectCode.base(from: "???") == "DU-AN")
    }

    @Test func codeIsTrimmedAtDashBoundary() {
        let c = ProjectCode.base(from: "Khảo sát tủ đổi pin Hà Nội")
        #expect(c.count <= 16 && !c.hasSuffix("-"))
        #expect(ProjectCode.base(from: "Khao sat tu doi pin") == "KHAO-SAT-TU-DOI")      // 17th char is "p": cut before the word
        #expect(ProjectCode.base(from: "abcd efghijklm nop") == "ABCD-EFGHIJKLM")          // 14 chars, next word does not fit
        #expect(ProjectCode.base(from: "aaaaaaaaaaaaaaaa bbb") == "AAAAAAAAAAAAAAAA")      // exactly 16, next is a dash
        #expect(ProjectCode.base(from: "Supercalifragilisticexpialidocious") == "SUPERCALIFRAGILI") // one long word: hard cut
    }

    @Test func uniquenessSuffix() {
        #expect(ProjectCode.unique("CHUNG", taken: []) == "CHUNG")
        #expect(ProjectCode.unique("CHUNG", taken: ["CHUNG"]) == "CHUNG-2")
        #expect(ProjectCode.unique("CHUNG", taken: ["chung", "CHUNG-2"]) == "CHUNG-3")
        let long = ProjectCode.unique("AAAAAAAAAAAAAAAA", taken: ["AAAAAAAAAAAAAAAA"])
        #expect(long == "AAAAAAAAAAAAAA-2" && long.count == 16)
        #expect(ProjectCode.generate(from: "Chung", taken: ["CHUNG"]) == "CHUNG-2")
    }

    @Test func codeValidity() {
        #expect(ProjectCode.isValid("VGREEN-HN") && ProjectCode.isValid("A1"))
        #expect(!ProjectCode.isValid("") && !ProjectCode.isValid("vgreen") && !ProjectCode.isValid("A B") && !ProjectCode.isValid("-A")
                && !ProjectCode.isValid("AAAAAAAAAAAAAAAAA") && !ProjectCode.isValid("Đ1"))
    }
}

@Suite struct ProjectStoreTests {
    let root = Fixtures.tempDir()
    var store: ProjectStore { ProjectStore(root: root) }

    @Test func createLoadListUpdate() throws {
        let a = try store.create(Project(code: "aa-1 ", name: "  Dự án A ", client: " ", now: Date(timeIntervalSince1970: 1000)))
        let b = try store.create(Project(code: "BB", name: "B", now: Date(timeIntervalSince1970: 2000)))
        #expect(a.code == "AA-1" && a.name == "Dự án A" && a.client == nil)
        #expect(store.list().map(\.id) == [b.id, a.id])
        var edit = a; edit.name = "Dự án A2"
        let saved = try store.update(edit, now: Date(timeIntervalSince1970: 3000))
        #expect(store.load(a.id)?.name == "Dự án A2" && saved.updatedAt == Date(timeIntervalSince1970: 3000))
        #expect(store.load("missing") == nil)
    }

    @Test func validation() throws {
        let a = try store.create(Project(code: "AA", name: "A"))
        #expect(throws: ProjectError.emptyName) { try store.create(Project(code: "ZZ", name: "  ")) }
        #expect(throws: ProjectError.invalidCode("A B")) { try store.create(Project(code: "A B", name: "x")) }
        #expect(throws: ProjectError.codeTaken("AA")) { try store.create(Project(code: "aa", name: "x")) }
        var same = a; same.name = "renamed"
        #expect((try? store.update(same)) != nil)      // own code is not a clash
        #expect(throws: ProjectError.notFound) { try store.update(Project(code: "QQ", name: "q")) }
    }

    @Test func archiveAndDelete() throws {
        let a = try store.create(Project(code: "AA", name: "A"))
        try store.setArchived(a.id, true)
        #expect(store.load(a.id)?.archived == true)
        try store.setArchived(a.id, false)
        #expect(store.load(a.id)?.archived == false)
        #expect(throws: ProjectError.hasRecords(3)) { try store.delete(a.id, recordCount: 3) }
        #expect(store.load(a.id) != nil)
        try store.sites(a.id).save([Site(code: "S1")])
        try store.delete(a.id, recordCount: 0)
        #expect(store.load(a.id) == nil && store.list().isEmpty)
    }

    @Test func templatesOfProject() throws {
        let a = try store.create(Project(code: "AA", name: "A"))
        let v12 = TemplateRef(id: "BB-TDP-01", version: "1.2"), v13 = TemplateRef(id: "BB-TDP-01", version: "1.3")
        try store.addTemplate(v12, to: a.id); try store.addTemplate(v12, to: a.id)
        #expect(store.load(a.id)?.templates == [v12])
        try store.useVersion(v13, in: a.id)
        #expect(store.load(a.id)?.templates == [v13])
        try store.removeTemplate(v13, from: a.id)
        #expect(store.load(a.id)?.templates.isEmpty == true)
    }

    @Test func sitesArePerProject() throws {
        let a = try store.create(Project(code: "AA", name: "A")), b = try store.create(Project(code: "BB", name: "B"))
        try store.sites(a.id).save([Site(code: "S1", address: "x", lat: 1, lng: 2)])
        #expect(store.sites(a.id).load().count == 1 && store.sites(b.id).load().isEmpty)
    }

    @Test func newerVersionSuggestion() throws {
        let t12 = try Fixtures.template()
        let t13 = try TemplateLoader.decode(Fixtures.variantData(version: "1.3"))
        let p = Project(code: "AA", name: "A", templates: [TemplateRef(t12)])
        #expect(ProjectTemplates.newerVersions(of: p, library: [t12, t13]) == [TemplateRef(t13)])
        #expect(ProjectTemplates.newerVersions(of: p, library: [t12]).isEmpty)
        #expect(ProjectTemplates.projects(using: t12.id, in: [p]).count == 1)
        #expect(ProjectTemplates.projects(using: "X", in: [p]).isEmpty)
    }

    @Test func moveTargets() {
        let r = Record(templateId: "T1", templateVersion: "1", projectId: "a")
        let a = Project(id: "a", code: "A", name: "A", templates: [TemplateRef(id: "T1", version: "1")])
        let b = Project(id: "b", code: "B", name: "B", templates: [TemplateRef(id: "T1", version: "2")])
        let c = Project(id: "c", code: "C", name: "C", templates: [TemplateRef(id: "T9", version: "1")])
        let d = Project(id: "d", code: "D", name: "D", templates: [TemplateRef(id: "T1", version: "1")], archived: true)
        #expect(ProjectMove.targets(for: r, in: [a, b, c, d], currentId: "a").map(\.id) == ["b"])
        #expect(ProjectMove.targets(for: r, in: [a, c], currentId: "a").isEmpty)
    }

    @Test func stats() {
        var s = ProjectStats()
        var done = Record(templateId: "T", templateVersion: "1", status: .completed); done.updatedAt = Date(timeIntervalSince1970: 50)
        var back = Record(templateId: "T", templateVersion: "1"); back.returned = true; back.updatedAt = Date(timeIntervalSince1970: 90)
        [done, back, Record(templateId: "T", templateVersion: "1", createdAt: Date(timeIntervalSince1970: 10))].forEach { s.add($0) }
        #expect(s.completed == 1 && s.returned == 1 && s.draft == 1 && s.total == 3 && s.latest == Date(timeIntervalSince1970: 90))
    }
}

@Suite struct RecordProjectIdTests {
    @Test func roundTripAndDefaultOnRead() throws {
        var r = Record(templateId: "T", templateVersion: "1", projectId: "p-1")
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: JSONCoding.makeEncoder().encode(r))
        #expect(back.projectId == "p-1")
        r.projectId = nil
        let none = try JSONCoding.makeDecoder().decode(Record.self, from: JSONCoding.makeEncoder().encode(r))
        #expect(none.projectId == nil)      // read as "Chung" by the migration
        let legacy = try JSONCoding.makeDecoder().decode(Record.self, from: Data(#"{"id":"x","projectId":""}"#.utf8))
        #expect(legacy.projectId == nil)
    }

    @Test func factoryAndDuplicateKeepProject() throws {
        let t = try Fixtures.template()
        let r = RecordFactory.make(template: t, projectId: "p-1")
        #expect(r.projectId == "p-1" && RecordFactory.duplicate(r).projectId == "p-1")
    }
}

@Suite struct ProjectMigrationTests {
    let root = Fixtures.tempDir()

    private func seedOldData() throws -> (old: Record, newer: Record) {
        _ = try Fixtures.install(Fixtures.templateData(), root: root)                                       // BB-TDP-01 1.2
        _ = try Fixtures.install(Fixtures.variantData(version: "1.1"), root: root)                          // older version
        _ = try Fixtures.install(Fixtures.variantData(id: "BB-TDP-02", version: "1.0"), root: root)
        let store = RecordStore(root: root)
        let a = Record(templateId: "BB-TDP-01", templateVersion: "1.1"), b = Record(templateId: "BB-TDP-02", templateVersion: "1.0")
        try store.save(a); try store.save(b)
        try SiteStore(root: root).save([Site(code: "S1", address: "a", lat: 1, lng: 2), Site(code: "S2")])
        return (a, b)
    }

    @Test func movesEverythingIntoChung() throws {
        let (a, b) = try seedOldData()
        let result = try ProjectMigration(root: root).run()
        let general = try #require(result.general)
        #expect(general.id == "chung" && general.code == "CHUNG" && general.name == "Chung" && result.recordsAssigned == 2 && result.sitesMoved)
        #expect(Set(general.templates.map(\.key)) == ["BB-TDP-01@1.2", "BB-TDP-02@1.0"])      // newest version of each id
        let store = RecordStore(root: root)
        #expect(store.load(a.id)?.projectId == general.id && store.load(b.id)?.projectId == general.id)
        #expect(ProjectStore(root: root).sites(general.id).load().map(\.code) == ["S1", "S2"])
        #expect(!FileManager.default.fileExists(atPath: SiteStore(root: root).url.path))
        #expect(abs(try #require(store.load(a.id)).updatedAt.timeIntervalSince(a.updatedAt)) < 1)      // not touched (ISO has second precision)
    }

    @Test func randomIdChungFromEarlierRunMovesToFixedId() throws {
        _ = try seedOldData()
        let projects = ProjectStore(root: root)
        let old = try projects.create(Project(code: "CHUNG", name: "Chung", templates: [TemplateRef(id: "BB-TDP-01", version: "1.2")]))
        try projects.sites(old.id).save([Site(code: "S1")])
        let store = RecordStore(root: root)
        let mine = Record(templateId: "BB-TDP-01", templateVersion: "1.2", projectId: old.id), foreign = Record(templateId: "BB-TDP-01", templateVersion: "1.2", projectId: "other")
        try store.save(mine); try store.save(foreign)
        let g = try #require(try ProjectMigration(root: root).run().general)
        #expect(g.id == "chung" && projects.load("chung")?.templates.count == 1 && projects.load(old.id) == nil)
        #expect(projects.sites("chung").load().map(\.code) == ["S1"])
        #expect(store.load(mine.id)?.projectId == "chung" && store.load(foreign.id)?.projectId == "other")
        #expect(store.list().filter { $0.projectId == nil }.isEmpty)
        let again = try ProjectMigration(root: root).run()
        #expect(again.general?.id == "chung" && projects.list().count == 1)
    }

    @Test func idempotent() throws {
        _ = try seedOldData()
        let first = try #require(try ProjectMigration(root: root).run().general)
        let again = try ProjectMigration(root: root).run()
        #expect(again.general?.id == first.id && again.recordsAssigned == 0 && !again.sitesMoved)
        #expect(ProjectStore(root: root).list().count == 1)
    }

    @Test func keepsRecordsOfOtherProjectsAndLaterOrphans() throws {
        _ = try seedOldData()
        let general = try #require(try ProjectMigration(root: root).run().general)
        let other = try ProjectStore(root: root).create(Project(code: "OTHER", name: "Other"))
        let mine = Record(templateId: "BB-TDP-01", templateVersion: "1.2", projectId: other.id)
        let orphan = Record(templateId: "BB-TDP-01", templateVersion: "1.2")
        try RecordStore(root: root).save(mine); try RecordStore(root: root).save(orphan)
        let r = try ProjectMigration(root: root).run()
        #expect(r.recordsAssigned == 1)
        #expect(RecordStore(root: root).load(mine.id)?.projectId == other.id)
        #expect(RecordStore(root: root).load(orphan.id)?.projectId == general.id)
    }

    @Test func freshInstallGetsEmptyChungOnce() throws {
        _ = try Fixtures.install(Fixtures.templateData(), root: root)
        let g = try #require(try ProjectMigration(root: root).run().general)
        #expect(g.templates.count == 1)
        _ = try ProjectMigration(root: root).run()
        #expect(ProjectStore(root: root).list().count == 1)
    }

    @Test func existingChungKeepsItsOwnTemplatesAndSites() throws {
        _ = try seedOldData()
        let g = try #require(try ProjectMigration(root: root).run().general)
        try ProjectStore(root: root).removeTemplate(TemplateRef(id: "BB-TDP-02", version: "1.0"), from: g.id)
        try SiteStore(root: root).save([Site(code: "LEGACY")])      // a stray legacy file must not overwrite the project's list
        _ = try ProjectMigration(root: root).run()
        #expect(ProjectStore(root: root).load(g.id)?.templates.count == 1)
        #expect(ProjectStore(root: root).sites(g.id).load().map(\.code) == ["S1", "S2"])
    }
}

@Suite struct ProjectLookTests {
    @Test func nextColorSkipsColoursInUse() {
        #expect(Project.nextColor(used: []) == Project.palette[0])
        #expect(Project.nextColor(used: [Project.palette[0], Project.palette[1]]) == Project.palette[2])
        // case-insensitive, unknown colours ignored
        #expect(Project.nextColor(used: [Project.palette[0].lowercased(), "#000000"]) == Project.palette[1])
    }

    @Test func nextColorWhenAllUsedPicksLeastUsed() {
        let used = Project.palette + Project.palette.dropFirst(1)   // every colour once, all but the first twice
        #expect(Project.nextColor(used: used) == Project.palette[0])
    }

    @Test func paletteColoursAreDistinct() {
        #expect(Set(Project.palette.map { $0.uppercased() }).count == Project.palette.count)
        #expect(Project.palette.count >= 10)
    }

    @Test func initialsFromName() {
        #expect(Project.initials(of: "Khảo sát tủ đổi pin Hà Nội") == "KS")
        #expect(Project.initials(of: "Chung") == "C")
        #expect(Project.initials(of: "  trạm sạc  ") == "TS")
        #expect(Project.initials(of: "") == "?")
    }

    @Test func avatarFileLifecycle() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ProjectStore(root: root)
        let p = try store.create(Project(code: "AV", name: "Avatar"))
        #expect(store.avatarURL(p.id) == nil)
        try store.setAvatar(Data([0xFF, 0xD8, 0xFF]), for: p.id)
        let url = try #require(store.avatarURL(p.id))
        #expect(try Data(contentsOf: url) == Data([0xFF, 0xD8, 0xFF]))
        try store.removeAvatar(p.id)
        #expect(store.avatarURL(p.id) == nil)
        try store.removeAvatar(p.id)   // removing twice is fine
    }
}
