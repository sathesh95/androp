// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ClipboardSync",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(
            name: "ClipboardSync",
            targets: ["ClipboardSync"]
        )
    ],
    dependencies: [],
    targets: [
        .executableTarget(
            name: "ClipboardSync",
            dependencies: [],
            path: "Sources"
        )
    ]
)
