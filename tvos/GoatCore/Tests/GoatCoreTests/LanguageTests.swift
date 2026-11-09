import Foundation
@testable import GoatCore

/// As línguas das faixas e a escolha automática: os mesmos casos do GoatTV Android (LanguagesTest e TrackPickTest).
extension CoreTests {
    private static func expectCode(_ expected: String?, _ raws: String?..., line: Int = #line) throws {
        for raw in raws { try expect(Languages.code(raw), expected, "«\(raw ?? "nil")»", line: line) }
    }

    static func languagesIsoCodes() throws {
        try expectCode("pt", "pt", "por", "POR", "Por", "pt-PT", "pt_BR", "pt-br")
        try expectCode("de", "de", "deu", "ger", "GER")
        try expectCode("fr", "fr", "fra", "fre")
        try expectCode("zh", "zh", "zho", "chi", "zh-Hans", "cmn")
        try expectCode("nl", "nl", "nld", "dut")
        try expectCode("el", "el", "ell", "gre")
        try expectCode("cs", "ces", "cze")
        try expectCode("ro", "ron", "rum")
        try expectCode("he", "he", "iw", "heb")
        try expectCode("id", "id", "in", "ind")
        try expectCode("no", "no", "nb", "nn", "nor", "nob", "nno")
        try expectCode(nil, nil, "", "  ", "und", "UND", "mul", "zxx", "mis", "qaa", "qad", "qtz", "xx")
    }

    static func languagesNames() throws {
        try expectCode("pt", "Portuguese", "Português", "portugues", "PORTUGUÊS", "Portugués", "Portugais", "Portugiesisch")
        try expectCode("en", "English", "Inglês", "Anglais", "Englisch")
        try expectCode("es", "Spanish", "Español", "Castellano")
        try expectCode("ru", "Russian", "Русский")
        try expectCode("ar", "Arabic", "العربية")
        try expectCode("ja", "Japanese", "日本語")
    }

    static func languagesLabels() throws {
        try expectCode("pt", "Faixa 1 - [Portuguese]", "Track 2 [por]", "POR AC3", "Audio (Português)", "por 5.1")
        try expectCode("en", "Track 1 - [English]", "ENG Dolby Digital", "English (Original)")
        try expectCode("de", "Deutsch Stereo")
        try expectCode("pt", "Brazilian Portuguese")
        try expectCode(nil, "Audio 1", "Track 2", "Stereo", "Dolby Digital 5.1", "Original", "AAC", "Main", "Commentary", "HD")
    }

    static func languagesEveryChoiceHasAName() throws {
        try expect(Array(Set(Languages.choices)).count, Languages.choices.count)
        for code in Languages.choices {
            // O código das Definições lê-se de volta como ele próprio (o que se grava é o que se compara).
            try expect(Languages.code(code), code)
            for app in ["en", "pt", "ja", "ar"] {
                let name = Languages.displayName(code, locale: app)
                try expect(!name.isEmpty && name != code, true, "\(code) em \(app): \(name)")
            }
        }
        try expect(Languages.displayName("pt", locale: "pt-PT"), "Português")
        try expect(Languages.displayName("en", locale: "pt-PT"), "Inglês")
    }

    private static func t(_ lang: String?, _ label: String? = nil, channels: Int = 2, selected: Bool = false, supported: Bool = true, forced: Bool = false) -> TrackInfo {
        TrackInfo(language: lang, label: label, channels: channels, supported: supported, selected: selected, forced: forced)
    }

    static func trackPickPreferredLanguage() throws {
        try expect(TrackPick.audio([t("eng", selected: true), t("por"), t("spa")], language: "pt", surround: false), 1)
        // Nada muda quando a língua já está a dar ou não existe.
        try expect(TrackPick.audio([t("pt", selected: true), t("en")], language: "pt", surround: false), nil)
        try expect(TrackPick.audio([t("eng", selected: true), t("spa")], language: "pt", surround: false), nil)
        try expect(TrackPick.audio([t("eng", selected: true), t("spa")], language: nil, surround: false), nil)
        // TS com "qaa" (versão original) e a dobragem marcada só na etiqueta.
        try expect(TrackPick.audio([t("qaa", selected: true), t(nil, "Faixa 2 - [Portuguese]")], language: "pt", surround: false), 1)
        try expect(TrackPick.audio([t("und", selected: true), t("und", "Português")], language: "pt", surround: false), 1)
    }

    static func trackPickSurround() throws {
        let tracks = [t("en", channels: 6), t("pt", channels: 2, selected: true), t("pt", channels: 6), t("en", channels: 8)]
        try expect(TrackPick.audio(tracks, language: "pt", surround: true), 2)
        try expect(TrackPick.audio(tracks, language: "fr", surround: true), 3)
        try expect(TrackPick.audio([t("en", channels: 6), t("pt", selected: true)], language: "pt", surround: true), nil)
        try expect(TrackPick.audio([t("pt", selected: true), t("pt", channels: 6)], language: nil, surround: true), 1)
        try expect(TrackPick.audio([t("pt", selected: true), t("en")], language: nil, surround: true), nil)
        try expect(TrackPick.audio([t("en", selected: true), t("pt", supported: false)], language: "pt", surround: false), nil)
        try expect(TrackPick.audio([], language: "pt", surround: true), nil)
    }

    static func trackPickSubtitles() throws {
        let tracks = [t("en"), t("pt", forced: true), t("por")]
        try expect(TrackPick.text(tracks, language: "pt"), 2)
        try expect(TrackPick.text([t("en"), t("pt", forced: true)], language: "pt"), 1)
        try expect(TrackPick.text(tracks, language: "fr"), nil)
        try expect(TrackPick.text(tracks, language: nil), nil)
    }

    static func trackChoiceKeepsAManualPick() throws {
        var c = TrackChoice()
        c.onOpen("a")
        try expect(c.pending(.audio), true)
        c.done(.audio)
        try expect(c.pending(.audio), false)
        try expect(c.pending(.text), true)
        c.onManual(.text)
        // O mesmo canal outra vez (catch-up, salto): o áudio volta a escolher-se, as legendas à mão ficam.
        c.onOpen("a")
        try expect(c.pending(.audio), true)
        try expect(c.pending(.text), false)
        try expect(c.isManual(.text), true)
        c.onOpen("b")
        try expect(c.pending(.text), true)
        try expect(c.isManual(.text), false)
    }
}
