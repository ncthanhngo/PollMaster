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

    @Test func archive() throws {
        let a = try store.create(Project(code: "AA", name: "A"))
        try store.setArchived(a.id, true)
        #expect(store.load(a.id)?.archived == true)
        try store.setArchived(a.id, false)
        #expect(store.load(a.id)?.archived == false)
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
        #expect(none.projectId == nil)
        let legacy = try JSONCoding.makeDecoder().decode(Record.self, from: Data(#"{"id":"x","projectId":""}"#.utf8))
        #expect(legacy.projectId == nil)
    }

    @Test func factoryAndDuplicateKeepProject() throws {
        let t = try Fixtures.template()
        let r = RecordFactory.make(template: t, projectId: "p-1")
        #expect(r.projectId == "p-1" && RecordFactory.duplicate(r).projectId == "p-1")
    }
}

@Suite struct ERPProjectSyncTests {
    let root = Fixtures.tempDir()
    var store: ProjectStore { ProjectStore(root: root) }

    @Test func decodesListRow() throws {
        let json = #"{"id":"p1","code":"EVS-01","name":"Trạm sạc Đà Nẵng","short_name":" ","project_type":"internal","customer_name":null,"#
            + #""customer_logo_url":"/uploads/a.png","created_at":"2026-05-01T08:00:00.123+07:00","member_count":3}"#
        let e = try JSONDecoder().decode(ERPProject.self, from: Data(json.utf8))
        #expect(e.id == "p1" && e.code == "EVS-01" && e.type == "internal" && e.customerName == nil && e.logo == "/uploads/a.png")
        #expect(e.displayName == "Trạm sạc Đà Nẵng" && e.createdAt != nil)
        let page = try JSONDecoder().decode(ERPEnvelope<ERPPage<ERPProject>>.self, from: Data(#"{"data":{"items":[\#(json)],"total":1}}"#.utf8))
        #expect(page.data?.items.count == 1 && page.data?.total == 1)
        #expect(ERPProject(id: "x", code: "C", name: "Long", shortName: "Ngắn").displayName == "Ngắn")
    }

    @Test func createsUpdatesAndHides() throws {
        let t = TemplateRef(id: "BB-TDP-01", version: "1.2")
        let a = ERPProject(id: "a", code: "evs a/1", name: "Dự án A", type: "company", customerName: "V-Green", logo: "/uploads/a.png")
        let b = ERPProject(id: "b", code: "B", name: "Nội bộ B", type: "internal")
        let me = ERPProject(id: "c", code: "C", name: "Cá nhân", type: "personal")
        let changes = try ERPProjectSync.apply([a, b, me], to: store)
        #expect(changes == [ERPProjectSync.LogoChange(projectId: "a", url: "/uploads/a.png")])
        let pa = try #require(store.load("a")), pb = try #require(store.load("b"))
        #expect(pa.code == "EVS-A-1" && pa.name == "Dự án A" && pa.client == "V-Green" && pa.kind == "company" && pa.logo == "/uploads/a.png")
        #expect(pb.kind == "internal" && pa.color != pb.color && store.load("c") == nil)

        try store.addTemplate(t, to: "a")
        try store.sites("a").save([Site(code: "S1")])
        var renamed = a; renamed.name = "Dự án A mới"; renamed.logo = nil
        let again = try ERPProjectSync.apply([renamed], to: store)
        #expect(again == [ERPProjectSync.LogoChange(projectId: "a", url: nil)])
        let pa2 = try #require(store.load("a"))
        #expect(pa2.name == "Dự án A mới" && pa2.templates == [t] && pa2.color == pa.color && !pa2.archived)    // device fields kept
        #expect(store.sites("a").load().count == 1 && store.load("b")?.archived == true)                        // dropped from ERP: hidden

        _ = try ERPProjectSync.apply([b], to: store)
        #expect(store.load("b")?.archived == false && store.load("a")?.archived == true)
    }

    @Test func phoenixStartsWithTheLibrary() throws {
        let lib = [TemplateRef(id: "BB-TDP-01", version: "1.2")]
        let phx = ERPProject(id: "phx", code: "PHN-01", name: "Dự án Phoenix", type: "company"), other = ERPProject(id: "o", code: "O", name: "Khác", type: "company")
        _ = try ERPProjectSync.apply([phx, other], to: store, library: lib)
        #expect(store.load("phx")?.templates == lib && store.load("o")?.templates.isEmpty == true)
        try store.removeTemplate(lib[0], from: "phx")
        _ = try ERPProjectSync.apply([phx, other], to: store, library: lib)
        #expect(store.load("phx")?.templates.isEmpty == true)      // only when it first arrives
    }

    @Test func missingAvatarFileIsFetchedAgain() throws {
        let a = ERPProject(id: "a", code: "A", name: "A", type: "company", logo: "https://x/a.png")
        _ = try ERPProjectSync.apply([a], to: store)
        #expect(try ERPProjectSync.apply([a], to: store).count == 1)
        try store.setAvatar(Data([1]), for: "a")
        #expect(try ERPProjectSync.apply([a], to: store).isEmpty)
    }

    @Test func fileURLs() {
        let host = URL(string: "https://api.evselab.com")!
        #expect(ERPProjectSync.fileURL("/uploads/a.png", host: host)?.absoluteString == "https://api.evselab.com/uploads/a.png")
        #expect(ERPProjectSync.fileURL("https://cdn/x.png", host: host)?.absoluteString == "https://cdn/x.png")
    }

    @Test func resetKeepsTemplates() throws {
        _ = try Fixtures.install(Fixtures.templateData(), root: root)
        _ = try store.create(Project(code: "OLD", name: "Old"))
        try RecordStore(root: root).save(Record(templateId: "BB-TDP-01", templateVersion: "1.2", projectId: "x"))
        try ERPProjectSync.resetLocalData(root: root)
        #expect(store.list().isEmpty && RecordStore(root: root).list().isEmpty && TemplateStore(root: root).list().count == 1)
        try ERPProjectSync.resetLocalData(root: root)      // nothing left: no error
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
