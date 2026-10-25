import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Descarrega listas e guias dos servidores IPTV (com o User-Agent das definições).
public struct PlaylistLoader: Sendable {
    public var session: URLSession
    public var userAgent: String
    /// Descomprime gzip (os guias `.xml.gz`); a app dá a implementação (Compression no tvOS).
    public var gunzip: (@Sendable (Data) -> Data?)?
    /// Nome para um canal sem nome ("Canal 12"), já traduzido.
    public var channelName: @Sendable (String) -> String

    public init(session: URLSession = HTTP.makeSession(timeout: 60), userAgent: String = iptvUserAgent,
                gunzip: (@Sendable (Data) -> Data?)? = nil,
                channelName: @escaping @Sendable (String) -> String = { "Channel \($0)" }) {
        self.session = session
        self.userAgent = userAgent
        self.gunzip = gunzip
        self.channelName = channelName
    }

    public func load(_ playlist: Playlist) async throws -> PlaylistContent {
        switch playlist.type {
        case .m3u: return try await loadM3U(playlist)
        case .xtream: return try await loadXtream(playlist)
        }
    }

    // MARK: M3U

    private func loadM3U(_ playlist: Playlist) async throws -> PlaylistContent {
        guard let raw = playlist.url?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { throw SourceError.noURL }
        guard let url = URL(string: raw), url.scheme != nil else { throw SourceError.badURL }
        let data = try await get(url)
        let name = channelName
        let result = M3UParser.parse(data) { name(String($0)) }
        let epg = playlist.epgUrl.flatMap { $0.trimmed.isEmpty ? nil : $0 } ?? result.epgUrl
        return PlaylistContent.build(playlist: playlist, categories: result.categories, channels: result.channels, guideUrl: epg)
    }

    // MARK: Xtream

    private func loadXtream(_ playlist: Playlist) async throws -> PlaylistContent {
        let loginURL = try Xtream.apiURL(playlist)
        let categoriesURL = try Xtream.apiURL(playlist, action: "get_live_categories")
        let streamsURL = try Xtream.apiURL(playlist, action: "get_live_streams")
        async let loginData = try? get(loginURL)
        async let categoriesData = get(categoriesURL)
        async let streamsData = get(streamsURL)

        let login = await loginData.flatMap(Xtream.parseLogin)
        if login?.auth == "0" { throw SourceError.badLogin }
        if let status = login?.status, status.lowercased() != "active" { throw SourceError.inactive(status) }

        let categories = try Xtream.parseCategories(try await categoriesData)
        let name = channelName
        let channels = try Xtream.parseStreams(try await streamsData) { name($0) }
        let account = login.map {
            XtreamAccount(maxConnections: $0.maxConnections, status: $0.status, allowedFormats: $0.allowedFormats, timezone: $0.timezone)
        }
        // Sem guia próprio, o guia completo do fornecedor (o mesmo XMLTV que os outros players usam).
        let guide = playlist.epgUrl.flatMap { $0.trimmed.isEmpty ? nil : $0 } ?? Xtream.xmltvURL(playlist)
        return PlaylistContent.build(playlist: playlist, categories: categories, channels: channels, account: account, guideUrl: guide)
    }

    /// Os próximos programas de um canal Xtream (`get_short_epg`).
    public func shortEpg(_ playlist: Playlist, streamId: String, limit: Int = 4) async throws -> [EpgEntry] {
        let url = try Xtream.apiURL(playlist, action: "get_short_epg", extra: [("stream_id", streamId), ("limit", String(limit))])
        return Xtream.parseShortEpg(try await get(url))
    }

    /// Ligações ativas e máximo da conta Xtream, ou nil se o servidor não disser.
    public func connections(_ playlist: Playlist) async -> (active: Int, max: Int)? {
        guard let url = try? Xtream.apiURL(playlist), let data = try? await get(url),
              let login = Xtream.parseLogin(data), let active = login.activeConnections, let max = login.maxConnections else { return nil }
        return (active, max)
    }

    // MARK: Guias XMLTV

    /// Descarrega e lê um guia XMLTV (`.xml` ou `.xml.gz`), só com os canais da lista.
    public func loadGuide(url: String, channels: [Channel]) async throws -> XMLTVGuide {
        guard let u = URL(string: url), u.scheme != nil else { throw SourceError.badURL }
        var data = try await get(u, timeout: 120)
        // Muitos guias vêm em .gz sem Content-Encoding: vê-se pelos dois primeiros bytes.
        if data.count > 2, data[data.startIndex] == 0x1F, data[data.startIndex + 1] == 0x8B {
            guard let unzipped = gunzip?(data) else { throw SourceError.badResponse }
            data = unzipped
        }
        try Task.checkCancellation()
        return XMLTVGuide.build(url: url, data: data, channels: channels)
    }

    // MARK: Pedidos

    func get(_ url: URL, timeout: TimeInterval = 60) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue(userAgent.isEmpty ? iptvUserAgent : userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await HTTP.fetch(session, request)
        guard (200..<300).contains(response.statusCode) else { throw SourceError.http(response.statusCode) }
        return data
    }
}

/// Os programas de um canal: o guia da conta (associação feita pela pessoa) ganha, depois o da lista, depois o extra.
public enum Guide {
    public static func entries(for channel: Channel, account: AccountGuide?, main: XMLTVGuide?, extra: XMLTVGuide?) -> [EpgEntry] {
        if let list = account?.programmes[channel.key], !list.isEmpty { return list }
        if let list = main?.entries(for: channel), !list.isEmpty { return list }
        return extra?.entries(for: channel) ?? []
    }

    /// Desvio do guia em minutos (Definições → Guia), para fornecedores com as horas trocadas.
    public static func shifted(_ list: [EpgEntry], minutes: Int) -> [EpgEntry] {
        guard minutes != 0 else { return list }
        let offset = Int64(minutes) * 60_000
        return list.map { EpgEntry(title: $0.title, startMs: $0.startMs + offset, endMs: $0.endMs + offset) }
    }

    /// O programa a dar agora e o seguinte.
    public static func nowNext(_ list: [EpgEntry], now: Int64 = nowMs()) -> (now: EpgEntry?, next: EpgEntry?) {
        guard let i = list.firstIndex(where: { $0.isOn(at: now) }) else {
            return (nil, list.first { $0.startMs > now })
        }
        return (list[i], i + 1 < list.count ? list[i + 1] : nil)
    }
}
