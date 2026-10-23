// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PollMasterCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PollMasterCore", targets: ["PollMasterCore"])],
    dependencies: [
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", from: "0.9.19"),
    ],
    targets: [
        .target(name: "PollMasterCore", dependencies: ["ZIPFoundation"]),
        .testTarget(name: "PollMasterCoreTests", dependencies: ["PollMasterCore"]),
    ]
)
