// swift-tools-version:5.9
import PackageDescription

// A parte da GoatTV que não é ecrã (listas, guia, conta, definições). Usa o núcleo partilhado com o Android
// (GoatShared.xcframework, feito pelo CI a partir do módulo shared/ em Kotlin para Frameworks/, não vai para o git),
// por isso os testes correm num Mac (`swift test`).
let package = Package(
    name: "GoatCore",
    platforms: [.tvOS(.v17), .iOS(.v17), .macOS(.v14)],
    products: [.library(name: "GoatCore", targets: ["GoatCore"])],
    targets: [
        .binaryTarget(name: "GoatShared", path: "Frameworks/GoatShared.xcframework"),
        // O núcleo em Kotlin/Native é estático e traz código em C++.
        .target(name: "GoatCore", dependencies: ["GoatShared"], linkerSettings: [.linkedLibrary("c++")]),
        // Fixtures: canais curtos feitos com o ffmpeg (os mesmos de shared/src/jvmTest/resources/ts).
        .testTarget(name: "GoatCoreTests", dependencies: ["GoatCore"], resources: [.copy("Fixtures")]),
    ]
)
