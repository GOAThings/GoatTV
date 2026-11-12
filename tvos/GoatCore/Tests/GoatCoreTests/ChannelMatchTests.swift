import Foundation
@testable import GoatCore

/// O "mesmo canal" na lista de reserva: os casos do GoatTV Android (app/src/test/resources/channel_match.json), lidos
/// do próprio ficheiro, para as duas plataformas escolherem sempre o mesmo canal.
extension CoreTests {
    /// O ficheiro dos casos: ao lado deste teste no repositório (Xcode) ou a partir da pasta tvos/GoatCore (CI em Linux).
    static func channelMatchCases() throws -> JSON {
        let relative = "app/src/test/resources/channel_match.json"
        var candidates: [URL] = []
        let here = URL(fileURLWithPath: #filePath)
        candidates.append(here.deletingLastPathComponent().appendingPathComponent("../../../../\(relative)"))
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        candidates.append(cwd.appendingPathComponent("../../\(relative)"))
        candidates.append(cwd.appendingPathComponent(relative))
        if let workspace = ProcessInfo.processInfo.environment["GITHUB_WORKSPACE"] {
            candidates.append(URL(fileURLWithPath: workspace).appendingPathComponent(relative))
        }
        for url in candidates {
            if let data = try? Data(contentsOf: url.standardizedFileURL) { return try JSON.parse(data) }
        }
        throw Failure(description: "channel_match.json não encontrado (\(candidates.map(\.path)))")
    }

    /// A lista de teste do backend (app/src/test/resources/organize.m3u, a mesma do Android e do site): as chaves e os
    /// grupos dos canais têm de sair iguais, também com fins de linha \r\n ou \r.
    static func m3uKeysMatchTheBackendFixture() throws {
        let relative = "app/src/test/resources/organize.m3u"
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../\(relative)")
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("../../\(relative)")
        guard let data = [here, cwd].lazy.compactMap({ try? Data(contentsOf: $0.standardizedFileURL) }).first else {
            throw Failure(description: "organize.m3u não encontrado")
        }
        let expected: [(String, String)] = [
            ("SIC.pt", "Portugal"), ("ueaaa29a28652", "Portugal"), ("u7aa953b3260a", "Portugal"), ("u6a4815eaa311", "Portugal"),
            ("ESPN.us", "Sports, US"), ("Euronews.fr", PlaylistContent.others), ("ud319eeb165a2", PlaylistContent.others),
            ("u6c2602101058", "Sports, US"), ("Kids1", "Infantil"), ("Kids2", "Infantil"), ("News1", "Notícias"),
            ("Docs1", "Documentários"), ("NoGrp", PlaylistContent.others),
        ]
        let text = String(decoding: data, as: UTF8.self)
        for (label, variant) in [("lf", text), ("crlf", text.replacingOccurrences(of: "\n", with: "\r\n")),
                                 ("cr", text.replacingOccurrences(of: "\n", with: "\r"))] {
            let r = M3UParser.parse(Data(variant.utf8))
            try expect(r.channels.map(\.key), expected.map(\.0), label)
            try expect(r.channels.map(\.categoryId), expected.map(\.1), label)
            try expect(r.channels.count > 4 ? r.channels[4].name : "", "ESPN, East", label)
        }
    }

    private static func entries(_ o: JSON, _ key: String) -> [ChannelMatch.Entry] {
        (o[key]?.array ?? []).enumerated().map { i, e in
            ChannelMatch.Entry(key: e["key"]?.string ?? "k\(i)", name: e["name"]?.string ?? "", epgId: e["epg"]?.string, group: e["group"]?.string)
        }
    }

    static func channelMatchNormalize() throws {
        let list = try channelMatchCases()["normalize"]?.array ?? []
        try expect(list.count >= 50, true, "casos de normalize")
        for c in list {
            let name = c["name"]?.string ?? ""
            let expected = ChannelMatch.Parsed(base: c["base"]?.string ?? "", country: c["country"]?.string,
                                               quality: c["quality"]?.int ?? -1, shift: c["shift"]?.int ?? -1)
            try expect(ChannelMatch.parse(name), expected, name)
        }
    }

    static func channelMatchFind() throws {
        let list = try channelMatchCases()["match"]?.array ?? []
        try expect(list.count >= 30, true, "casos de match")
        for c in list {
            let label = c["case"]?.string ?? ""
            let t = c["target"] ?? .null
            let target = ChannelMatch.Entry(key: "main", name: t["name"]?.string ?? "", epgId: t["epg"]?.string, group: t["group"]?.string)
            let result = ChannelMatch.decide(target, ChannelMatcher(entries(c, "pool")), override: c["override"]?.string, manual: c["manual"]?.bool == true)
            let kind: String
            switch result {
            case .match: kind = "match"
            case .unsure: kind = "unsure"
            case .missing: kind = "missing"
            }
            try expect(kind, c["kind"]?.string ?? "", label)
            try expect(result.index, c["expect"]?.int, label)
        }
    }

    static func channelMatchCoverage() throws {
        for c in try channelMatchCases()["coverage"]?.array ?? [] {
            let label = c["case"]?.string ?? ""
            let overrides = overrides(c)
            let cov = ChannelMatch.coverage(entries(c, "main"), ChannelMatcher(entries(c, "pool")), overrides: overrides, manual: c["manual"]?.bool == true)
            try expect(cov.total, c["main"]?.array?.count ?? -1, label)
            try expect(cov.matched, c["matched"]?.int ?? -1, label)
            try expect(cov.unsure, c["unsure"]?.int ?? -1, label)
            try expect(cov.missing, c["missing"]?.int ?? -1, label)
            try expect(cov.unsureNames, (c["unsureNames"]?.array ?? []).compactMap(\.string), label)
            try expect(cov.missingNames, (c["missingNames"]?.array ?? []).compactMap(\.string), label)
        }
    }

    static func channelMatchRanked() throws {
        for c in try channelMatchCases()["ranked"]?.array ?? [] {
            let t = c["target"] ?? .null
            let ranked = ChannelMatcher(entries(c, "pool")).ranked(t["name"]?.string ?? "", epgId: t["epg"]?.string, limit: c["limit"]?.int ?? 8)
            try expect(ranked, (c["expect"]?.array ?? []).compactMap(\.int), c["case"]?.string ?? "")
        }
        try expect(ChannelMatch.levenshtein("kitten", "sitting"), 3)
        try expect(ChannelMatch.levenshtein("", "sic"), 3)
    }

    static func channelMatchReview() throws {
        for c in try channelMatchCases()["review"]?.array ?? [] {
            let label = c["case"]?.string ?? ""
            let overrides = overrides(c)
            let rows = ChannelMatch.review(entries(c, "main"), ChannelMatcher(entries(c, "pool")), overrides: overrides, manual: c["manual"]?.bool == true)
            let expected = (c["expect"]?.array ?? []).map { r -> String in
                let a = r.array ?? []
                return "\(a[0].string ?? "") \(a[1].int.map(String.init) ?? "-") \(a[2].int.map(String.init) ?? "-")"
            }
            try expect(rows.map { "\($0.state.rawValue) \($0.backup.map(String.init) ?? "-") \($0.suggestion.map(String.init) ?? "-")" }, expected, label)
            try expect(rows.map(\.main), Array(rows.indices), label)
            if let failovers = c["failovers"]?.array {
                try expect(rows.map(\.failovers), failovers.map { $0.array?.compactMap(\.string) ?? [] }, "\(label)/failovers")
            }
            for (name, count) in c["filters"]?.object ?? [:] {
                let filter = ChannelMatch.Filter(rawValue: name)
                try expect(filter != nil, true, "\(label)/\(name)")
                try expect(rows.filter { $0.passes(filter!) }.count, count.int ?? -1, "\(label)/\(name)")
            }
        }
    }

    /// As escolhas à mão de um caso: uma chave, "off" (nenhum) ou um array de failovers, como no `settings`.
    private static func overrides(_ c: JSON) -> [String: [String]] {
        (c["overrides"]?.object ?? [:]).compactMapValues { v in
            if let list = v.array { return list.compactMap(\.string) }
            return v.string.map { $0 == ChannelMatch.off ? [] : [$0] }
        }
    }

    static func channelMatchFailovers() throws {
        for c in try channelMatchCases()["failovers"]?.array ?? [] {
            let label = c["case"]?.string ?? ""
            let t = c["target"]
            let target = ChannelMatch.Entry(key: t?["key"]?.string ?? "main", name: t?["name"]?.string ?? "", epgId: t?["epg"]?.string, group: t?["group"]?.string)
            let mainKeys = Set((c["mainKeys"]?.array ?? []).compactMap(\.string))
            let chosen = c["chosen"]?.array?.compactMap(\.string)
            let variants = (c["variants"]?.array ?? []).compactMap(\.string)
            let result = ChannelMatch.failovers(target, ChannelMatcher(entries(c, "pool")), chosen: chosen, manual: c["manual"]?.bool == true,
                                                variants: variants, inMain: mainKeys.contains)
            try expect(result, (c["expect"]?.array ?? []).compactMap(\.string), label)
        }
    }

    static func channelNames() throws {
        for c in try channelMatchCases()["names"]?.array ?? [] {
            let rules = (c["rules"]?.array ?? []).map { NameRule(find: $0["find"]?.string ?? "", replace: $0["replace"]?.string ?? "") }
            try expect(ChannelNames.apply(c["name"]?.string ?? "", rules), c["expect"]?.string ?? "", c["case"]?.string ?? "")
        }
    }

    static func channelVariants() throws {
        for c in try channelMatchCases()["variants"]?.array ?? [] {
            let channels = (c["channels"]?.array ?? []).map { (key: $0["key"]?.string ?? "", name: $0["name"]?.string ?? "") }
            let expected = (c["expect"]?.object ?? [:]).mapValues { $0.array?.compactMap(\.string) ?? [] }
            try expect(ChannelVariants.of(channels), expected, c["case"]?.string ?? "")
        }
    }
}
