import Testing
import Foundation
@testable import PollMasterCore

@Suite struct ValidationTests {
    let t: Template
    init() throws { t = try Fixtures.template() }

    private func issues(_ r: Record, _ id: String) -> [ValidationIssue] { Validator.validate(t, r).filter { $0.fieldId == id } }

    @Test func emptyRecordHasRequiredIssuesAndProgress() {
        let r = RecordFactory.make(template: t)
        let all = Validator.validate(t, r)
        #expect(all.contains { $0.fieldId == "HD-code" && $0.kind == .required })
        #expect(all.contains { $0.fieldId == "A-01" && $0.sectionId == "A" })
        let p = Validator.progress(t, r)
        #expect(p.total > 40)
        #expect(p.done < p.total)
    }

    @Test func fullyFilledRecordIsValid() {
        let r = Fixtures.filledRecord(t)
        let all = Validator.validate(t, r)
        #expect(all.isEmpty, "\(all.map { "\($0.fieldId): \($0.message)" })")
        let p = Validator.progress(t, r)
        #expect(p.done == p.total)
    }

    @Test func notMeasurableNeedsReason() {
        var r = Fixtures.filledRecord(t)
        r.values["B-02"] = FieldValue(flag: .notMeasurable)
        #expect(issues(r, "B-02").first?.kind == .reason)
        r.values["B-02"] = FieldValue(flag: .notMeasurable, reason: "Không với tới")
        #expect(issues(r, "B-02").isEmpty)
        // not required but flagged without reason still errors
        r.values["A-09"] = FieldValue(flag: .notMeasurable)
        #expect(issues(r, "A-09").first?.kind == .reason)
    }

    @Test func minMaxRange() {
        var r = Fixtures.filledRecord(t)
        r.values["B-02"] = FieldValue(v: -1)
        #expect(issues(r, "B-02").first?.kind == .range)
        r.values["G-23"] = FieldValue(v: [50, 120, nil])
        #expect(issues(r, "G-23").first?.kind == .range)
        r.values["G-23"] = FieldValue(v: [50, 100, nil])
        #expect(issues(r, "G-23").isEmpty)
    }

    @Test func samplesRequireAtLeastOne() {
        var r = Fixtures.filledRecord(t)
        r.values["G-22"] = FieldValue(v: [nil, nil, nil])
        #expect(issues(r, "G-22").first?.kind == .required)
        r.values["G-22"] = FieldValue(v: [nil, 20, nil])
        #expect(issues(r, "G-22").isEmpty)
    }

    @Test func photoMinCount() {
        var r = Fixtures.filledRecord(t)
        var fv = FieldValue()
        fv.mediaInV = [Media(file: "media/a.jpg")]
        r.values["P01-04"] = fv
        #expect(issues(r, "P01-04").first?.kind == .minCount)
        fv.mediaInV = (1...4).map { Media(file: "media/\($0).jpg") }
        r.values["P01-04"] = fv
        #expect(issues(r, "P01-04").isEmpty)
    }

    @Test func tableMinRowsAndRequiredColumns() throws {
        let json = #"{"schemaVersion":1,"id":"T","version":"1","title":"t","sections":[{"id":"A","title":"A","items":[{"kind":"table","id":"TB","title":"Bang","required":true,"minRows":2,"columns":[{"id":"c","label":"C","type":"text","required":true}]}]}]}"#
        let tpl = try TemplateLoader.decode(Data(json.utf8))
        var r = RecordFactory.make(template: tpl)
        #expect(Validator.validate(tpl, r).first?.kind == .minRows)
        r.tables["TB"] = [Row(values: ["c": FieldValue(v: "x")]), Row()]            // second row empty -> not counted
        #expect(Validator.validate(tpl, r).first?.kind == .minRows)
        r.tables["TB"] = [Row(values: ["c": FieldValue(v: "x")]), Row(values: ["c": FieldValue(v: "y")])]
        #expect(Validator.validate(tpl, r).isEmpty)
        r.tables["TB"]![1].values["c"] = FieldValue(note: "only a note")             // non-empty row missing required column
        #expect(Validator.validate(tpl, r).first?.columnId == "c")
    }

    @Test func hiddenPartsAreNotValidatedOrShown() {
        var r = Fixtures.filledRecord(t)
        let b16 = t.field("B-16")!
        // B-16 (yesNo, required) overhang part only visible when yes
        r.values["B-16"] = FieldValue(v: false, parts: ["overhang": 120])
        #expect(PartVisibility.visibleParts(of: b16, value: r.value("B-16"), lookup: { r.values[$0] }).isEmpty)
        #expect(ValueFormatter.display(b16, r.values["B-16"]) == "Không")
        r.values["B-16"] = FieldValue(v: true, parts: ["overhang": 120])
        #expect(ValueFormatter.display(b16, r.values["B-16"]) == "Có; Độ vươn: 120 mm")
        // composite B-06: required parts all visible & must be filled
        var comp = FieldValue(parts: ["rows": 2, "cols": 3])
        r.values["B-06"] = comp
        #expect(issues(r, "B-06").first?.kind == .required)
        comp.parts?["screen"] = "trên"
        r.values["B-06"] = comp
        #expect(issues(r, "B-06").isEmpty)
        // hidden part value ignored for emptiness
        let c13 = t.field("C-13")!
        #expect(ValueFormatter.display(c13, FieldValue(v: false, parts: ["count": 2])) == "Không")
    }

    @Test func completeAndReopen() {
        var r = Fixtures.filledRecord(t)
        RecordFactory.complete(&r, template: t)
        #expect(r.status == .completed && r.completedAt != nil)
        #expect(SessionTime(r.values["A-02"]?.v)?.end != nil)
        RecordFactory.reopen(&r)
        #expect(r.status == .draft && r.completedAt == nil)
    }

    @Test func flagDisplay() {
        let f = t.field("B-02")!
        #expect(ValueFormatter.display(f, FieldValue(v: 1500, flag: .estimate)) == "~1500")
        #expect(ValueFormatter.display(f, FieldValue(flag: .notMeasurable, reason: "cao")) == "KĐĐ (cao)")
    }
}
