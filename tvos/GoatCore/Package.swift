// swift-tools-version:5.9
import PackageDescription

// A parte da GoatTV que não é ecrã (listas, guia, conta, definições): corre também em Linux, para se testar sem Mac.
let package = Package(
    name: "GoatCore",
    platforms: [.tvOS(.v17), .iOS(.v17), .macOS(.v14)],
    products: [.library(name: "GoatCore", targets: ["GoatCore"])],
    targets: [
        .target(name: "GoatCore"),
        .testTarget(name: "GoatCoreTests", dependencies: ["GoatCore"]),
    ]
)
