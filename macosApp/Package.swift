// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "SafePocketMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "SafePocketMac", targets: ["SafePocketMac"])
    ],
    targets: [
        .executableTarget(
            name: "SafePocketMac",
            path: "SafePocketMac"
        )
    ]
)
