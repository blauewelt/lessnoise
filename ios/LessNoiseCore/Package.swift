// swift-tools-version:5.9
//
// LessNoiseCore: the platform-independent measuring core of «Weniger Lärm» for iOS, a port of
// android/dsp (and of the pure upload/UI logic of android/app). Foundation only on Apple
// platforms (CryptoKit for HMAC-SHA256, Compression for gzip); on Linux, where the golden tests
// run without a Mac, swift-crypto provides the same `Crypto` API. See ../README.md.

import PackageDescription

let package = Package(
    name: "LessNoiseCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        // Dynamic: the iOS app and its test bundle then share ONE copy of the types at run time.
        .library(name: "LessNoiseCore", type: .dynamic, targets: ["LessNoiseCore"]),
    ],
    dependencies: [
        // Linux only (CryptoKit is not available there); never linked into the iOS app.
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0"),
    ],
    targets: [
        .target(
            name: "LessNoiseCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux])),
            ]
        ),
        .testTarget(
            name: "LessNoiseCoreTests",
            // The golden fixtures live in Tests/Fixtures (outside the target) and are read by path.
            dependencies: ["LessNoiseCore"]
        ),
    ]
)
