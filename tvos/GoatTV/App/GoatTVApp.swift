import SwiftUI

@main
struct GoatTVApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Logótipos dos canais: ficam em cache (memória e disco) entre aberturas.
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 256 << 20, directory: nil)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .onAppear { model.start() }
                .onOpenURL { model.open($0) }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { model.didEnterBackground() }
                    if phase == .active { model.didBecomeActive() }
                }
        }
    }
}
