// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorVideoProcessor",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorVideoProcessor",
            targets: ["CapacitorVideoProcessorPluginPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "CapacitorVideoProcessorPluginPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CapacitorVideoProcessorPluginPlugin"),
        .testTarget(
            name: "CapacitorVideoProcessorPluginPluginTests",
            dependencies: ["CapacitorVideoProcessorPluginPlugin"],
            path: "ios/Tests/CapacitorVideoProcessorPluginPluginTests")
    ]
)