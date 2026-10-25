import XCTest

/// Uma volta pela app no simulador com o comando virtual, com um screenshot a cada passo (vão para o
/// .xcresult e o CI publica-os). Usa a lista de demonstração de UITests/Demo, servida pelo runner em :8765.
final class GoatTVUITests: XCTestCase {
    private let app = XCUIApplication()
    private let remote = XCUIRemote.shared
    private var step = 0

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-UITestPlaylist", "http://127.0.0.1:8765/demo.m3u",
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    }

    func testTour() {
        app.launch()
        pause(2); shot("intro")
        pause(14); shot("playing")

        // Zapping e HUD.
        press(.down, wait: 2); shot("zap-card")
        pause(6)
        press(.select, wait: 2); shot("hud")
        press(.menu, wait: 1.5)

        // Lista de canais e de grupos.
        press(.left, wait: 2); shot("channels")
        press(.left, wait: 2); shot("groups")

        // Definições → Listas → Demo → Grupos: o olho.
        guard focus("gear", .up) else { return shot("no-gear") }
        press(.select, wait: 2); shot("settings")
        open("lists"); open("p:demo"); shot("playlist")
        open("groups"); shot("groups-eye")
        if focus("g:Entertainment") {
            press(.select, wait: 1.5); shot("entertainment-hidden")
        }
        press(.menu, wait: 1.5)

        // Canais → Movies: esconder um canal.
        open("channels"); shot("channel-groups")
        open("g:Movies"); shot("channels-eye")
        if focus("c:orbit") {
            press(.select, wait: 1.5); shot("orbit-hidden")
        }
        for _ in 0..<5 { press(.menu, wait: 1) }
        pause(1); shot("settings-closed")

        // O grupo escondido já não aparece na lista de grupos.
        press(.left, wait: 2)
        press(.left, wait: 2); shot("groups-after-hide")
        XCTAssertFalse(app.buttons["group:Entertainment"].exists, "Entertainment continua na lista de grupos")
        press(.menu, wait: 1.5)
        if app.buttons["group:Movies"].exists || app.buttons["channel:lumen"].exists { press(.menu, wait: 1.5) }

        // Voltar abre o guia.
        press(.menu, wait: 3); shot("guide")
        press(.down, wait: 1); press(.right, wait: 1.5); shot("guide-moved")
        press(.menu, wait: 2)

        // O outro motor: VLC.
        press(.left, wait: 2); press(.left, wait: 2)
        guard focus("gear", .up) else { return shot("no-gear-2") }
        press(.select, wait: 2)
        open("playback"); shot("playback")
        open("engine"); shot("engine")
        if focus("o1") { press(.select, wait: 1.5) }
        shot("playback-vlc")
        for _ in 0..<2 { press(.menu, wait: 1) }
        press(.down, wait: 10); shot("vlc-playing")
        press(.select, wait: 2); shot("vlc-hud")
        press(.menu, wait: 1.5)

        // A Top Shelf: no ecrã principal com o GoatTV em foco (sem a extensão é a imagem fixa); ↑ e OK abre o canal.
        press(.home, wait: 4)
        let icon = XCUIApplication(bundleIdentifier: "com.apple.PineBoard").icons["GoatTV"]
        for _ in 0..<8 where !(icon.exists && icon.hasFocus) { press(.right, wait: 0.8) }
        pause(15); shot("top-shelf")
        press(.up, wait: 2); shot("top-shelf-card")
        press(.select, wait: 10); shot("opened-from-top-shelf")
    }

    // MARK: Ajudas

    private func pause(_ seconds: Double) {
        Thread.sleep(forTimeInterval: seconds)
    }

    private func press(_ button: XCUIRemote.Button, wait: Double = 0.6) {
        remote.press(button)
        pause(wait)
    }

    /// Anda com o foco ([direction] e depois o contrário) até ao elemento com esse id; false se não chegar.
    @discardableResult
    private func focus(_ id: String, _ direction: XCUIRemote.Button = .down, steps: Int = 25) -> Bool {
        let element = app.buttons[id]
        // Se não está no ecrã, não se carrega em nada (no player, ↑/↓ mudavam de canal).
        guard element.waitForExistence(timeout: 3) else {
            XCTFail("\(id) não existe")
            shot("missing-\(id)")
            return false
        }
        let back: XCUIRemote.Button = direction == .down ? .up : direction == .up ? .down : direction == .left ? .right : .left
        for d in [direction, back] {
            for _ in 0..<steps {
                if element.exists, element.hasFocus { return true }
                press(d, wait: 0.35)
            }
        }
        if element.exists, element.hasFocus { return true }
        XCTFail("Sem foco em \(id)")
        shot("missing-\(id)")
        return false
    }

    private func open(_ id: String) {
        if focus(id) { press(.select, wait: 1.5) }
    }

    private func shot(_ name: String) {
        step += 1
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = String(format: "%02d-%@", step, name)
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
