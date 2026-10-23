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

    @Test func guideExampleDecodes() throws {
        let url = Fixtures.sharedDir.appendingPathComponent("huong-dan-tao-mau-bang-ai.md")
        let md = try String(contentsOf: url, encoding: .utf8)
        let section = try #require(md.range(of: "## 7."))
        let json = try #require(TemplateText.extractJSON(String(md[section.upperBound...])))
        _ = try TemplateLoader.decode(Data(json.utf8))
    }
}
