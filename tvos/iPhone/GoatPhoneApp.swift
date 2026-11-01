import AVFoundation
import SwiftUI

@main
struct GoatPhoneApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 256 << 20, directory: nil)
        // Com o botão de silêncio ligado, o som do vídeo continua a ouvir-se.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            PhoneRoot()
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
