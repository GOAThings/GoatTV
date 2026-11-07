import Foundation
@testable import GoatCore

/// A porta dos telemóveis (os canais para o GoatRemote): os mesmos casos do GoatTV Android (LanRequestTest e
/// PhoneChannelListTest), que são as regras da porta 6470 do GoatLauncher.
extension CoreTests {
    private static func request(_ lines: String...) -> LanRequest? {
        LanRequest.read(Array((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8))
    }

    static func lanRequestAccepts() throws {
        let android = request("POST /play/k%201 HTTP/1.1", "User-Agent: Dalvik/2.1.0", "Host: 192.168.1.20:6471", "Connection: Keep-Alive", "Content-Length: 0")
        try expect(android?.allowed, true)
        try expect(android?.method, "POST")
        try expect(android?.path, "/play/k%201")
        try expect(request("GET /channels HTTP/1.1", "Host: 192.168.1.20", "Accept: */*")?.allowed, true)
        try expect(request("GET /channels HTTP/1.1", "host: [fe80::1]:6471")?.allowed, true)
    }

    static func lanRequestRefuses() throws {
        try expect(request("POST /play/1 HTTP/1.1", "Host: 192.168.1.20:6471", "Origin: https://site.example")?.allowed, false)
        try expect(request("POST /play/1 HTTP/1.1", "Host: 192.168.1.20:6471", "ORIGIN: null")?.allowed, false)
        try expect(request("GET /channels HTTP/1.1", "Host: site.example:6471")?.allowed, false)
        try expect(request("GET /channels HTTP/1.1")?.allowed, false)
        try expect(request("GET /channels HTTP/1.1", "Host: 192.168.1.20", "Host: site.example")?.allowed, false)
    }

    static func lanRequestHosts() throws {
        for host in [
            "10.0.0.1", "10.0.0.1:6471", "255.255.255.255:65535",
            "[::1]", "[::]", "[fe80::1]:6471", "[fd12:3456::abcd]", "[fe80::1%25wlan0]:6471", "[fe80::1%wlan0]",
            "[1:2:3:4:5:6:7:8]", "[::ffff:192.168.1.20]:6471", "[1:2:3:4:5:6:1.2.3.4]",
        ] { try expect(LanRequest.isIpHost(host), true, host) }
        for host in [
            "", "tv", "tv.local", "tv.local:6471", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4.site.example", "+1.2.3.4",
            "1.2.3.4:", "1.2.3.4:x", "1.2.3.4:65536", "::1", "[", "[]", "[::1", "[::1]x", "[::1]:", "[site.example]",
            "[1:2:3]", "[1:2:3:4:5:6:7:8:9]", "[:::]", "[1::2::3]", "[12345::]", "[g::]", "[fe80::1%]", "[1.2.3.4]",
        ] { try expect(LanRequest.isIpHost(host), false, host) }
        for host in ["shield", "shield.local", "SHIELD.lan:6471", "tv.home.arpa", "tv.internal", "box.home"] {
            try expect(LanRequest.isHomeName(host), true, host)
        }
        for host in ["example.com", "evil.local.example.com", "a:b:c", "", "shield:port", "tv_1", "tv.local:99999"] {
            try expect(LanRequest.isHomeName(host), false, host)
        }
        try expect(request("GET /channels HTTP/1.1", "Host: shield.local:6471")?.allowed, true)
    }

    static func lanRequestLimit() throws {
        let limit = LanRequest.maxHeadBytes
        try expect(LanRequest.scan([UInt8](repeating: 0x61, count: limit)), .done(nil))
        try expect(LanRequest.scan([UInt8](repeating: 0x61, count: limit - 1)), .needMore)
        // A linha em branco depois do limite já não conta.
        try expect(LanRequest.scan([UInt8](repeating: 0x61, count: limit) + Array("\r\n\r\n".utf8)), .done(nil))
        try expect(LanRequest.read([UInt8](repeating: 0x61, count: 2 * limit)), nil)
        // Aos bocados, como chega pela rede.
        let head = Array("GET /channels HTTP/1.1\r\nHost: 10.0.0.1\r\n\r\n".utf8)
        try expect(LanRequest.scan(Array(head.prefix(30))), .needMore)
        if case .done(let request) = LanRequest.scan(head) {
            try expect(request?.allowed, true)
        } else {
            throw Failure(description: "o pedido inteiro não foi lido")
        }
    }

    static func lanRequestMalformed() throws {
        try expect(LanRequest.read(Array("GET /channels HTTP/1.1\r\nHost: 10.0.0.1\r\n".utf8)), nil)
        try expect(LanRequest.read([]), nil)
        try expect(LanRequest.read(Array("\r\n".utf8)), nil)
        try expect(LanRequest.read(Array("GET\r\n\r\n".utf8)), nil)
    }

    /// Só a rede de casa, e nunca a própria TV (os endereços dela, o da ligação, o loopback).
    static func lanPeer() throws {
        let tv: [UInt8] = [192, 168, 1, 20]
        let own: [[UInt8]] = [tv, [127, 0, 0, 1], [0xFE, 0x80, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20]]
        try expect(LanPeer.allowed(peer: [192, 168, 1, 30], local: tv, own: own), true)
        try expect(LanPeer.allowed(peer: tv, local: tv, own: []), false)
        try expect(LanPeer.allowed(peer: tv, local: nil, own: own), false)
        for peer: [UInt8] in [[10, 1, 2, 3], [172, 16, 0, 1], [172, 31, 255, 1], [169, 254, 3, 4]] {
            try expect(LanPeer.allowed(peer: peer, local: tv, own: own), true, "\(peer)")
        }
        for peer: [UInt8] in [[127, 0, 0, 1], [8, 8, 8, 8], [172, 32, 0, 1], [100, 64, 0, 1], [192, 169, 1, 1], [1, 2, 3]] {
            try expect(LanPeer.allowed(peer: peer, local: tv, own: own), false, "\(peer)")
        }
        func v6(_ head: [UInt8], _ tail: [UInt8]) -> [UInt8] { head + [UInt8](repeating: 0, count: 16 - head.count - tail.count) + tail }
        try expect(LanPeer.allowed(peer: v6([0xFE, 0x80], [0x01]), local: nil, own: own), true)
        try expect(LanPeer.allowed(peer: v6([0xFD, 0x12], [0x01]), local: nil, own: own), true)
        try expect(LanPeer.allowed(peer: v6([0xFE, 0xC0], [0x01]), local: nil, own: own), true)
        try expect(LanPeer.allowed(peer: v6([0x20, 0x01, 0x0D, 0xB8], [0x01]), local: nil, own: own), false)
        try expect(LanPeer.allowed(peer: v6([], [0x01]), local: nil, own: []), false)
        // O endereço de ligação local da TV, com o índice da interface que a Apple lá mete.
        try expect(LanPeer.allowed(peer: v6([0xFE, 0x80], [0x20]), local: nil, own: own), false)
        // IPv4 dentro de IPv6.
        try expect(LanPeer.allowed(peer: v6([], [0xFF, 0xFF, 192, 168, 1, 30]), local: nil, own: own), true)
        try expect(LanPeer.allowed(peer: v6([], [0xFF, 0xFF, 192, 168, 1, 20]), local: nil, own: own), false)
        try expect(LanPeer.allowed(peer: v6([], [0xFF, 0xFF, 127, 0, 0, 1]), local: nil, own: []), false)
    }

    // MARK: Lista de canais

    private static let phonePlaylist = Playlist(id: "p1", type: .m3u, name: "A minha lista", url: "https://example.com/list.m3u")
    private static let phoneGroups = (
        news: Category(id: "news", name: "Notícias"), sport: Category(id: "sport", name: "Desporto"),
        adult: Category(id: "adult", name: "Adultos"), kids: Category(id: "kids", name: "Infantis")
    )
    private static let phoneChannels = [
        Channel(uid: 0, key: "n1", num: 1, name: "RTP 1", logo: "https://x/rtp1.png", categoryId: "news"),
        Channel(uid: 1, key: "n2", num: 2, name: "SIC <Notícias>", logo: nil, categoryId: "news"),
        Channel(uid: 2, key: "s1", num: 3, name: "Sport TV", logo: "logo.png", categoryId: "sport"),
        Channel(uid: 3, key: "a1", num: 4, name: "Privado", logo: "https://x/a1.png", categoryId: "adult"),
        Channel(uid: 4, key: "k1", num: 5, name: "Panda", logo: "https://x/panda.png", categoryId: "kids"),
        Channel(uid: 5, key: "k2", num: 6, name: "Canal escondido", logo: nil, categoryId: "kids"),
        // A mesma chave noutro grupo: conta só o primeiro canal com ela.
        Channel(uid: 6, key: "n1", num: 7, name: "RTP 1 (cópia)", logo: nil, categoryId: "kids"),
    ]
    private static let phoneContent = PlaylistContent.build(
        playlist: phonePlaylist, categories: [phoneGroups.news, phoneGroups.sport, phoneGroups.adult, phoneGroups.kids],
        channels: phoneChannels, guideUrl: nil
    )
    private static var phonePrefs: Prefs {
        var p = Prefs()
        p.hiddenGroups = ["p1:sport"]
        p.hiddenChannels = ["p1:k2"]
        p.pinHash = String(repeating: "0", count: 64)
        p.lockedGroups = ["p1:adult"]
        return p
    }

    /// Os grupos como o ecrã os dá (aqui também os escondidos e bloqueados: a lista tira-os na mesma).
    private static func phoneList(prefs: Prefs = CoreTests.phonePrefs, current: String? = "k1", order: [Category]? = nil,
                                  logos: [String: String] = [:]) -> PhoneChannelList {
        let g = phoneGroups
        return PhoneChannelList(
            playlist: phonePlaylist,
            groups: (order ?? [g.kids, g.news, g.sport, g.adult]).map { PhoneChannelList.Group($0, phoneContent.byCategory[$0.id] ?? []) },
            prefs: prefs,
            favorites: ["n2", "a1"],
            current: current,
            logos: logos,
            byKey: phoneContent.byKey
        )
    }

    private static func ids(_ json: JSON, _ array: String, _ field: String) -> [String] {
        json[array]?.array?.compactMap { $0[field]?.string } ?? []
    }

    static func phoneNow() throws {
        let json = try JSON.parse(PhoneChannelList.nowJSON(current: "k1", now: EpgEntry(title: "Notícias \"às 8\"", startMs: 1000, endMs: 2000), next: nil))
        try expect(json["current"], "k1")
        try expect(json["now"]?["title"], "Notícias \"às 8\"")
        try expect(json["now"]?["start"]?.int, 1000)
        try expect(json["now"]?["end"]?.int, 2000)
        try expect(json["next"], .null)
        let empty = try JSON.parse(PhoneChannelList.nowJSON(current: nil, now: nil, next: nil))
        try expect(empty["current"], .null)
    }

    static func phoneListLeavesOutHiddenAndLocked() throws {
        let json = try JSON.parse(phoneList().json())
        try expect(json["playlist"]?["id"], "p1")
        try expect(json["playlist"]?["name"], "A minha lista")
        try expect(json["current"], "k1")
        // Pela ordem do ecrã, sem o escondido nem o bloqueado.
        try expect(ids(json, "groups", "id"), ["kids", "news"])
        try expect(json["groups"]?.array?.first?["name"], "Infantis")
        let items = json["channels"]?.array ?? []
        // Sem o canal escondido nem a cópia da chave n1 (aparece uma vez, no seu grupo).
        try expect(ids(json, "channels", "key"), ["k1", "n1", "n2"])
        try expect(ids(json, "channels", "group"), ["kids", "news", "news"])
        try expect(items.compactMap { $0["num"]?.int }, [5, 1, 2])
        try expect(items.count > 2 ? items[2]["name"] : nil, "SIC <Notícias>")
        try expect(items.compactMap { $0["logo"]?.bool }, [true, true, false])
        try expect(items.compactMap { $0["favorite"]?.bool }, [false, false, true])
        try expect(json["truncated"], nil)
    }

    static func phoneListLockedEvenWithoutPin() throws {
        // O bloqueio conta tal como está nas definições: nem um canal do grupo nem o canal a ver.
        var prefs = phonePrefs
        prefs.hiddenGroups = []
        let json = try JSON.parse(phoneList(prefs: prefs, current: "a1").json())
        try expect(ids(json, "groups", "id"), ["kids", "news", "sport"])
        try expect(json["current"], .null)
        try expect(ids(json, "channels", "key").contains("a1"), false)
        let p = phonePrefs
        try expect(PhoneChannelList.allowed(p, "p1", phoneChannels[3]), false)
        try expect(PhoneChannelList.allowed(p, "p1", phoneChannels[2]), false)
        try expect(PhoneChannelList.allowed(p, "p1", phoneChannels[5]), false)
        try expect(PhoneChannelList.allowed(p, "p1", phoneChannels[4]), true)
    }

    static func phoneListNoCurrent() throws {
        try expect(try JSON.parse(phoneList(current: nil).json())["current"], .null)
        try expect(try JSON.parse(phoneList(current: "gone").json())["current"], .null)
    }

    static func phoneListLogos() throws {
        // Um logótipo escolhido (http) substitui o da lista; um caminho relativo não se consegue carregar.
        try expect(PhoneChannelList.logoURL(phoneChannels[2], logos: ["s1": "https://y/sport.png"]), "https://y/sport.png")
        try expect(PhoneChannelList.logoURL(phoneChannels[2], logos: [:]), nil)
        try expect(PhoneChannelList.logoURL(phoneChannels[1], logos: [:]), nil)
        try expect(PhoneChannelList.logoURL(phoneChannels[0], logos: ["n1": "file:///sdcard/x.png"]), nil)
        // "" é o ecrã sem logótipo (as iniciais), mesmo que a lista tenha um.
        try expect(PhoneChannelList.logoURL(phoneChannels[0], logos: ["n1": ""]), nil)
        let json = try JSON.parse(phoneList(logos: ["n2": "http://y/sic.png"]).json())
        try expect(json["channels"]?.array?.last?["logo"], true)
        // O tipo da imagem: o do fornecedor, ou PNG pelos bytes.
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0])
        try expect(PhoneChannelList.logoType(contentType: "Image/JPEG; charset=x", bytes: Data()), "image/jpeg")
        try expect(PhoneChannelList.logoType(contentType: "image/", bytes: png), "image/png")
        try expect(PhoneChannelList.logoType(contentType: "text/html", bytes: png), "image/png")
        try expect(PhoneChannelList.logoType(contentType: nil, bytes: Data("<html>".utf8)), nil)
    }

    static func phoneListCap() throws {
        let full = phoneList().json()
        let cut = phoneList().json(maxBytes: full.count - 10)
        try expect(cut.count <= full.count - 10, true)
        let json = try JSON.parse(cut)
        try expect(json["truncated"], true)
        try expect(json["channels"]?.array?.count, 2)
        // Nem um canal cabe: a lista vai vazia, mas continua a ser JSON.
        let empty = try JSON.parse(phoneList().json(maxBytes: 10))
        try expect(empty["channels"]?.array?.count, 0)
        try expect(empty["truncated"], true)
        // Os textos vão bem escapados.
        try expect(PhoneChannelList.quote("a\"b\\c\n\u{1}é <"), "\"a\\\"b\\\\c\\n\\u0001é <\"")
    }

    static func phoneKeys() throws {
        try expect(PhoneChannelList.decodeKey("k1"), "k1")
        try expect(PhoneChannelList.decodeKey("a%20b%2Fc+d"), "a b/c+d")
        try expect(PhoneChannelList.decodeKey("canal%20%C3%A7"), "canal ç")
        // Bytes UTF-8 sem codificar (o pedido é lido byte a byte).
        try expect(PhoneChannelList.decodeKey("Ã§"), "ç")
        for bad in ["", "%", "%2", "%zz", "%+1", "%C3", "%FF", "ć"] { try expect(PhoneChannelList.decodeKey(bad), nil, bad) }
    }
}
