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
        // As linhas novas (como no Android TV): o modo do AFR e a língua do áudio.
        open("afr"); shot("afr-modes")
        press(.menu, wait: 1)
        open("audio_language"); shot("audio-languages")
        press(.menu, wait: 1)
        for _ in 0..<2 { press(.menu, wait: 1) }
        press(.down, wait: 10); shot("vlc-playing")
        press(.select, wait: 2); shot("vlc-hud")
        press(.menu, wait: 1.5)
        // A passar canais depressa (atraso automático): a informação segue cada tecla, o canal abre onde se parar.
        press(.down, wait: 0.2); press(.down, wait: 0.2); shot("zap-flipping")
        press(.down, wait: 10); shot("zap-settled")

        // A Top Shelf: no ecrã principal com o GoatTV em foco (sem a extensão é a imagem fixa); ↑ e OK abre o canal.
        press(.home, wait: 4)
        let icon = XCUIApplication(bundleIdentifier: "com.apple.PineBoard").icons["GoatTV"]
        for _ in 0..<8 where !(icon.exists && icon.hasFocus) { press(.right, wait: 0.8) }
        pause(15); shot("top-shelf")
        press(.up, wait: 2); shot("top-shelf-card")
        press(.select, wait: 10); shot("opened-from-top-shelf")
    }

    /// Pausar o direto com um canal .ts ao vivo (UITests/Live/live-ts.py): Play/Pausa pausa, ← recua 10 s, OK pausa e
    /// continua, Voltar volta ao direto. Só no workflow timeshift-sim (TIMESHIFT_TEST=1), que serve esse canal.
    func testTimeshift() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["TIMESHIFT_TEST"] != "1", "Só com o canal .ts do workflow timeshift-sim")
        app.launchArguments = ["-UITestPlaylist", "http://127.0.0.1:8765/live.m3u", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        pause(25); shot("ts-live")
        XCTAssertNil(behind(), "atrás do direto sem se ter pausado")

        // Play/Pausa: pausa; a gravação continua por trás.
        press(.playPause, wait: 8); shot("ts-paused")
        XCTAssertTrue(app.otherElements["timeshiftBar"].exists, "sem a barra da pausa do direto")
        let paused = behind() ?? -1
        XCTAssertTrue((5...14).contains(paused), "8 s em pausa, mas vai \(paused) s atrás")

        // ← recua 10 s: primeiro por confirmar, depois o salto (o VLC reabre noutro ponto do anel).
        press(.left, wait: 0.5); shot("ts-rewind-pending")
        pause(9); shot("ts-rewound")
        let rewound = behind() ?? -1
        XCTAssertTrue(rewound >= paused + 6, "recuou de \(paused) s para \(rewound) s")

        // OK pausa e OK continua (do mesmo ponto).
        press(.select, wait: 4); shot("ts-paused-again")
        press(.select, wait: 6); shot("ts-resumed")
        let resumed = behind() ?? -1
        XCTAssertTrue(resumed >= rewound + 1 && resumed <= rewound + 10, "depois de 4 s em pausa: \(rewound) s → \(resumed) s")

        // Voltar: de volta ao direto.
        press(.menu, wait: 8); shot("ts-back-live")
        XCTAssertNil(behind(), "ainda atrás do direto depois de Voltar")
        XCTAssertFalse(app.otherElements["timeshiftBar"].exists, "a barra ficou no ecrã no direto")

        // Gravar agora (OK → Gravar): copia do anel desta ligação; o leitor e a gravação usam a mesma (o servidor conta as
        // ligações e o workflow falha com mais de uma).
        press(.select, wait: 2)
        if focus("hud:record", .right) { press(.select, wait: 4) }
        shot("rec-started")
        XCTAssertTrue(app.staticTexts["REC"].waitForExistence(timeout: 5), "sem a cápsula REC")
        press(.menu, wait: 10); shot("rec-watching")

        // ↓ outro canal a gravar: o cartão "Estás a gravar"; Voltar fica a gravar neste canal.
        press(.down, wait: 3); shot("rec-switch-card")
        XCTAssertTrue(app.buttons["recording-back"].exists, "sem o cartão da gravação ao mudar de canal")
        press(.menu, wait: 3); shot("rec-kept")
        XCTAssertTrue(app.staticTexts["REC"].exists, "a gravação parou depois de Voltar")

        // O painel das gravações (OK → Gravações) e ver a gravação, com a barra e o "GRAVADO".
        press(.select, wait: 2)
        if focus("hud:recordings", .right) { press(.select, wait: 3) }
        shot("rec-panel")
        press(.select, wait: 10); shot("rec-playback")
        XCTAssertTrue(app.staticTexts["RECORDED"].exists, "a ver a gravação sem o \"RECORDED\"")
        // Voltar: o direto do canal que se grava, pela mesma ligação.
        press(.menu, wait: 10); shot("rec-back-live")
        XCTAssertFalse(app.staticTexts["RECORDED"].exists, "ainda na gravação depois de Voltar")

        // Parar a gravação (OK → Parar a gravação).
        press(.select, wait: 2)
        if focus("hud:record", .right) { press(.select, wait: 4) }
        press(.menu, wait: 3); shot("rec-stopped")
        XCTAssertFalse(app.staticTexts["REC"].exists, "a cápsula REC ficou depois de parar")

        // ↓ o canal HLS ao vivo: a janela do próprio leitor da Apple (sem disco).
        press(.down, wait: 25); shot("hls-live")
        XCTAssertNil(behind(), "HLS: atrás do direto sem se ter pausado")
        press(.playPause, wait: 10); shot("hls-paused")
        XCTAssertTrue(app.otherElements["timeshiftBar"].exists, "HLS: sem a barra da pausa do direto")
        let hlsPaused = behind() ?? -1
        // O fim da janela do HLS anda aos saltos de um bocado (4 s aqui) e o AVPlayer relê a lista mesmo em pausa: a
        // conta do "atrás" tem essa folga (já se viram 2, 3 e 7 s depois de 10 s em pausa).
        XCTAssertTrue((1...16).contains(hlsPaused), "HLS: 10 s em pausa, mas vai \(hlsPaused) s atrás")
        press(.left, wait: 8); shot("hls-rewound")
        let hlsRewound = behind() ?? -1
        XCTAssertTrue(hlsRewound >= hlsPaused + 5, "HLS: recuou de \(hlsPaused) s para \(hlsRewound) s")
        press(.menu, wait: 8); shot("hls-back-live")
        XCTAssertNil(behind(), "HLS: ainda atrás do direto depois de Voltar")

        // ↓ um MP4 num endereço .ts: não é TS, o VLC abre-o diretamente (sem pausa do direto), depois de o gravador
        // largar a ligação.
        press(.down, wait: 20); shot("mp4-direct")
        XCTAssertNil(behind(), "MP4: atrás do direto")
        XCTAssertFalse(app.otherElements["timeshiftBar"].exists, "MP4: com a barra da pausa do direto")
    }

    /// Quanto a cápsula da pausa do direto diz que vai atrás ("−0:18" → 18), ou nil sem ela.
    private func behind() -> Int? {
        let pill = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "−")).firstMatch
        guard pill.exists else { return nil }
        let parts = pill.label.dropFirst().split(separator: ":").compactMap { Int($0) }
        return parts.reduce(0) { $0 * 60 + $1 }
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
