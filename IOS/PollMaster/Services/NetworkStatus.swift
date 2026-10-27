import Foundation
import Network
import Observation

/// Whether the device has a usable network path right now (not a guarantee that the ERP answers).
@MainActor @Observable
final class NetworkStatus {
    private(set) var online = true
    /// Called when the network comes back after being gone.
    @ObservationIgnored var onReconnect: (() -> Void)?
    @ObservationIgnored private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let back = ok && !self.online
                self.online = ok
                if back { self.onReconnect?() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "pollmaster.network"))
    }

    deinit { monitor.cancel() }
}
