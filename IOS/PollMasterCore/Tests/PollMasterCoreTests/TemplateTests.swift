import Testing
import Foundation
@testable import PollMasterCore

@Suite struct TemplateTests {
    @Test func decodesBundledTemplate() throws {
        let t = try Fixtures.template()
        #expect(t.id == "BB-TDP-01")
        #expect(t.sections.count == 10)
        #expect(t.signatures.map(\.id) == ["surveyor", "checker"])
        #expect(t.recordCodeField?.id == "HD-code")
        #expect(t.allTables.map(\.id) == ["E1", "E2", "F1", "H1"])
        #expect(t.table("E2")?.isFixed == true)
        #expect(t.table("F1")?.stopwatch?.columns.count == 8)
    }

    @Test func idsAreUniqueAndFormulasParse() throws {
        let t = try Fixtures.template()
        var ids = t.header.map(\.id)
        for s in t.sections { ids += s.items.map(\.id) }
        #expect(Set(ids).count == ids.count)
        #expect(TemplateLoader.check(t).isEmpty)
        #expect(t.allFields.filter(\.isComputed).count == 9)
    }

    @Test func reencodeRoundTrip() throws {
        let t = try Fixtures.template()
        let data = try JSONCoding.makeEncoder().encode(t)
        #expect(try JSONCoding.makeDecoder().decode(Template.self, from: data) == t)
    }

    @Test func parseErrorReportsPath() throws {
        let bad = Data(#"{"schemaVersion":1,"id":"X","version":"1","title":"t","sections":[{"id":"A","title":"a","items":[{"id":"f","label":"l","type":"bogus"}]}]}"#.utf8)
        do { _ = try TemplateLoader.decode(bad); Issue.record("expected failure") }
        catch let TemplateError.parse(msg) { #expect(msg.contains("sections[0].items[0].type")) }
    }

    @Test func rejectsDuplicateIdsAndBadFormula() throws {
        let dup = Data(#"{"schemaVersion":1,"id":"X","version":"1","title":"t","sections":[{"id":"A","title":"a","items":[{"id":"f","label":"l","type":"text"},{"id":"f","label":"l","type":"computed","formula":"1 +"}]}]}"#.utf8)
        do { _ = try TemplateLoader.decode(dup); Issue.record("expected failure") }
        catch let TemplateError.invalid(list) { #expect(list.contains { $0.contains("Trùng") }); #expect(list.contains { $0.contains("công thức") }) }
    }

    @Test func storeInstallListDelete() throws {
        let dir = Fixtures.tempDir()
        let store = TemplateStore(root: dir)
        let t = try store.install(try Fixtures.templateData())
        #expect(store.exists(id: t.id, version: t.version))
        #expect(store.list().count == 1)
        #expect(store.load(id: t.id, version: t.version) == t)
        try store.delete(t)
        #expect(store.list().isEmpty)
    }
}
