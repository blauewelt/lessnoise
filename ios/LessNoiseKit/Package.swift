// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LessNoiseKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "LessNoiseKit", targets: ["LessNoiseKit"])],
    dependencies: [
        .package(path: "../LessNoiseCore"),
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        .target(name: "LessNoiseKit", dependencies: ["LessNoiseCore", .product(name: "GRDB", package: "GRDB.swift")]),
        .testTarget(name: "LessNoiseKitTests", dependencies: ["LessNoiseKit"]),
    ]
)
