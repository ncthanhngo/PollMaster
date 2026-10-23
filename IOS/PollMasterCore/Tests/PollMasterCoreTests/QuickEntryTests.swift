import Testing
import Foundation
@testable import PollMasterCore

@Suite struct QuickEntryTests {
    // MARK: decoding

    @Test func decodesQuickEntryKeys() throws {
        let t = try Fixtures.template()
        #expect(t.field("HD-code")?.short == true && t.field("A-07")?.short == true)
        #expect(t.field("A-01")?.short == false)
        let e1 = try #require(t.table("E1"))
        let slot = try #require(e1.column("slotRow"))
        #expect(slot.type == .integer)
        #expect(slot.chipsRange == ChipsRange(min: 1, maxRef: "B-06.rows", fallbackMax: 10))
        #expect(e1.column("attempts")?.stepper == true)
        #expect(t.table("E2")?.inline == true && e1.inline == false)
        let cues = try #require(t.table("F1")?.stopwatch)
        #expect(cues.cue(for: "T1") == StopwatchCue(text: "Đo nhiệt pin cũ vừa tháo ngay", field: "G-33"))
        #expect(cues.cue(for: "T6")?.field == "G-32")
        #expect(cues.cue(for: "T2") == nil)
        let g2 = t.sections.first { $0.id == "G" }?.items.compactMap { item -> Heading? in if case .heading(let h) = item, h.id == "G2" { return h } else { return nil } }.first
        #expect(g2?.roundEntry == RoundEntry(samples: 3, timeField: "G-20"))
    }

    @Test func newKeysRoundTripAndStayOptional() throws {
        let json = #"{"id":"x","type":"integer","stepper":true,"chipsRange":{"maxRef":"a"}}"#
        let f = try JSONDecoder().decode(Field.self, from: Data(json.utf8))
        #expect(f.chipsRange == ChipsRange(min: 1, maxRef: "a", fallbackMax: 10))
        let back = try JSONDecoder().decode(Field.self, from: JSONEncoder().encode(f))
        #expect(back == f)
        let plain = try JSONDecoder().decode(Field.self, from: Data(#"{"id":"y","type":"text"}"#.utf8))
        #expect(!plain.short && !plain.stepper && plain.chipsRange == nil)
    }

    // MARK: chipsRange

    @Test func chipsRangeMaxResolution() {
        let c = ChipsRange(min: 1, maxRef: "B-06.rows", fallbackMax: 10)
        func lookup(_ rows: JSONValue?) -> (String) -> FieldValue? {
            { $0 == "B-06" ? FieldValue(parts: rows.map { ["rows": $0] }) : nil }
        }
        #expect(c.resolvedMax(lookup(4)) == 4)
        #expect(c.values(lookup(3)) == [1, 2, 3])
        #expect(c.resolvedMax(lookup(nil)) == 10)          // part empty
        #expect(c.resolvedMax { _ in nil } == 10)           // field missing
        #expect(c.resolvedMax(lookup(0)) == 10)             // not > 0
        #expect(c.resolvedMax(lookup(2.5)) == 10)           // not an integer
        #expect(c.resolvedMax(lookup("x")) == 10)
        #expect(ChipsRange(maxRef: nil, fallbackMax: 6).resolvedMax { _ in nil } == 6)
        #expect(ChipsRange(min: 1, maxRef: "N").resolvedMax { _ in FieldValue(v: 5) } == 5)   // plain field ref
        #expect(ChipsRange(min: 5, maxRef: nil, fallbackMax: 3).values { _ in nil }.isEmpty)
    }

    // MARK: record code

    private func codeTemplate() throws -> Template { try Fixtures.template() }
    private func record(_ t: Template, code: String?, templateId: String? = nil) -> Record {
        var r = Record(templateId: templateId ?? t.id, templateVersion: t.version)
        if let code { r.values["HD-code"] = FieldValue(v: .string(code)) }
        return r
    }

    @Test func recordCodeProposal() throws {
        let t = try codeTemplate()
        #expect(RecordCodeProposal.propose(for: t, among: []) == "001")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "001")]) == "002")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "009"), record(t, code: "003")]) == "010")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "099")]) == "100")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "007/A")]) == "008")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "ABC"), record(t, code: nil), record(t, code: "")]) == "001")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "ABC"), record(t, code: "004")]) == "005")
        #expect(RecordCodeProposal.propose(for: t, among: [record(t, code: "12")]) == "13")
    }

    @Test func recordCodeProposalPerTemplateId() throws {
        let t = try codeTemplate()
        let other = record(t, code: "050", templateId: "OTHER")
        let sameIdOldVersion = { var r = record(t, code: "002"); r.templateVersion = "1.0"; return r }()
        #expect(RecordCodeProposal.propose(for: t, among: [other, sameIdOldVersion]) == "003")
    }

    // MARK: roundEntry

    @Test func roundGroupMembership() throws {
        let t = try Fixtures.template()
        let items = try #require(t.sections.first { $0.id == "G" }).items
        let g2 = RoundEntryRules.groupFieldIds(in: items, headingId: "G2")
        #expect(g2.first == "G-20" && g2.last == "G-36" && g2.contains("G-24"))
        #expect(!g2.contains("G-01") && RoundEntryRules.groupFieldIds(in: items, headingId: "G1").isEmpty)  // G1 has no samples fields
        #expect(RoundEntryRules.groupFieldIds(in: items, headingId: "nope").isEmpty)
        #expect(RoundEntryRules.heading(owning: "G-22", in: items)?.id == "G2")
        #expect(RoundEntryRules.heading(owning: "G-05", in: items) == nil)   // under G1 without roundEntry
    }

    @Test func roundAutoTimeFill() {
        let entry = RoundEntry(samples: 3, timeField: "G-20")
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let expected = ISODate.format(now, "HH:mm")
        func fill(round: Int, edited: String = "G-22", has: Bool = true, time: FieldValue?) -> String? {
            RoundEntryRules.timeToFill(entry, round: round, editedFieldId: edited, editedHasValue: has, time: time, now: now)
        }
        #expect(fill(round: 0, time: nil) == expected)
        var t = FieldValue(); t.setSample(0, count: 3, to: "10:00")
        #expect(fill(round: 0, time: t) == nil)                       // already has a time
        #expect(fill(round: 1, time: t) == expected)                  // other round still empty
        #expect(fill(round: 0, edited: "G-20", time: nil) == nil)     // editing the time itself
        #expect(fill(round: 0, has: false, time: nil) == nil)         // value cleared
        #expect(fill(round: 3, time: nil) == nil)                     // out of range
        #expect(RoundEntryRules.timeToFill(RoundEntry(samples: 3, timeField: nil), round: 0, editedFieldId: "G-22", editedHasValue: true, time: nil) == nil)
    }

    @Test func firstEmptyRound() {
        var v = FieldValue()
        #expect(RoundEntryRules.firstEmptyRound(samples: 3, value: nil) == 0)
        v.setSample(0, count: 3, to: 20)
        #expect(RoundEntryRules.firstEmptyRound(samples: 3, value: v) == 1)
        v.setSample(1, count: 3, to: 21); v.setSample(2, count: 3, to: 22)
        #expect(RoundEntryRules.firstEmptyRound(samples: 3, value: v) == 2)
    }

    // MARK: outline stats

    @Test func outlineStats() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        let a = OutlineStats.compute(t, r, sectionId: "A")
        #expect(a.required.total == 8 && a.required.done == 1)           // sessionTime auto-started
        #expect(a.line.hasPrefix("1/8"))
        #expect(OutlineStats.compute(t, r, sectionId: "E").rowCounts.isEmpty)   // E2 fixed, E1 empty
        r.tables["E1"] = [Row(values: ["kb": FieldValue(v: "S01")]), Row(values: ["kb": FieldValue(v: "S02")]), Row()]
        let e = OutlineStats.compute(t, r, sectionId: "E")
        #expect(e.rowCounts.count == 1 && e.rowCounts[0].count == 2 && e.rowCounts[0].label == "lượt")
        #expect(e.line.contains("2 lượt"))
        var m = FieldValue(); m.mediaInV = [Media(file: "media/a.jpg")]
        r.values["P05"] = m
        let i = OutlineStats.compute(t, r, sectionId: "I")
        #expect(i.mediaTotal == 15 && i.mediaFilled == 1 && i.line.hasSuffix("1/15"))
        #expect(!i.missingPhoto)
    }

    @Test func outlineMissingPhotoFlag() throws {
        var t = try Fixtures.template()
        let s = try #require(t.sections.firstIndex { $0.id == "I" })
        let k = try #require(t.sections[s].items.firstIndex { $0.id == "P05" })
        guard case .field(var f) = t.sections[s].items[k] else { Issue.record("not a field"); return }
        f.minCount = 2
        t.sections[s].items[k] = .field(f)
        var r = RecordFactory.make(template: t)
        #expect(OutlineStats.compute(t, r, sectionId: "I").missingPhoto == false)   // untouched, optional
        f.required = true
        t.sections[s].items[k] = .field(f)
        #expect(OutlineStats.compute(t, r, sectionId: "I").missingPhoto)
        var m = FieldValue(); m.mediaInV = [Media(file: "media/a.jpg"), Media(file: "media/b.jpg")]
        r.values["P05"] = m
        #expect(OutlineStats.compute(t, r, sectionId: "I").missingPhoto == false)
    }

    // MARK: fill queue

    @Test func fillQueueOrdering() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        let steps = FillQueue.steps(t, r)
        #expect(steps.first?.sectionId == Validator.headerSectionId)
        #expect(Set(steps.map(\.fieldId)).count == steps.count)             // one step per field
        let order = t.header.map(\.id) + t.sections.flatMap { $0.items.map(\.id) }
        let positions = steps.compactMap { order.firstIndex(of: $0.fieldId) }
        #expect(positions == positions.sorted() && positions.count == steps.count)
        // next: skips what is filled and what is behind
        let a01 = try #require(steps.first { $0.fieldId == "A-01" })
        r.values["A-01"] = FieldValue(v: "TDP-1")
        let nxt = try #require(FillQueue.next(t, r, after: a01.fieldId))
        #expect(nxt.fieldId != "A-01" && (order.firstIndex(of: nxt.fieldId) ?? 0) > (order.firstIndex(of: "A-01") ?? 0))
        #expect(FillQueue.next(t, r, after: nil)?.fieldId == FillQueue.steps(t, r).first?.fieldId)
        #expect(FillQueue.next(t, r, after: "A-01", skipped: [nxt.fieldId])?.fieldId != nxt.fieldId)
    }

    @Test func fillQueueTableStepAndEmptyWhenComplete() throws {
        var t = try Fixtures.template()
        let s = try #require(t.sections.firstIndex { $0.id == "E" })
        let k = try #require(t.sections[s].items.firstIndex { $0.id == "E1" })
        guard case .table(var e1) = t.sections[s].items[k] else { Issue.record("not a table"); return }
        e1.required = true
        t.sections[s].items[k] = .table(e1)
        let step = try #require(FillQueue.steps(t, Fixtures.filledRecord(t)).first)
        #expect(step.fieldId == "E1" && step.isTable && step.sectionId == "E")
        var done = Fixtures.filledRecord(t)
        done.tables["E1"] = [Row(values: ["kb": FieldValue(v: "S01")])]
        #expect(FillQueue.steps(t, done).isEmpty)
        #expect(FillQueue.next(t, done, after: nil) == nil)
    }

    // MARK: inline table

    @Test func inlineLayoutColumns() throws {
        let t = try Fixtures.template()
        let layout = try #require(t.table("E2")?.inlineLayout)
        #expect(layout.choice.id == "entered" && layout.labelColumns.map(\.id) == ["kb", "deviation", "level"])
        #expect(layout.sheetColumns.map(\.id) == ["phenomenon"])
        #expect(t.table("E1")?.inlineLayout == nil)                          // repeat table, not inline
    }

    @Test func inlineLayoutEligibility() throws {
        let t = try Fixtures.template()
        var e2 = try #require(t.table("E2"))
        e2.columns[e2.columns.firstIndex { $0.id == "entered" }!].type = .singleChoice
        e2.columns[e2.columns.firstIndex { $0.id == "entered" }!].options = (1...5).map { Option(value: "\($0)", label: "\($0)") }
        #expect(e2.inlineLayout == nil)                                       // > 4 options
        e2.columns[e2.columns.firstIndex { $0.id == "entered" }!].options = (1...4).map { Option(value: "\($0)", label: "\($0)") }
        #expect(e2.inlineLayout?.choice.id == "entered")
        e2.inline = false
        #expect(e2.inlineLayout == nil)
        var noChoice = try #require(t.table("E2"))
        noChoice.columns.removeAll { $0.id == "entered" }
        #expect(noChoice.inlineLayout == nil)
    }
}
