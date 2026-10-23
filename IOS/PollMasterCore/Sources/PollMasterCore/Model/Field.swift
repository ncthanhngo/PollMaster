import Foundation

public enum FieldType: String, Codable, Sendable, CaseIterable {
    case text, multiline, number, integer, singleChoice, multiChoice, yesNo, rating
    case date, time, datetime, sessionTime, month, gps, compass, photo, video
    case elapsed, composite, computed, signature
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

    enum CodingKeys: String, CodingKey { case id, label, type, unit, options, showWhen }

    /// `label` is required, `type` defaults to text (schema §6.6).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decode(String.self, forKey: .label)
        type = try c.decodeIfPresent(FieldType.self, forKey: .type) ?? .text
        unit = try c.decodeIfPresent(String.self, forKey: .unit)
        options = try c.decodeIfPresent([Option].self, forKey: .options)
        showWhen = try c.decodeIfPresent(ShowWhen.self, forKey: .showWhen)
    }

    public init(id: String, label: String, type: FieldType, unit: String? = nil, options: [Option]? = nil, showWhen: ShowWhen? = nil) {
        self.id = id; self.label = label; self.type = type; self.unit = unit; self.options = options; self.showWhen = showWhen
    }

    /// Pseudo field so part inputs reuse the scalar input views.
    public var asField: Field {
        var f = Field(id: id, label: label, type: type)
        f.unit = unit; f.options = options; f.flags = false
        return f
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

    public init(id: String, label: String, type: FieldType) {
        self.id = id; self.label = label; self.type = type
    }

    enum CodingKeys: String, CodingKey {
        case id, label, type, required, unit, method, hint, flags, samples, parts, `default`, readonly, role, prefix
        case attachPhoto, options, min, max, decimals, minCount, maxCount, labels, allowUnknown, minLabel, maxLabel, formula
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
    }

    /// `~` / KĐĐ markers allowed? Default true for number, composite, text.
    public var flagsEnabled: Bool {
        flags ?? [.number, .composite, .text].contains(type)
    }
    public var isComputed: Bool { type == .computed }
    public var ratingMax: Int { Int(max ?? 5) }
    public var displayDecimals: Int { decimals ?? 2 }
    public var isMedia: Bool { type == .photo || type == .video }
    public func optionLabel(_ value: String) -> String { options?.first { $0.value == value }?.label ?? value }
}
