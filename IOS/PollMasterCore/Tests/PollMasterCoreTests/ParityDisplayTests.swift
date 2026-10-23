import Testing
import Foundation
@testable import PollMasterCore

/// Rules of docs/template-schema.md §6.2, §6.3, §6.5, §6.6.
@Suite struct ParityDisplayTests {
    let t: Template
    init() throws { t = try Fixtures.template() }

    @Test func roundIsDecimalExact() {
        #expect(FormulaMath.round(1.0 / 3, 2) == 0.33)
        #expect(FormulaMath.round(0.1 + 0.2, 2) == 0.3)
        #expect(ValueFormatter.number(0.1 + 0.2) == "0.30000000000000004")
    }

    @Test func whitespaceIsEmpty() {
        var r = Fixtures.filledRecord(t)
        r.values["B-02"] = FieldValue(v: "   ")
        #expect(Validator.validate(t, r).contains { $0.fieldId == "B-02" && $0.message == "Bắt buộc nhập" })
        r.values["B-02"] = FieldValue(flag: .notMeasurable, reason: "  ")
        #expect(Validator.validate(t, r).contains { $0.fieldId == "B-02" && $0.message == "Cần nhập lý do KĐĐ" })
    }

    @Test func progressCountsOnlyIssueFreeItems() {
        var r = Fixtures.filledRecord(t)
        let full = Validator.progress(t, r)
        r.values["B-02"] = FieldValue(flag: .notMeasurable)
        #expect(Validator.progress(t, r).done == full.done - 1)
        r.values["B-02"] = FieldValue(v: -5)       // min 0 -> issue
        #expect(Validator.progress(t, r).done == full.done - 1)
    }

    @Test func containsOnNonArrayIsEquality() {
        let part = Part(id: "p", label: "p", type: .text, unit: nil, options: nil, showWhen: ShowWhen(field: "$self", equals: nil, contains: "a"))
        let f = Field(id: "f", label: "f", type: .composite)
        #expect(PartVisibility.isVisible(part, in: f, value: FieldValue(v: "a"), lookup: { _ in nil }))
        #expect(!PartVisibility.isVisible(part, in: f, value: FieldValue(v: "ab"), lookup: { _ in nil }))
        #expect(PartVisibility.isVisible(part, in: f, value: FieldValue(v: ["a", "b"]), lookup: { _ in nil }))
    }

    @Test func displayStrings() throws {
        var rating = Field(id: "r", label: "r", type: .rating); rating.max = 5
        #expect(ValueFormatter.display(rating, FieldValue(v: 4)) == "4/5")
        var num = Field(id: "n", label: "n", type: .number)
        #expect(ValueFormatter.display(num, FieldValue(v: 1.2345)) == "1.2345")
        num.decimals = 2
        #expect(ValueFormatter.display(num, FieldValue(v: 1.2345)) == "1.23")
        let integer = Field(id: "i", label: "i", type: .integer)
        #expect(ValueFormatter.display(integer, FieldValue(v: 7)) == "7")
        let st = Field(id: "s", label: "s", type: .sessionTime)
        let a = try #require(ISODate.parse("2026-10-23T10:00:00+07:00")), b = try #require(ISODate.parse("2026-10-24T11:30:00+07:00"))
        let tz = ISODate.format(a, "dd/MM/yyyy HH:mm"), tz2 = ISODate.format(b, "dd/MM/yyyy HH:mm")
        #expect(ValueFormatter.display(st, FieldValue(v: SessionTime(start: a, end: b).json)) == "\(tz) – \(tz2)")
        #expect(ValueFormatter.display(st, FieldValue(v: SessionTime(start: a).json)) == "\(tz) – …")
        #expect(ValueFormatter.mmss(3725) == "1:02:05" && ValueFormatter.mmss(65) == "01:05")
        let sig = Field(id: "g", label: "g", type: .signature)
        #expect(ValueFormatter.display(sig, FieldValue(v: .object(["file": "media/sig_a.png"]))) == "sig_a.png")
        var comp = Field(id: "c", label: "c", type: .composite)
        comp.parts = [Part(id: "x", label: "Vươn", type: .number, unit: "mm", options: nil, showWhen: nil)]
        var yn = comp; yn.type = .yesNo
        #expect(ValueFormatter.display(yn, FieldValue(v: true, parts: ["x": 50])) == "Có; Vươn: 50 mm")
        #expect(ValueFormatter.display(comp, FieldValue(parts: ["x": 50])) == "Vươn: 50 mm")
    }

    @Test func recordTitleTrimsSeparators() throws {
        var t = t
        t.recordTitle = "{HD-code} – {A-01} /"
        var r = Record(templateId: t.id, templateVersion: t.version)
        r.values["A-01"] = FieldValue(v: "K1")
        #expect(RecordNaming.title(t, r) == "K1")
        r.values["A-01"] = nil
        #expect(RecordNaming.title(t, r) == t.title)
    }

    @Test func xlsxNumbersRoundedAndSheetNames() throws {
        var used = Set<String>()
        #expect(XLSXExport.sheetName("a[b]:c", used: &used) == "a_b__c")
        #expect(XLSXExport.sheetName("A_B__C", used: &used) == "A_B__C (2)")
        #expect(XLSXExport.sheetName(String(repeating: "x", count: 40), used: &used).count == 31)
        var f = Field(id: "n", label: "n", type: .number); f.decimals = 1
        #expect(ExportModelBuilder.numericValue(f, FieldValue(v: 2.26)) == 2.3)
        var rt = Field(id: "r", label: "r", type: .rating); rt.max = 5
        #expect(ExportModelBuilder.numericValue(rt, FieldValue(v: 4)) == nil)
    }

    @Test func exportJSONEmptyValueIsNullAndTimestampsRaw() throws {
        var r = Record(templateId: t.id, templateVersion: t.version)
        let json = #"{"id":"r9","createdAt":"2026-10-23T10:00+07:00","updatedAt":"2026-10-23T10:00+07:00"}"#
        r = try JSONCoding.makeDecoder().decode(Record.self, from: Data(json.utf8))
        let out = try JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) as! [String: Any]
        let rec = out["record"] as! [String: Any]
        #expect(rec["createdAt"] as? String == "2026-10-23T10:00+07:00")
        let fields = out["fields"] as! [String: Any]
        #expect((fields["P05"] as! [String: Any])["value"] is NSNull)
        #expect((fields["B-02"] as! [String: Any])["value"] is NSNull)
    }

    @Test func importRejectsBadFormulas() {
        func problems(_ formula: String) -> [String] {
            let json = #"{"schemaVersion":1,"id":"X","version":"1","title":"t","sections":[{"id":"A","title":"a","items":[{"id":"n","label":"n","type":"number"},{"kind":"table","id":"T","columns":[{"id":"c","type":"number"}]},{"id":"f","label":"l","type":"computed","formula":"\#(formula)"}]}]}"#
            do { _ = try TemplateLoader.decode(Data(json.utf8)); return [] }
            catch TemplateError.invalid(let l) { return l }
            catch { return ["other \(error)"] }
        }
        #expect(problems("sum(T, {@c})").isEmpty)
        #expect(problems("{n} + 1").isEmpty)
        for bad in ["1 +", "foo({n})", "round()", "if({n}, 1)", "{@c} + 1", "sum(Z, {@c})", "sum(T, {@c}, true, 1)"] {
            let p = problems(bad)
            #expect(p.contains { $0.contains("sections[0].items[2].formula") }, "\(bad) -> \(p)")
        }
    }

    @Test func importRejectsChoiceWithoutOptionsAndBadParts() {
        func decode(_ item: String) -> String? {
            let json = #"{"schemaVersion":1,"id":"X","version":"1","title":"t","sections":[{"id":"A","title":"a","items":[\#(item)]}]}"#
            do { _ = try TemplateLoader.decode(Data(json.utf8)); return nil }
            catch TemplateError.invalid(let l) { return l.joined(separator: "\n") }
            catch TemplateError.parse(let m) { return m }
            catch { return "\(error)" }
        }
        #expect(decode(#"{"id":"c","label":"c","type":"singleChoice"}"#)?.contains("sections[0].items[0].options") == true)
        #expect(decode(#"{"id":"c","label":"c","type":"multiChoice","options":[]}"#)?.contains("options") == true)
        #expect(decode(#"{"id":"c","label":"c","type":"composite","parts":[{"id":"p"}]}"#)?.contains("label") == true)
        #expect(decode(#"{"id":"c","label":"c","type":"composite","parts":[{"id":"p","label":"P"}]}"#) == nil)
        #expect(decode(#"{"kind":"table","id":"T","columns":[{"id":"c","type":"text"}],"rows":[{"id":"r1"}]}"#) == nil)
    }
}

extension ParityDisplayTests {
    @Test func tableProgressIncludesRowIssues() {
        var t = t
        let si = t.sections.firstIndex { $0.tables.contains { $0.id == "E1" } }!
        let ii = t.sections[si].items.firstIndex { $0.id == "E1" }!
        guard case .table(var tb) = t.sections[si].items[ii] else { return }
        tb.required = true
        let ci = tb.columns.firstIndex { $0.id == "mode" }!
        tb.columns[ci].required = true
        t.sections[si].items[ii] = .table(tb)
        var r = Fixtures.filledRecord(t)
        r.tables["E1"] = [Row(values: ["mode": FieldValue(v: "O")])]
        let ok = Validator.progress(t, r)
        r.tables["E1"] = [Row(values: ["mode": FieldValue(v: "O")]), Row(values: ["hand": FieldValue(v: "2T")])]   // 2nd row misses "mode"
        #expect(Validator.validate(t, r).contains { $0.fieldId == "E1" })
        #expect(Validator.progress(t, r).done == ok.done - 1)
    }

    @Test func missingRecordTitleFallsBackToTemplateTitle() {
        var t = t
        t.recordTitle = nil
        var r = Record(templateId: t.id, templateVersion: t.version)
        r.values["HD-code"] = FieldValue(v: "X")
        #expect(RecordNaming.title(t, r) == t.title)
    }
}
