// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "ImpalaCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "ImpalaCore", targets: ["ImpalaCore"])
    ],
    targets: [
        .target(
            name: "ImpalaCore",
            path: "Sources/ImpalaCore"
        ),
        .testTarget(
            name: "ImpalaCoreTests",
            dependencies: ["ImpalaCore"],
            path: "Tests/ImpalaCoreTests"
        )
    ]
)
