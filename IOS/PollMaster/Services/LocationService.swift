import CoreLocation
import Foundation
import Observation

enum LocationFailure: LocalizedError {
    case denied, timeout, headingUnavailable
    var errorDescription: String? {
        switch self {
        case .denied: return "Chưa được cấp quyền vị trí. Bật trong Cài đặt > PollMaster."
        case .timeout: return "Không lấy được tín hiệu, thử lại ngoài trời."
        case .headingUnavailable: return "Thiết bị không có la bàn."
        }
    }
}

/// One-shot GPS fix and compass heading.
@MainActor @Observable
final class LocationService: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var locationWaiter: CheckedContinuation<CLLocation, Error>?
    private var headingWaiter: CheckedContinuation<Double, Error>?

    /// Cached last fix; nil without permission.
    var lastKnown: CLLocation? {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: return manager.location
        default: return nil
        }
    }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func currentLocation() async throws -> CLLocation {
        guard locationWaiter == nil else { throw LocationFailure.timeout }
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
            for _ in 0..<60 where manager.authorizationStatus == .notDetermined { try? await Task.sleep(for: .milliseconds(500)) }
        }
        guard [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) else { throw LocationFailure.denied }
        let timeout = Task { [weak self] in
            try await Task.sleep(for: .seconds(15))
            self?.finishLocation(.failure(LocationFailure.timeout))
        }
        defer { timeout.cancel() }
        return try await withCheckedThrowingContinuation { cont in
            locationWaiter = cont
            manager.requestLocation()
        }
    }

    func currentHeading() async throws -> Double {
        guard CLLocationManager.headingAvailable() else { throw LocationFailure.headingUnavailable }
        guard headingWaiter == nil else { throw LocationFailure.timeout }
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        let timeout = Task { [weak self] in
            try await Task.sleep(for: .seconds(5))
            self?.finishHeading(.failure(LocationFailure.timeout))
        }
        defer { timeout.cancel() }
        return try await withCheckedThrowingContinuation { cont in
            headingWaiter = cont
            manager.startUpdatingHeading()
        }
    }

    private func finishLocation(_ r: Result<CLLocation, Error>) {
        guard let w = locationWaiter else { return }
        locationWaiter = nil
        w.resume(with: r)
    }

    private func finishHeading(_ r: Result<Double, Error>) {
        manager.stopUpdatingHeading()
        guard let w = headingWaiter else { return }
        headingWaiter = nil
        w.resume(with: r)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last { finishLocation(.success(l)) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishLocation(.failure(error))
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let h = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        if h >= 0 { finishHeading(.success(h)) }
    }
}
