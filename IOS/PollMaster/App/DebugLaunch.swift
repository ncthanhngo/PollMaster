import Foundation

#if DEBUG
extension AppEnvironment {
    /// Simulator verification aid: `-pmCreate <templateId>` makes a record and opens it,
    /// `-pmSection <n>` then pushes that section.
    func applyDebugLaunchArguments() {
        let d = UserDefaults.standard
        guard let tid = d.string(forKey: "pmCreate"), let t = templates.first(where: { $0.id == tid }) else { return }
        createRecord(from: t)
        if d.object(forKey: "pmSection") != nil, case .editor(let id)? = recordsPath.first {
            recordsPath.append(.section(record: id, index: d.integer(forKey: "pmSection"), focus: nil))
        }
    }
}
#else
extension AppEnvironment { func applyDebugLaunchArguments() {} }
#endif
