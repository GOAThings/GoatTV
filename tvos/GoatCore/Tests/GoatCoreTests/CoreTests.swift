import Foundation
@testable import GoatCore

/// Os testes do núcleo, como funções simples: correm no Xcode (XCTest, ver XCTestRunner.swift) e em Linux sem XCTest.
enum CoreTests {
    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    static func expect<T: Equatable>(_ actual: T, _ expected: T, _ what: String = "", line: Int = #line) throws {
        if actual != expected { throw Failure(description: "linha \(line) \(what): esperava \(expected), veio \(actual)") }
    }

    static let all: [(String, () throws -> Void)] = [
        ("hashes", hashes),
        ("channelIndex", channelIndex),
        ("appUpdate", appUpdate),
        ("m3u", m3u),
        ("m3uKeys", m3uKeys),
        ("xtream", xtream),
        ("xtreamUrls", xtreamUrls),
        ("xmltv", xmltv),
        ("xmltvTime", xmltvTime),
        ("normalizeName", normalizeName),
        ("streams", streams),
        ("settingsMerge", settingsMerge),
        ("prefs", prefs),
        ("library", library),
        ("config", config),
        ("json", json),
        ("catchup", catchup),
        ("epgCatalog", epgCatalog),
        ("playlistForm", playlistForm),
    ]

    static func playlistForm() throws {
        var f = PlaylistForm(type: .xtream)
        f.server = " srv.tv:8080/ "
        f.username = "u "
        try expect(f.problem, .password)
        f.password = "p"
        let p = f.playlist(id: "x")
        try expect(p.server, "http://srv.tv:8080")
        try expect(p.name, "srv.tv")
        try expect(p.username, "u")
        try expect(p.url, nil)
        var m = PlaylistForm(type: .m3u)
        m.url = "lista.com/a.m3u"
        m.name = "Minha"
        try expect(m.playlist(id: "m").url, "http://lista.com/a.m3u")
        try expect(m.playlist(id: "m").name, "Minha")
        try expect(Backend.playlistBody(p)["url"], "")
    }

    static func epgCatalog() throws {
        let json = try JSON.parse(Data(#"[{"country":"pt","country_name":"Portugal","channels":[{"id":"RTP1.pt","name":"RTP 1","icon":"http://i/rtp1.png","now":"Telejornal"},{"id":"sic.pt","name":"SIC &amp; Co","icon":""}]}]"#.utf8))
        let results = json.array!.map(EpgCountryResult.init(json:))
        try expect(results[0].channels.count, 2)
        try expect(results[0].channels[1].name, "SIC & Co")
        try expect(results[0].channels[1].icon, nil)
        let channels = [
            Channel(uid: 0, key: "a", num: 1, name: "PT: RTP 1 HD", logo: nil, categoryId: "c"),
            Channel(uid: 1, key: "b", num: 2, name: "Outro", logo: nil, categoryId: "c", epgId: "rtp1.PT"),
            Channel(uid: 2, key: "c", num: 3, name: "Nada", logo: nil, categoryId: "c"),
        ]
        let pairs = Guide.match(channels, results[0].channels)
        try expect(pairs.map(\.0.key), ["a", "b"])
        try expect(pairs.map { $0.1.id ?? "" }, ["RTP1.pt", "RTP1.pt"])
    }

    static func catchup() throws {
        let p = Playlist(id: "x", type: .xtream, name: "X", server: "http://s:80", username: "u", password: "p")
        var ch = Channel(uid: 0, key: "9", num: 1, name: "A", logo: nil, categoryId: "c", streamId: "9", archiveDays: 2)
        let start = XMLTVParser.parseTime("20261024120000 +0000")!
        let cu = Catchup(channel: ch, title: "T", startMs: start, endMs: start + 3_600_000, positionMs: 90_000)
        let c = catchupCandidates(playlist: p, catchup: cu, timezone: "Europe/Lisbon")!
        try expect(c.startMs, start + 60_000)
        try expect(c.candidates.map(\.url), [
            "http://s:80/timeshift/u/p/59/2026-10-24:13-01/9.ts",
            "http://s:80/streaming/timeshift.php?username=u&password=p&stream=9&start=2026-10-24:13-01&duration=59",
        ])
        let entry = EpgEntry(title: "T", startMs: start, endMs: start + 3_600_000)
        try expect(Catchup.available(playlist: p, channel: ch, entry: entry, now: start + 3_600_000), true)
        ch.archiveDays = 0
        try expect(Catchup.available(playlist: p, channel: ch, entry: entry, now: start + 3_600_000), false)
    }

    static func hashes() throws {
        try expect(SHA.hex(SHA.sha1(Array("abc".utf8))), "a9993e364706816aba3e25717850c26c9cd0d89d")
        try expect(SHA.hex(SHA.sha256([])), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        // Calculados à parte (Python hashlib), iguais aos do Android.
        try expect(M3UParser.urlKey("http://example.com/live/1.ts"), "u1f19d54b353a")
        try expect(M3UParser.urlKey("http://a.b/c?x=1"), "udf750051f35d")
        try expect(Prefs.hashPin("1234"), "a7a4dce97baf5e19db8d7891cb4347178fcb468e0b21d5fb6ed4d9b9e911cf0a")
    }

    static func m3u() throws {
        let text = """
        \u{FEFF}#EXTM3U x-tvg-url="http://guide/a.xml.gz,http://guide/b.xml"
        #EXTINF:-1 tvg-id="rtp1.pt" tvg-name="RTP 1" tvg-logo="http://logo/rtp1.png" group-title="Portugal, Generalistas",RTP 1 HD
        http://srv/live/1.ts
        #EXTINF:-1 tvg-id="" group-title="",Canal Sem Grupo
        #EXTGRP:Desporto
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        #EXTVLCOPT:http-referrer=http://ref/
        http://srv/live/2.m3u8\r
        #EXTINF:-1,Filme
        http://srv/movie/u/p/9.mp4
        #EXTINF:-1 tvg-name="Só Nome"
        http://srv/live/3.ts
        """
        let r = M3UParser.parse(Data(text.utf8))
        try expect(r.epgUrl, "http://guide/a.xml.gz")
        try expect(r.channels.count, 3)
        try expect(r.channels[0].name, "RTP 1 HD")
        try expect(r.channels[0].key, "rtp1.pt")
        try expect(r.channels[0].epgId, "rtp1.pt")
        try expect(r.channels[0].categoryId, "Portugal, Generalistas")
        try expect(r.channels[0].logo, "http://logo/rtp1.png")
        try expect(r.channels[1].categoryId, "Desporto")
        try expect(r.channels[1].userAgent, "Mozilla/5.0")
        try expect(r.channels[1].referrer, "http://ref/")
        try expect(r.channels[1].url, "http://srv/live/2.m3u8")
        try expect(r.channels[1].key, M3UParser.urlKey("http://srv/live/2.m3u8"))
        try expect(r.channels[2].name, "Só Nome")
        try expect(r.channels[2].num, 3)
        try expect(r.categories.map(\.id), ["Portugal, Generalistas", "Desporto", PlaylistContent.others])
    }

    static func m3uKeys() throws {
        // tvg-id repetido: o segundo usa o hash do URL.
        let text = """
        #EXTM3U
        #EXTINF:-1 tvg-id="a",A
        http://x/1
        #EXTINF:-1 tvg-id="a",A2
        http://x/2
        """
        let r = M3UParser.parse(Data(text.utf8))
        try expect(r.channels.map(\.key), ["a", M3UParser.urlKey("http://x/2")])
        try expect(M3UParser.attributes("a=\"1\" B-c = \"2\" a=\"3\" bad=x"), ["a": "3", "b-c": "2"])
    }

    static func xtream() throws {
        let streams = """
        [{"num":1,"name":"RTP 1","stream_id":101,"stream_icon":"http://i/1.png","category_id":"5","epg_channel_id":"rtp1.pt","tv_archive":1,"tv_archive_duration":"3"},
         {"num":"2","name":"","stream_id":"102","stream_icon":"","category_id":7,"epg_channel_id":null,"tv_archive":0},
         {"num":3,"name":"Sem id"}]
        """
        let ch = try Xtream.parseStreams(Data(streams.utf8))
        try expect(ch.count, 2)
        try expect(ch[0].key, "101")
        try expect(ch[0].archiveDays, 3)
        try expect(ch[0].logo, "http://i/1.png")
        try expect(ch[1].name, "Channel 102")
        try expect(ch[1].categoryId, "7")
        try expect(ch[1].logo, nil)
        try expect(ch[1].num, 2)
        let cats = try Xtream.parseCategories(Data(#"[{"category_id":"5","category_name":" PT "},{"category_id":7,"category_name":""}]"#.utf8))
        try expect(cats, [Category(id: "5", name: "PT"), Category(id: "7", name: "7")])
        try expect(try Xtream.parseCategories(Data("{}".utf8)), [])
        let login = Xtream.parseLogin(Data(#"{"user_info":{"auth":1,"status":"Active","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"timezone":"Europe/Lisbon"}}"#.utf8))
        try expect(login?.auth, "1")
        try expect(login?.maxConnections, 2)
        try expect(login?.allowedFormats, ["m3u8", "ts"])
        try expect(login?.timezone, "Europe/Lisbon")
        let epg = Xtream.parseShortEpg(Data(#"{"epg_listings":[{"title":"Tm90w61jaWFz","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]}"#.utf8))
        try expect(epg, [EpgEntry(title: "Notícias", startMs: 1_700_000_000_000, endMs: 1_700_003_600_000)])
    }

    static func xtreamUrls() throws {
        let p = Playlist(id: "x", type: .xtream, name: "X", server: "srv.tv:8080/", username: "us er", password: "p&ss/1")
        try expect(Xtream.streamURL(p, streamId: "101", ext: "ts"), "http://srv.tv:8080/live/us%20er/p&ss%2F1/101.ts")
        try expect(Xtream.xmltvURL(p), "http://srv.tv:8080/xmltv.php?username=us%20er&password=p%26ss/1")
        let api = try Xtream.apiURL(p, action: "get_live_streams").absoluteString
        try expect(api, "http://srv.tv:8080/player_api.php?username=us%20er&password=p%26ss/1&action=get_live_streams")
    }

    static func xmltv() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <channel id="RTP1.pt"><display-name lang="pt">RTP 1</display-name><display-name>RTP1 HD</display-name></channel>
          <channel id="sic.pt"><display-name>SIC</display-name></channel>
          <!-- comentário <programme> -->
          <programme start="20261024120000 +0100" stop="20261024130000 +0100" channel="rtp1.pt">
            <title lang="pt">Jornal da Tarde &amp; mais</title><title lang="en">News</title>
          </programme>
          <programme start="20261024130000 +0100" stop="20261024140000 +0100" channel="sic.pt"><title><![CDATA[A & B]]></title></programme>
          <programme start="20261024140000 +0100" stop="20261024150000 +0100" channel="outro"><title>Fora</title></programme>
        </tv>
        """
        let channels = [
            Channel(uid: 0, key: "a", num: 1, name: "RTP 1", logo: nil, categoryId: "c", epgId: "RTP1.PT"),
            Channel(uid: 1, key: "b", num: 2, name: "PT: SIC HD", logo: nil, categoryId: "c"),
        ]
        let now = XMLTVParser.parseTime("20261024123000 +0100")!
        let guide = XMLTVGuide.build(url: "u", data: Data(xml.utf8), channels: channels, now: now)
        try expect(guide.entries(for: channels[0]).map(\.title), ["Jornal da Tarde & mais"])
        try expect(guide.entries(for: channels[1]).map(\.title), ["A & B"])
        try expect(guide.programmes["outro"] == nil, true)
        let account = AccountGuide.parse(playlistId: "p", etag: nil, data: Data(xml.utf8), now: now)
        try expect(account.programmes["outro"]?.first?.title, "Fora")
    }

    static func xmltvTime() throws {
        try expect(XMLTVParser.parseTime("20261024120000 +0100"), 1_792_839_600_000)
        try expect(XMLTVParser.parseTime("20261024110000"), 1_792_839_600_000)
        try expect(XMLTVParser.parseTime("20261024110000 -0030"), 1_792_841_400_000)
        try expect(XMLTVParser.parseTime("2026"), nil)
        try expect(XMLTVParser.decode("a &lt;b&gt; &#233; &#x41; &unknown; &"), "a <b> é A &unknown; &")
    }

    static func normalizeName() throws {
        try expect(XMLTVGuide.normalizeName("PT: RTP 1 HD"), "rtp1")
        try expect(XMLTVGuide.normalizeName("TVI Ficção FHD"), "tvificcao")
        try expect(XMLTVGuide.normalizeName("UK | BBC One 1080p"), "bbcone")
    }

    static func streams() throws {
        let x = Playlist(id: "x", type: .xtream, name: "X", server: "http://s", username: "u", password: "p")
        let ch = Channel(uid: 0, key: "1", num: 1, name: "A", logo: nil, categoryId: "c", streamId: "1")
        try expect(streamCandidates(playlist: x, channel: ch, preferred: nil).map(\.url), ["http://s/live/u/p/1.ts", "http://s/live/u/p/1.m3u8"])
        try expect(streamCandidates(playlist: x, channel: ch, preferred: nil, allowedFormats: ["m3u8"]).map(\.format), ["m3u8"])
        let m = Playlist(id: "m", type: .m3u, name: "M", url: "http://l")
        var c2 = ch
        c2.url = "http://s/a/b.m3u8?t=1"
        try expect(streamCandidates(playlist: m, channel: c2, preferred: nil).map(\.url), ["http://s/a/b.m3u8?t=1", "http://s/a/b.ts?t=1"])
        c2.url = "http://s/a/b"
        try expect(streamCandidates(playlist: m, channel: c2, preferred: StreamFormat.ts).map(\.format), ["ts", "m3u8"])
    }

    static func settingsMerge() throws {
        let base: [String: JSON] = ["a": 1, "o": .object(["x": 1, "y": 2]), "h": .strings(["p:1", "p:2"]), "gone": true]
        let local: [String: JSON] = ["a": 2, "o": .object(["x": 1, "y": 3]), "h": .strings(["p:1", "p:3"])]
        let patch = SettingsMerge.diff(base, local)
        try expect(patch, ["a": 2, "o": .object(["y": 3]), "h": .strings(["p:1", "p:3"]), "gone": .null])
        try expect(SettingsMerge.apply(base, patch), local)
        try expect(SettingsMerge.hasTopLevelArray(patch), true)
        // O servidor juntou p:9 entretanto: fica, e aqui tirou-se p:2 e juntou-se p:3.
        let server: [String: JSON] = ["a": 1, "o": .object(["x": 1, "y": 2]), "h": .strings(["p:1", "p:2", "p:9"]), "gone": true, "new": "s"]
        let rebased = SettingsMerge.rebase(server: server, base: base, local: local)
        try expect(rebased["h"], .strings(["p:1", "p:9", "p:3"]))
        try expect(rebased["new"], "s")
        try expect(rebased["gone"], nil)
    }

    static func prefs() throws {
        let s: JSON = .object([
            "show_numbers": false, "sort": "name", "info_seconds": 99, "logo_source": "pt", "stream_format": "m3u8",
            "hidden_groups": .strings(["p:g"]), "channel_order": .object(["p:g": .strings(["b", "a"])]),
            "parental_pin_sha256": .string(String(repeating: "a", count: 64)), "epg_refresh_hours": 7,
        ])
        let p = Prefs(settings: s)
        try expect(p.showNumbers, false)
        try expect(p.sort, "name")
        try expect(p.infoSeconds, 30)
        try expect(p.logoSource, "pt")
        try expect(p.streamFormat, "m3u8")
        try expect(p.isHidden("p", "g"), true)
        try expect(p.channelOrder["p:g"], ["b", "a"])
        try expect(p.epgRefreshHours, 12)
        try expect(p.pinHash?.count, 64)
    }

    static func library() throws {
        let pl = Playlist(id: "p", type: .m3u, name: "P", url: "u")
        func ch(_ i: Int, _ key: String, _ name: String, _ cat: String) -> Channel {
            Channel(uid: i, key: key, num: i + 1, name: name, logo: nil, categoryId: cat)
        }
        let content = PlaylistContent.build(
            playlist: pl,
            categories: [Category(id: "news", name: "News"), Category(id: "kids", name: "Kids"), Category(id: "adult", name: "Adult")],
            channels: [ch(0, "n1", "Zeta", "news"), ch(1, "n2", "Alpha", "news"), ch(2, "k1", "Kid", "kids"), ch(3, "a1", "X", "adult"), ch(4, "o1", "Other", "nope")],
            guideUrl: nil
        )
        var settings: [String: JSON] = [
            "group_order": .object(["p": .strings(["kids"])]),
            "parental_pin_sha256": .string(Prefs.hashPin("0000")),
            "locked_groups": .strings(["p:adult"]),
            "hidden_channels": .strings(["p:n2"]),
        ]
        var lib = Library(content: content, prefs: Prefs(settings: .object(settings)), favorites: ["a1", "k1"], recents: ["n1"])
        try expect(lib.groups(favoritesName: "F", recentName: "R", allName: "A", othersName: "O").map(\.id),
                   [SpecialCategory.favorites, SpecialCategory.recent, SpecialCategory.all, "kids", "news", PlaylistContent.others])
        try expect(lib.channels(in: SpecialCategory.all).map(\.key), ["n1", "k1", "o1"])
        try expect(lib.channels(in: SpecialCategory.favorites).map(\.key), ["k1"])
        try expect(lib.channels(in: "news").map(\.key), ["n1"])
        settings["channel_order"] = .object(["p:news": .strings(["n2", "n1"])])
        settings["hidden_channels"] = nil
        settings["sort"] = "name"
        lib = Library(content: content, prefs: Prefs(settings: .object(settings)), favorites: [], recents: [], unlocked: ["p:adult"])
        try expect(lib.channels(in: "news").map(\.key), ["n2", "n1"])
        try expect(lib.channels(in: SpecialCategory.all).map(\.key), ["n2", "k1", "o1", "a1", "n1"])
        try expect(lib.isGroupLocked("adult"), false)
        settings["show_all_group"] = false
        settings["show_favorites_group"] = false
        settings["show_recent_group"] = false
        lib = Library(content: content, prefs: Prefs(settings: .object(settings)), favorites: [], recents: ["n1"])
        try expect(lib.groups(favoritesName: "F", recentName: "R", allName: "A", othersName: "O").first?.id, "kids")
        try expect(lib.groups(favoritesName: "F", recentName: "R", allName: "A", othersName: "O", forGuide: true).first?.id, SpecialCategory.all)
    }

    static func config() throws {
        let raw = """
        {"user":{"id":"u1","email":"a@b.c"},"device":{"id":"d1","name":"Sala"},
         "playlists":[{"id":"p1","type":"xtream","name":"","server":"s","username":"u","password":"p","url":"","epg_url":""},
                      {"id":"p2","type":"other"}],
         "favorites":{"p1":["1","2","1"]},"settings":{"last_channel":"2"},"settings_rev":7}
        """
        let c = Backend.parseConfig(try JSON.parse(Data(raw.utf8)))
        try expect(c.playlists.count, 1)
        try expect(c.playlists[0].name, "Xtream")
        try expect(c.playlists[0].url, nil)
        try expect(c.favorites["p1"], ["1", "2"])
        try expect(c.settings["last_channel"], "2")
        try expect(c.settingsRev, 7)
        try expect(c.deviceName, "Sala")
    }

    static func json() throws {
        let j = try JSON.parse(Data(#"{"a":[1,2.5,"x",true,null],"b":{"c":-3}}"#.utf8))
        try expect(j["a"]?.array?.count, 5)
        try expect(j["b"]?["c"]?.int, -3)
        try expect(j["a"]?.array?[1].text, "2.5")
        let round = try JSON.parse(j.encoded())
        try expect(round, j)
        try expect(String(decoding: JSON.object(["n": 5]).encoded(), as: UTF8.self), #"{"n":5}"#)
    }

    static func appUpdate() throws {
        try expect(AppUpdate.isNewer("0.8.7", than: "0.8.6"), true)
        try expect(AppUpdate.isNewer("0.10.0", than: "0.9.9"), true)
        try expect(AppUpdate.isNewer("v0.8.6", than: "0.8.6"), false)
        try expect(AppUpdate.isNewer("0.8", than: "0.8.1"), false)
        let withIpa = try JSON.parse(Data(#"{"tag_name":"v0.8.7","assets":[{"name":"goattv.apk"},{"name":"GoatTV.ipa"}]}"#.utf8))
        try expect(AppUpdate.version(fromLatest: withIpa), "0.8.7")
        let apkOnly = try JSON.parse(Data(#"{"tag_name":"v0.8.7","assets":[{"name":"goattv.apk"}]}"#.utf8))
        try expect(AppUpdate.version(fromLatest: apkOnly), nil)
    }

    /// O hash do índice de canais é o mesmo do Android (ChannelIndexTest) para a mesma lista.
    static func channelIndex() throws {
        let playlist = Playlist(id: "p1", type: .m3u, name: "Demo", url: "https://example.com/list.m3u")
        let content = PlaylistContent(
            playlist: playlist,
            categories: [Category(id: "1", name: "News"), Category(id: "Outros", name: "Outros")],
            channels: [
                Channel(uid: 0, key: "101", num: 1, name: "RTP 1", logo: "https://x/rtp1.png", categoryId: "1"),
                Channel(uid: 1, key: "u0a1b2c", num: 2, name: "Canal ç", logo: "logo.png", categoryId: "Outros"),
            ]
        )
        guard let index = ChannelIndex(content) else { throw Failure(description: "sem índice") }
        try expect(index.hash, "ea9f8cc27edc2e37f03ba70062966b66b503be16")
        let body = try JSON.parse(index.body())
        try expect(body["channels"]?.array?.count, 2)
        try expect(body["channels"]?.array?[1]["logo"] == nil, true)
    }
}
