import Foundation

enum XML {
    /// Escape text and drop characters illegal in XML 1.0.
    static func escape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for u in s.unicodeScalars {
            switch u {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "\t", "\n", "\r": out.unicodeScalars.append(u)
            default:
                if u.value >= 0x20 && !(0xD800...0xDFFF).contains(u.value) && u.value != 0xFFFE && u.value != 0xFFFF {
                    out.unicodeScalars.append(u)
                }
            }
        }
        return out
    }

    static let header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
}
