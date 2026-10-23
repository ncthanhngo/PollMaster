import Foundation

extension ChipsRange {
    /// Highest chip: the integer > 0 found at `maxRef` ("fieldId" -> its value, "fieldId.partId" -> that part), else `fallbackMax`.
    public func resolvedMax(_ lookup: (String) -> FieldValue?) -> Int {
        guard let ref = maxRef, !ref.isEmpty else { return fallbackMax }
        let bits = ref.split(separator: ".", maxSplits: 1).map(String.init)
        guard let fv = lookup(bits[0]) else { return fallbackMax }
        let raw = bits.count > 1 ? fv.parts?[bits[1]] : fv.v
        guard let n = raw?.double, n == n.rounded(), n >= 1, n <= 1000 else { return fallbackMax }
        return Int(n)
    }

    /// Chip values `min...max`; empty when the range is inverted.
    public func values(_ lookup: (String) -> FieldValue?) -> [Int] {
        let top = resolvedMax(lookup)
        return top >= min ? Array(min...top) : []
    }
}
