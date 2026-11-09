import Foundation
@testable import GoatCore

/// O catch-up das listas M3U: os mesmos casos do GoatTV Android (M3uCatchupTest), com exemplos como os do README do
/// Kodi IPTV Simple (os endereços são inventados).
extension CoreTests {
    // 8 nov. 2026, 20:30:15 UTC; o programa dura 1 h e "agora" é 3 h depois do início.
    private static let cuStart: Int64 = 1_794_169_815
    private static let cuEnd: Int64 = cuStart + 3_600
    private static let cuNow: Int64 = cuStart + 10_800
    private static let utc = TimeZone(identifier: "UTC")!
    private static let paris = TimeZone(identifier: "Europe/Paris")!

    private static func parseM3u(_ lines: String...) -> [Channel] {
        M3UParser.parse(Data(lines.joined(separator: "\n").utf8)).channels
    }

    private static func cuChannel(_ url: String, _ type: String?, _ source: String? = nil) -> Channel {
        Channel(uid: 0, key: "k", num: 1, name: "C", logo: nil, categoryId: "g", url: url, archiveDays: 3, catchupType: type, catchupSource: source)
    }

    private static func cuUrls(_ c: Channel, _ zone: TimeZone = utc) -> [String] {
        var seen = Set<String>()
        return M3uCatchup.candidates(c, start: cuStart, end: cuEnd, now: cuNow, zone: zone).map(\.url).filter { seen.insert($0).inserted }
    }

    static func m3uCatchupAttributes() throws {
        let c = parseM3u(
            "#EXTM3U",
            #"#EXTINF:-1 tvg-id="a" catchup="default" catchup-days="7" catchup-source="http://cu.example.com/a?start={utc}&end={utcend}",A"#,
            "http://live.example.com/a.m3u8",
            #"#EXTINF:-1 tvg-id="b" catchup-type="flussonic" catchup-days="2",B"#,
            "http://fs.example.com/b/index.m3u8?token=t",
            #"#EXTINF:-1 tvg-id="c",C sem catch-up"#,
            "http://live.example.com/c.ts"
        )
        try expect(c.map(\.archiveDays), [7, 2, 0])
        try expect(c.map(\.catchupType), ["default", "flussonic", nil])
        try expect(c[0].catchupSource, "http://cu.example.com/a?start={utc}&end={utcend}")
        try expect(c[1].catchupSource, nil)

        let h = parseM3u(
            #"#EXTM3U url-tvg="http://epg.example.com/e.xml" catchup="append" catchup-days="5" catchup-source="?utc={utc}&lutc={lutc}""#,
            #"#EXTINF:-1 tvg-id="a",A"#,
            "http://live.example.com/a.m3u8",
            #"#EXTINF:-1 tvg-id="b" catchup="shift" catchup-days="1",B"#,
            "http://live.example.com/b.m3u8"
        )
        try expect(h.map(\.catchupType), ["append", "shift"])
        try expect(h.map(\.archiveDays), [5, 1])
        try expect(h[0].catchupSource, "?utc={utc}&lutc={lutc}")
        try expect(h[1].catchupSource, nil)

        let d = parseM3u(
            "#EXTM3U",
            #"#EXTINF:-1 tvg-id="a" timeshift="3",A"#,
            "http://ott.example.com/ch/a/video.m3u8?token=x",
            #"#EXTINF:-1 tvg-id="b" tvg-rec="4",B"#,
            "http://panel.example.com:8080/live/user/pass/1477.ts"
        )
        try expect(d.map(\.catchupType), ["shift", "xc"])
        try expect(d.map(\.archiveDays), [3, 4])
    }

    static func m3uCatchupAliasesAndRefusals() throws {
        func spec(_ attrs: [String: String], url: String = "http://h.example.com/live/u/p/1.ts") -> M3uCatchup.Spec? {
            M3uCatchup.spec(attrs, header: [:], url: url)
        }
        try expect(spec(["catchup": "fs"])?.type, M3uCatchup.flussonicTs)
        try expect(spec(["catchup": "flussonic-ts"])?.type, M3uCatchup.flussonicTs)
        try expect(spec(["catchup": "Flussonic-HLS"])?.type, M3uCatchup.flussonic)
        try expect(spec(["catchup": "xtream"])?.type, M3uCatchup.xc)
        try expect(spec(["catchup": "shift"])?.days, M3uCatchup.defaultDays)
        try expect(spec(["catchup-source": "http://x.example.com/{utc}.ts"])?.type, M3uCatchup.default)
        try expect(spec(["catchup": "vod", "catchup-source": "{catchup-id}"]), nil)
        try expect(spec(["catchup": "default"]), nil)
        try expect(spec(["catchup": "append", "catchup-source": "?id={catchup-id}"]), nil)
        try expect(spec(["catchup": "0"]), nil)
        try expect(spec(["catchup": "xc"], url: "http://h.example.com/a.m3u8"), nil)
        try expect(spec([:]), nil)
    }

    static func m3uCatchupUrls() throws {
        let s = cuStart, e = cuEnd, n = cuNow
        let def = cuChannel("http://live.example.com/325/mono.m3u8?token=x", "default",
                            "http://cu.example.com/325/mono.m3u8?token=x&utc={utc}&lutc={lutc}&end=${end}&d={duration}&min={duration:60}&ago={offset:60}")
        try expect(cuUrls(def), ["http://cu.example.com/325/mono.m3u8?token=x&utc=\(s)&lutc=\(n)&end=\(e)&d=3600&min=60&ago=180"])

        let dates = cuChannel("http://live.example.com/a.ts", "default", "http://cu.example.com/{Y}/{m}/{d}/{H}-{M}-{S}/a.ts?s=${start:YmdHMS}&e={utcend:Y-m-d:H-M}")
        try expect(cuUrls(dates, utc), ["http://cu.example.com/2026/11/08/20-30-15/a.ts?s=20261108203015&e=2026-11-08:21-30"])
        try expect(cuUrls(dates, paris), ["http://cu.example.com/2026/11/08/21-30-15/a.ts?s=20261108213015&e=2026-11-08:22-30"])

        try expect(cuUrls(cuChannel("http://live.example.com/325/mono.m3u8?token=x", "append", "&utc={utc}&lutc={lutc}")),
                   ["http://live.example.com/325/mono.m3u8?token=x&utc=\(s)&lutc=\(n)"])
        try expect(cuUrls(cuChannel("http://live.example.com/a.ts", "shift")), ["http://live.example.com/a.ts?utc=\(s)&lutc=\(n)"])
        try expect(cuUrls(cuChannel("http://live.example.com/a.m3u8?t=1", "shift")), ["http://live.example.com/a.m3u8?t=1&utc=\(s)&lutc=\(n)"])

        try expect(cuUrls(cuChannel("http://ch01.example.com/151/mpegts?token=my_token", "flussonic")), ["http://ch01.example.com/151/timeshift_abs-\(s).ts?token=my_token"])
        try expect(cuUrls(cuChannel("http://fs.example.com:8888/325/index.m3u8?token=secret", "flussonic")), ["http://fs.example.com:8888/325/index-\(s)-3600.m3u8?token=secret"])
        try expect(cuUrls(cuChannel("http://fs.example.com:8888/325/mono.m3u8", "flussonic")), ["http://fs.example.com:8888/325/mono-\(s)-3600.m3u8"])
        try expect(cuUrls(cuChannel("http://fs.example.com/325/live?token=t", "flussonic-ts")), ["http://fs.example.com/325/timeshift_abs-\(s).ts?token=t"])
        try expect(cuUrls(cuChannel("http://fs.example.com/325", "flussonic-ts")), ["http://fs.example.com/325/timeshift_abs-\(s).ts"])

        try expect(cuUrls(cuChannel("http://panel.example.com:8080/live/my@account.xc/my_password/1477.ts", "xc"), paris), [
            "http://panel.example.com:8080/timeshift/my@account.xc/my_password/60/2026-11-08:21-30/1477.ts",
            "http://panel.example.com:8080/streaming/timeshift.php?username=my@account.xc&password=my_password&stream=1477&start=2026-11-08:21-30&duration=60",
        ])
        try expect(cuUrls(cuChannel("http://panel.example.com/u/p/9", "xc")).first, "http://panel.example.com/timeshift/u/p/60/2026-11-08:20-30/9.ts")
        try expect(cuUrls(cuChannel("http://panel.example.com/live/u/p/9.m3u8", "xc")).first, "http://panel.example.com/timeshift/u/p/60/2026-11-08:20-30/9.m3u8")
    }

    static func m3uCatchupFormats() throws {
        func formats(_ c: Channel) -> [String] { M3uCatchup.candidates(c, start: cuStart, end: cuEnd, now: cuNow, zone: utc).map(\.format) }
        try expect(formats(cuChannel("http://live.example.com/a.ts", "default", "http://cu.example.com/a.m3u8?s={utc}")), [StreamFormat.hls])
        try expect(formats(cuChannel("http://live.example.com/a.m3u8", "default", "http://cu.example.com/archive?s={utc}")), [StreamFormat.hls, StreamFormat.ts])
        try expect(formats(cuChannel("http://live.example.com/play/1", "shift")), [StreamFormat.ts, StreamFormat.hls])
    }

    static func m3uCatchupRequest() throws {
        let m3u = Playlist(id: "p", type: .m3u, name: "M3U", url: "http://list.example.com/l.m3u")
        func cu(_ c: Channel, _ positionMs: Int64 = 0) -> Catchup { Catchup(channel: c, title: "Programa", startMs: cuStart * 1000, endMs: cuEnd * 1000, positionMs: positionMs) }
        let shift = cuChannel("http://live.example.com/a.ts", "shift")
        let a = catchupCandidates(playlist: m3u, catchup: cu(shift, 61_500), timezone: nil, nowMs: cuNow * 1000, zone: utc)
        try expect(a?.startMs, (cuStart + 61) * 1000)
        try expect(a?.candidates.first?.url, "http://live.example.com/a.ts?utc=\(cuStart + 61)&lutc=\(cuNow)")
        let xc = cuChannel("http://panel.example.com/live/u/p/9.ts", "xc")
        try expect(catchupCandidates(playlist: m3u, catchup: cu(xc), timezone: nil, nowMs: cuNow * 1000)?.startMs, (cuStart - 15) * 1000)
        let minutes = cuChannel("http://live.example.com/a.ts", "default", "http://cu.example.com/{Y}{m}{d}{H}{M}/a.ts")
        try expect(catchupCandidates(playlist: m3u, catchup: cu(minutes), timezone: nil, nowMs: cuNow * 1000)?.startMs, (cuStart - 15) * 1000)
        // Sem tipo (lista guardada antes desta versão): sem catch-up.
        var old = shift
        old.catchupType = nil
        try expect(catchupCandidates(playlist: m3u, catchup: cu(old), timezone: nil) == nil, true)

        // A correção da hora mexe só no que se pede: o leitor conta do início verdadeiro.
        let corrected = catchupCandidates(playlist: m3u, catchup: cu(shift), timezone: nil, correctionMin: -60, nowMs: cuNow * 1000)
        try expect(corrected?.startMs, cuStart * 1000)
        try expect(corrected?.candidates.first?.url, "http://live.example.com/a.ts?utc=\(cuStart - 3600)&lutc=\(cuNow)")

        let xtream = Playlist(id: "x", type: .xtream, name: "X", server: "http://panel.example.com", username: "u", password: "p")
        var ch = cuChannel("", nil)
        ch.streamId = "9"
        let asked = catchupCandidates(playlist: xtream, catchup: cu(ch), timezone: "UTC", correctionMin: 120)
        try expect(asked?.startMs, (cuStart - 15) * 1000)
        try expect(asked?.candidates.first?.url.hasSuffix("/timeshift/u/p/61/2026-11-08:22-30/9.ts"), true, asked?.candidates.first?.url ?? "-")

        let stalker = Playlist(id: "s", type: .stalker, name: "S", server: "http://portal.example.com", username: "00:1A:79:00:00:01")
        try expect(catchupCandidates(playlist: stalker, catchup: cu(cuChannel("http://portal.example.com/ch/1", nil)), timezone: nil) == nil, true)

        // O guia mostra o catch-up dos canais M3U com tipo, dentro dos dias.
        let entry = EpgEntry(title: "P", startMs: cuStart * 1000, endMs: cuEnd * 1000)
        try expect(Catchup.available(playlist: m3u, channel: shift, entry: entry, now: cuNow * 1000), true)
        try expect(Catchup.available(playlist: m3u, channel: old, entry: entry, now: cuNow * 1000), false)
    }
}

/// O arquivo do Stalker: os mesmos casos do GoatTV Android (StalkerTest).
extension CoreTests {
    static func stalkerArchiveDays() throws {
        func days(_ fields: [String: JSON]) -> Int { Stalker.archiveDays(.object(fields)) }
        try expect(days(["tv_archive_duration": 168]), 0)
        try expect(days(["tv_archive": "0", "tv_archive_duration": 168]), 0)
        try expect(days(["tv_archive": 1, "tv_archive_duration": 168]), 7)
        try expect(days(["enable_tv_archive": "1", "tv_archive_duration": "168"]), 7)
        try expect(days(["archive": "1", "tv_archive_duration": "24"]), 1)
        try expect(days(["tv_archive": "1", "tv_archive_duration": "3"]), 3)
        try expect(days(["tv_archive": "1", "tv_archive_duration": "72"]), 3)
        try expect(days(["tv_archive": "1", "tv_archive_duration": "36"]), 2)
        // Marcado sem duração: uma semana (o padrão do Ministra).
        try expect(days(["tv_archive": "1"]), 7)
        // Os canais lidos do portal trazem os dias, e o guia passa a mostrar o catch-up deles.
        let channels = Stalker.parseChannels([.object(["id": "42", "cmd": "ffrt http://localhost/ch/42", "tv_archive": "1", "tv_archive_duration": "168"])],
                                             endpoint: "http://portal.example.com/stalker_portal/server/load.php", channelName: { $0 })
        try expect(channels.first?.archiveDays, 7)
    }

    static func stalkerArchivePicksThePortalProgrammeWhenTimesDrift() throws {
        let t0: Int64 = 1_794_160_800
        // O portal diz que o segundo programa começa um minuto depois do que diz o guia.
        let rows = [
            JSON.object(["id": "1", "start_timestamp": .number(Double(t0)), "stop_timestamp": .number(Double(t0 + 1860)), "mark_archive": 1]),
            JSON.object(["id": "2", "start_timestamp": .number(Double(t0 + 1860)), "stop_timestamp": .number(Double(t0 + 5400)), "mark_archive": 1]),
            JSON.object(["id": "3", "start_timestamp": .number(Double(t0 + 5400)), "stop_timestamp": .number(Double(t0 + 7200)), "mark_archive": "0"]),
        ].compactMap(Stalker.recorded)
        try expect(rows.map(\.id), ["1", "2"])
        // Do início (o guia diz t0 + 1800): a gravação do segundo, não o último minuto do primeiro.
        let fromStart = Stalker.matching(rows, wantedMs: (t0 + 1800) * 1000, startMs: (t0 + 1800) * 1000, endMs: (t0 + 5400) * 1000)
        try expect(fromStart?.id, "2")
        try expect(fromStart?.startMs, (t0 + 1860) * 1000)
        let middle = Stalker.matching(rows, wantedMs: (t0 + 3000) * 1000, startMs: (t0 + 1800) * 1000, endMs: (t0 + 5400) * 1000)
        try expect(middle?.id, "2")
        try expect(Stalker.matching(rows, wantedMs: (t0 + 9000) * 1000, startMs: (t0 + 9000) * 1000, endMs: (t0 + 9600) * 1000) == nil, true)
    }
}
