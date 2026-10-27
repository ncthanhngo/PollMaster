import Foundation
import Network
import Observation

/// Whether the device has a usable network path right now (not a guarantee that the ERP answers).
@MainActor @Observable
final class NetworkStatus {
    private(set) var online = true
    @ObservationIgnored private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            Task { @MainActor in self?.online = ok }
        }
        monitor.start(queue: DispatchQueue(label: "pollmaster.network"))
    }

    deinit { monitor.cancel() }
}
