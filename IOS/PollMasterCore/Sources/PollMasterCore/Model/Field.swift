import Foundation

public enum FieldType: String, Codable, Sendable, CaseIterable {
    case text, multiline, number, integer, singleChoice, multiChoice, yesNo, rating
    case date, time, datetime, sessionTime, month, gps, compass, photo, video
    case elapsed, composite, computed, signature, audio
}

public struct Option: Codable, Equatable, Sendable, Hashable {
    public var value: String
    public var label: String
    public var withText: Bool

    public init(value: String, label: String, withText: Bool = false) {
        self.value = value; self.label = label; self.withText = withText
    }

    enum CodingKeys: String, CodingKey { case value, label, withText }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        value = try c.decode(String.self, forKey: .value)
        label = try c.decode(String.self, forKey: .label)
        withText = try c.decodeIfPresent(Bool.self, forKey: .withText) ?? false
    }
}

public struct ShowWhen: Codable, Equatable, Sendable, Hashable {
    public var field: String
    public var equals: JSONValue?
    public var contains: String?
}

/// Sub-input of a field ("___ x ___").
public struct Part: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var type: FieldType
    public var unit: String?
    public var options: [Option]?
    public var showWhen: ShowWhen?
    public var warnMin: Double?
    public var warnMax: Double?

    enum CodingKeys: String, CodingKey { case id, label, type, unit, options, showWhen, warnMin, warnMax }

    /// `label` is required, `type` defaults to text (schema §6.6).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decode(String.self, forKey: .label)
        type = try c.decodeIfPresent(FieldType.self, forKey: .type) ?? .text
        unit = try c.decodeIfPresent(String.self, forKey: .unit)
        options = try c.decodeIfPresent([Option].self, forKey: .options)
        showWhen = try c.decodeIfPresent(ShowWhen.self, forKey: .showWhen)
        warnMin = try c.decodeIfPresent(Double.self, forKey: .warnMin)
        warnMax = try c.decodeIfPresent(Double.self, forKey: .warnMax)
    }

    public init(id: String, label: String, type: FieldType, unit: String? = nil, options: [Option]? = nil, showWhen: ShowWhen? = nil,
                warnMin: Double? = nil, warnMax: Double? = nil) {
        self.id = id; self.label = label; self.type = type; self.unit = unit; self.options = options; self.showWhen = showWhen
        self.warnMin = warnMin; self.warnMax = warnMax
    }

    /// Pseudo field so part inputs reuse the scalar input views.
    public var asField: Field {
        var f = Field(id: id, label: label, type: type)
        f.unit = unit; f.options = options; f.flags = false; f.warnMin = warnMin; f.warnMax = warnMax
        return f
    }
}

/// `lookup` of a text field: pick a site, then fill other fields (`fill`: target field id -> site attribute).
public struct Lookup: Codable, Equatable, Sendable {
    public var source: String
    public var fill: [String: String]
    /// GPS field compared against the chosen site's planned coordinate (never filled from it).
    public var gps: String?

    enum CodingKeys: String, CodingKey { case source, fill, gps }

    public init(source: String = "sites", fill: [String: String] = [:], gps: String? = nil) {
        self.source = source; self.fill = fill; self.gps = gps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "sites"
        fill = try c.decodeIfPresent([String: String].self, forKey: .fill) ?? [:]
        gps = try c.decodeIfPresent(String.self, forKey: .gps)
    }
}

/// `chipsRange` of an integer field: one-tap chips `min…max`, `max` read from `maxRef` ("fieldId" or "fieldId.partId").
public struct ChipsRange: Codable, Equatable, Sendable {
    public var min: Int
    public var maxRef: String?
    public var fallbackMax: Int

    enum CodingKeys: String, CodingKey { case min, maxRef, fallbackMax }

    public init(min: Int = 1, maxRef: String? = nil, fallbackMax: Int = 10) {
        self.min = min; self.maxRef = maxRef; self.fallbackMax = fallbackMax
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        min = try c.decodeIfPresent(Int.self, forKey: .min) ?? 1
        maxRef = try c.decodeIfPresent(String.self, forKey: .maxRef)
        fallbackMax = try c.decodeIfPresent(Int.self, forKey: .fallbackMax) ?? 10
    }
}

public struct Field: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var type: FieldType
    public var required: Bool = false
    public var unit: String?
    public var method: String?
    public var hint: String?
    public var flags: Bool?
    public var samples: Int?
    public var parts: [Part]?
    public var `default`: JSONValue?
    public var readonly: Bool = false
    public var role: String?
    public var prefix: String?
    public var attachPhoto: Bool = false
    public var options: [Option]?
    public var min: Double?
    public var max: Double?
    public var decimals: Int?
    public var minCount: Int?
    public var maxCount: Int?
    public var labels: [String]?
    public var allowUnknown: Bool = false
    public var minLabel: String?
    public var maxLabel: String?
    public var formula: String?
    public var photoRef: String?
    public var warnMin: Double?
    public var warnMax: Double?
    public var maxAccuracy: Double?
    public var lookup: Lookup?
    public var carryOver: Bool = false
    public var short: Bool = false
    public var stepper: Bool = false
    public var chipsRange: ChipsRange?

    public init(id: String, label: String, type: FieldType) {
        self.id = id; self.label = label; self.type = type
    }

    enum CodingKeys: String, CodingKey {
        case id, label, type, required, unit, method, hint, flags, samples, parts, `default`, readonly, role, prefix
        case attachPhoto, options, min, max, decimals, minCount, maxCount, labels, allowUnknown, minLabel, maxLabel, formula
        case photoRef, warnMin, warnMax, maxAccuracy, lookup, carryOver, short, stepper, chipsRange
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? id
        type = try c.decode(FieldType.self, forKey: .type)
        required = try c.decodeIfPresent(Bool.self, forKey: .required) ?? false
        unit = try c.decodeIfPresent(String.self, forKey: .unit)
        method = try c.decodeIfPresent(String.self, forKey: .method)
        hint = try c.decodeIfPresent(String.self, forKey: .hint)
        flags = try c.decodeIfPresent(Bool.self, forKey: .flags)
        samples = try c.decodeIfPresent(Int.self, forKey: .samples)
        parts = try c.decodeIfPresent([Part].self, forKey: .parts)
        `default` = try c.decodeIfPresent(JSONValue.self, forKey: .default)
        readonly = try c.decodeIfPresent(Bool.self, forKey: .readonly) ?? false
        role = try c.decodeIfPresent(String.self, forKey: .role)
        prefix = try c.decodeIfPresent(String.self, forKey: .prefix)
        attachPhoto = try c.decodeIfPresent(Bool.self, forKey: .attachPhoto) ?? false
        options = try c.decodeIfPresent([Option].self, forKey: .options)
        min = try c.decodeIfPresent(Double.self, forKey: .min)
        max = try c.decodeIfPresent(Double.self, forKey: .max)
        decimals = try c.decodeIfPresent(Int.self, forKey: .decimals)
        minCount = try c.decodeIfPresent(Int.self, forKey: .minCount)
        maxCount = try c.decodeIfPresent(Int.self, forKey: .maxCount)
        labels = try c.decodeIfPresent([String].self, forKey: .labels)
        allowUnknown = try c.decodeIfPresent(Bool.self, forKey: .allowUnknown) ?? false
        minLabel = try c.decodeIfPresent(String.self, forKey: .minLabel)
        maxLabel = try c.decodeIfPresent(String.self, forKey: .maxLabel)
        formula = try c.decodeIfPresent(String.self, forKey: .formula)
        photoRef = try c.decodeIfPresent(String.self, forKey: .photoRef)
        warnMin = try c.decodeIfPresent(Double.self, forKey: .warnMin)
        warnMax = try c.decodeIfPresent(Double.self, forKey: .warnMax)
        maxAccuracy = try c.decodeIfPresent(Double.self, forKey: .maxAccuracy)
        lookup = try c.decodeIfPresent(Lookup.self, forKey: .lookup)
        carryOver = try c.decodeIfPresent(Bool.self, forKey: .carryOver) ?? false
        short = try c.decodeIfPresent(Bool.self, forKey: .short) ?? false
        stepper = try c.decodeIfPresent(Bool.self, forKey: .stepper) ?? false
        chipsRange = try c.decodeIfPresent(ChipsRange.self, forKey: .chipsRange)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(label, forKey: .label); try c.encode(type, forKey: .type)
        if required { try c.encode(true, forKey: .required) }
        try c.encodeIfPresent(unit, forKey: .unit); try c.encodeIfPresent(method, forKey: .method)
        try c.encodeIfPresent(hint, forKey: .hint); try c.encodeIfPresent(flags, forKey: .flags)
        try c.encodeIfPresent(samples, forKey: .samples); try c.encodeIfPresent(parts, forKey: .parts)
        try c.encodeIfPresent(`default`, forKey: .default)
        if readonly { try c.encode(true, forKey: .readonly) }
        try c.encodeIfPresent(role, forKey: .role); try c.encodeIfPresent(prefix, forKey: .prefix)
        if attachPhoto { try c.encode(true, forKey: .attachPhoto) }
        try c.encodeIfPresent(options, forKey: .options); try c.encodeIfPresent(min, forKey: .min)
        try c.encodeIfPresent(max, forKey: .max); try c.encodeIfPresent(decimals, forKey: .decimals)
        try c.encodeIfPresent(minCount, forKey: .minCount); try c.encodeIfPresent(maxCount, forKey: .maxCount)
        try c.encodeIfPresent(labels, forKey: .labels)
        if allowUnknown { try c.encode(true, forKey: .allowUnknown) }
        try c.encodeIfPresent(minLabel, forKey: .minLabel); try c.encodeIfPresent(maxLabel, forKey: .maxLabel)
        try c.encodeIfPresent(formula, forKey: .formula)
        try c.encodeIfPresent(photoRef, forKey: .photoRef)
        try c.encodeIfPresent(warnMin, forKey: .warnMin); try c.encodeIfPresent(warnMax, forKey: .warnMax)
        try c.encodeIfPresent(maxAccuracy, forKey: .maxAccuracy); try c.encodeIfPresent(lookup, forKey: .lookup)
        if carryOver { try c.encode(true, forKey: .carryOver) }
        if short { try c.encode(true, forKey: .short) }
        if stepper { try c.encode(true, forKey: .stepper) }
        try c.encodeIfPresent(chipsRange, forKey: .chipsRange)
    }

    /// `~` / KĐĐ markers allowed? Default true for number, composite, text.
    public var flagsEnabled: Bool {
        flags ?? [.number, .composite, .text].contains(type)
    }
    public var isComputed: Bool { type == .computed }
    public var ratingMax: Int { Int(max ?? 5) }
    public var displayDecimals: Int { decimals ?? 2 }
    /// photo / video / audio: a `Media` list stored in `v`.
    public var isMedia: Bool { type == .photo || type == .video || type == .audio }
    public var gpsMaxAccuracy: Double { maxAccuracy ?? 15 }
    public func optionLabel(_ value: String) -> String { options?.first { $0.value == value }?.label ?? value }
}
