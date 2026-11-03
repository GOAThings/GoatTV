import AVFoundation
import SwiftUI

@main
struct GoatPhoneApp: App {
    @UIApplicationDelegateAdaptor(PhoneOrientation.self) private var orientation
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

/// O botão de ecrã inteiro prende a orientação pedida, até o telemóvel ficar mesmo nela: a partir daí volta a rodar com a mão.
final class PhoneOrientation: NSObject, UIApplicationDelegate {
    private static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Self.mask
    }

    static func force(_ target: UIInterfaceOrientationMask) {
        mask = target
        apply()
        scene?.requestGeometryUpdate(.iOS(interfaceOrientations: target))
    }

    static func deviceMoved() {
        let device = UIDevice.current.orientation
        guard (mask == .portrait && device == .portrait) || (mask == .landscape && device.isLandscape) else { return }
        mask = .allButUpsideDown
        apply()
    }

    private static var scene: UIWindowScene? {
        UIApplication.shared.connectedScenes.first as? UIWindowScene
    }

    private static func apply() {
        scene?.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}
