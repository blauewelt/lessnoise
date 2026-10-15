// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LessNoiseKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    // Dynamic: the iOS app and its test bundle then share ONE copy of the types at run time.
    products: [.library(name: "LessNoiseKit", type: .dynamic, targets: ["LessNoiseKit"])],
    dependencies: [
        .package(path: "../LessNoiseCore"),
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        .target(name: "LessNoiseKit", dependencies: ["LessNoiseCore", .product(name: "GRDB", package: "GRDB.swift")]),
        .testTarget(name: "LessNoiseKitTests", dependencies: ["LessNoiseKit"]),
    ]
)
