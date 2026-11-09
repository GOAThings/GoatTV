import Foundation
@testable import GoatCore

/// As regras do leitor: os mesmos casos do GoatTV Android (OpenRetryTest, ZapPacerTest, AfrTest e PrefsTest).
extension CoreTests {
    private static func ladder(_ code: Int?) -> [Int] {
        var out: [Int] = []
        var retry = 1
        while let d = OpenRetry.delayMs(httpCode: code, retry: retry) {
            out.append(d)
            retry += 1
        }
        return out
    }

    static func openRetryBusyTriesFastAndOften() throws {
        for code in [403, 406, 429, 458, 503, 509] {
            try expect(ladder(code), [150, 200, 250, 250, 300, 300, 400, 500, 600], "\(code)")
            try expect(OpenRetry.isBusy(code), true, "\(code)")
        }
    }

    static func openRetryNotFoundOnceMore() throws {
        try expect(ladder(404), [250])
        try expect(ladder(410), [250])
        try expect(OpenRetry.isBusy(404), false)
    }

    static func openRetryOtherErrorsShorter() throws {
        try expect(ladder(nil), [0, 500, 1_000])
        try expect(ladder(500), [0, 500, 1_000])
        try expect(OpenRetry.isBusy(nil), false)
    }

    static func zapPacerOnePressOpensAtOnce() throws {
        var p = ZapPacer()
        try expect(p.delayFor(nowMs: 10_000, setting: Prefs.ZapDelay.auto), 0)
        try expect(p.delayFor(nowMs: 12_000, setting: Prefs.ZapDelay.auto), 0)
    }

    static func zapPacerFlippingOpensWhereYouStop() throws {
        var p = ZapPacer()
        let auto = Prefs.ZapDelay.auto
        try expect(p.delayFor(nowMs: 0, setting: auto), 0)
        try expect(p.delayFor(nowMs: 200, setting: auto), ZapPacer.settleMs)
        try expect(p.delayFor(nowMs: 450, setting: auto), ZapPacer.settleMs)
        try expect(p.delayFor(nowMs: 900, setting: auto), ZapPacer.settleMs)
        try expect(p.delayFor(nowMs: 900 + ZapPacer.flipWindowMs, setting: auto), 0)
    }

    static func zapPacerOffAndFixed() throws {
        var p = ZapPacer()
        for t: Int64 in [0, 50, 100] { try expect(p.delayFor(nowMs: t, setting: Prefs.ZapDelay.off), 0) }
        var q = ZapPacer()
        try expect(q.delayFor(nowMs: 0, setting: 1_000), 1_000)
        try expect(q.delayFor(nowMs: 60_000, setting: 2_000), 2_000)
    }

    static func frameRateSnap() throws {
        try expect(FrameRate.snap(15), 15)
        try expect(FrameRate.snap(25.1), 25)
        try expect(FrameRate.snap(29.971), 29.97)
        try expect(FrameRate.snap(59.95), 59.94)
        try expect(FrameRate.snap(23.98), 23.976)
    }

    static func frameRateCounter() throws {
        /// [fps] imagens por segundo lidas de [stepMs] em [stepMs] ms, com o contador a andar aos saltos de 1 s.
        func measure(_ fps: Double, seconds: Int = 4, stepMs: Int64 = 250, coarse: Bool = false) -> Double? {
            var c = FrameRate.Counter()
            var last: Double?
            var t: Int64 = 0
            while t <= Int64(seconds) * 1000 {
                let shownAt = coarse ? (t / 1000) * 1000 : t
                let frames = 1 + Int(Double(shownAt) * fps / 1000)
                if let v = c.add(ms: 5_000 + t, frames: frames) { last = v }
                t += stepMs
            }
            return last
        }
        try expect(measure(25), 25)
        try expect(measure(50), 50)
        try expect(measure(30_000 / 1001), 29.97)
        try expect(measure(30), 29.97, "a contagem não separa 30 de 29,97: fica a da TV")
        try expect(measure(24), 23.976)
        try expect(measure(25, coarse: true), 25)
        try expect(measure(25, seconds: 2), nil, "ainda sem tempo que chegue")
        // Sem imagens, ou um contador que volta atrás (outro canal), recomeça.
        var c = FrameRate.Counter()
        try expect(c.add(ms: 0, frames: 0), nil)
        _ = c.add(ms: 100, frames: 500)
        try expect(c.add(ms: 3_000, frames: 10), nil)
        try expect(FrameRate.snapCounted(15), nil)
    }

    static func afrSessionModes() throws {
        var off = AfrSession()
        try expect(off.onFps(mode: Prefs.AfrMode.off, fps: 25), nil)

        var start = AfrSession()
        try expect(start.onFps(mode: Prefs.AfrMode.start, fps: 25), 25)
        try expect(start.onFps(mode: Prefs.AfrMode.start, fps: 25), nil)
        start.newChannel()
        try expect(start.onFps(mode: Prefs.AfrMode.start, fps: 29.97), nil)
        start.reset()
        try expect(start.onFps(mode: Prefs.AfrMode.start, fps: 29.97), 29.97)

        var always = AfrSession()
        try expect(always.onFps(mode: Prefs.AfrMode.always, fps: 25), 25)
        try expect(always.onFps(mode: Prefs.AfrMode.always, fps: 25), nil)
        always.newChannel()
        try expect(always.onFps(mode: Prefs.AfrMode.always, fps: 59.94), 59.94)
    }

    static func prefsAfrModeKeepsTheOldKey() throws {
        func mode(_ s: [String: JSON]) -> String { Prefs(settings: .object(s)).afrMode }
        try expect(mode(["afr": true]), Prefs.AfrMode.always)
        try expect(mode(["afr": false]), Prefs.AfrMode.off)
        try expect(mode([:]), Prefs.AfrMode.off)
        try expect(mode(["afr": false, "afr_mode": "start"]), Prefs.AfrMode.off)
        try expect(mode(["afr": true, "afr_mode": "start"]), Prefs.AfrMode.start)
        try expect(mode(["afr": true, "afr_mode": "x"]), Prefs.AfrMode.always)
        // Escrever um modo escreve as duas chaves (desligado apaga o modo).
        for m in Prefs.AfrMode.all {
            var settings: [String: JSON] = ["afr": true, "afr_mode": "always"]
            for (k, v) in Prefs.afrModeValues(m) {
                if let v { settings[k] = v } else { settings.removeValue(forKey: k) }
            }
            let p = Prefs(settings: .object(settings))
            try expect(p.afrMode, m)
            try expect(p.afr, m != Prefs.AfrMode.off)
            if m == Prefs.AfrMode.off { try expect(settings, ["afr": false]) }
        }
    }

    static func prefsNewKeysAcceptOnlyTheirOptions() throws {
        func p(_ s: [String: JSON]) -> Prefs { Prefs(settings: .object(s)) }
        try expect(p([:]).zapDelayMs, Prefs.ZapDelay.auto)
        try expect(p(["zap_delay_ms": 2_000]).zapDelayMs, 2_000)
        try expect(p(["zap_delay_ms": 0]).zapDelayMs, Prefs.ZapDelay.off)
        try expect(p(["zap_delay_ms": 700]).zapDelayMs, Prefs.ZapDelay.auto)
        try expect(p(["afr_fallback": "50"]).afrFallback, Prefs.AfrFallback.hz50)
        try expect(p(["afr_fallback": "48"]).afrFallback, Prefs.AfrFallback.none)
        try expect(Prefs.AfrFallback.fps(Prefs.AfrFallback.hz5994), 59.94)
        try expect(p(["audio_language": " PT "]).audioLanguage, "pt")
        try expect(p(["audio_language": "português"]).audioLanguage, "")
        try expect(p(["subtitle_language": "eng"]).subtitleLanguage, "eng")
        try expect(p(["catchup_offset_min": -900]).catchupOffsetMin, -720)
        try expect(p(["catchup_offset_min": 60]).catchupOffsetMin, 60)
        try expect(p([:]).recordPaddingBeforeMin, 1)
        try expect(p([:]).recordPaddingAfterMin, 2)
        try expect(p(["record_padding_before_min": 5, "record_padding_after_min": 3]).recordPaddingBeforeMin, 5)
        try expect(p(["record_padding_after_min": 3]).recordPaddingAfterMin, 2)
    }
}
