import Testing
import Foundation
@testable import PollMasterCore

@Suite struct TemplateTextTests {
    @Test func plainJSON() {
        #expect(TemplateText.extractJSON("  {\"a\": {\"b\": 1}}\n") == "{\"a\": {\"b\": 1}}")
    }

    @Test func fencedWithProse() {
        let s = "Đây là mẫu:\n```json\n{\"a\": 1}\n```\nGiả định: {không phải JSON}"
        #expect(TemplateText.extractJSON(s) == "{\"a\": 1}")
    }

    @Test func firstJSONFenceWins() {
        let s = "```text\n{\"x\":0}\n```\n```json\n{\"a\": 1}\n```\n```json\n{\"b\": 2}\n```"
        #expect(TemplateText.extractJSON(s) == "{\"a\": 1}")
    }

    @Test func noJSON() {
        #expect(TemplateText.extractJSON("xin chào") == nil)
        #expect(TemplateText.extractJSON("```json\n```") == nil)
    }

    @Test func fixRequestListsErrors() {
        let lines = TemplateText.errorLines(TemplateError.invalid(["a", "b"]))
        #expect(TemplateText.fixRequest(lines).hasSuffix("hãy sửa và trả lại toàn bộ JSON:\n- a\n- b"))
    }

    @Test func editChangesOnlyNameShortNameAndColor() throws {
        let data = try Fixtures.templateData()
        var before = try TemplateLoader.decode(data)
        let after = try TemplateLoader.decode(TemplateText.edited(data, title: "  Tủ đổi pin Hà Nội ", shortName: " TĐP ", color: "#B8312F"))
        #expect(after.title == "Tủ đổi pin Hà Nội" && after.badge == "TĐP" && after.badgeColor == "#B8312F")
        before.title = after.title; before.shortName = after.shortName; before.color = after.color
        #expect(after == before)
        let cleared = try TemplateLoader.decode(TemplateText.edited(TemplateText.edited(data, title: "X", shortName: "S", color: "#000000"), title: "X", shortName: "", color: nil))
        #expect(cleared.shortName == nil && cleared.color == nil && cleared.badge == cleared.id && Project.palette.contains(cleared.badgeColor))
    }

    @Test func editRejectsBlankTitleAndBadColor() throws {
        #expect(throws: TemplateError.self) { try TemplateText.edited(Fixtures.templateData(), title: "   ", shortName: nil, color: nil) }
        #expect(throws: TemplateError.self) { try TemplateText.edited(Fixtures.templateData(), title: "A", shortName: nil, color: "red") }
    }

    @Test func defaultBadgeColorMatchesAndroid() throws {
        // same hash rule as Template.badgeColor on Android: h = (h * 31 + byte) % 1000003: "BB-TDP-01" -> 9, "KS-VF-SWAP-01" -> 7
        #expect(try Fixtures.template().badgeColor == Project.palette[9])
    }

    @Test func guideExampleDecodes() throws {
        let url = Fixtures.sharedDir.appendingPathComponent("huong-dan-tao-mau-bang-ai.md")
        let md = try String(contentsOf: url, encoding: .utf8)
        let section = try #require(md.range(of: "## 7."))
        let json = try #require(TemplateText.extractJSON(String(md[section.upperBound...])))
        _ = try TemplateLoader.decode(Data(json.utf8))
    }
}
