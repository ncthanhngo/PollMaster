import Testing
@testable import PollMasterCore

@Suite struct AppearanceModeTests {
    @Test func toggleFlipsDisplayedScheme() {
        #expect(AppearanceMode.toggled(displayingDark: false) == .dark)
        #expect(AppearanceMode.toggled(displayingDark: true) == .light)
    }

    @Test func unknownStoredValueFallsBackToNil() {
        #expect(AppearanceMode(rawValue: "") == nil)
        #expect(AppearanceMode(rawValue: "system") == .system)
    }
}
