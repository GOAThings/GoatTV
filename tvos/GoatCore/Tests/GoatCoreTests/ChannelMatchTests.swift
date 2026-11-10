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

    private static func entries(_ o: JSON, _ key: String) -> [ChannelMatch.Entry] {
        (o[key]?.array ?? []).enumerated().map { i, e in
            ChannelMatch.Entry(key: e["key"]?.string ?? "k\(i)", name: e["name"]?.string ?? "", epgId: e["epg"]?.string)
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
            let result = ChannelMatcher(entries(c, "pool")).find(t["name"]?.string ?? "", epgId: t["epg"]?.string, override: c["override"]?.string)
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
            let overrides = (c["overrides"]?.object ?? [:]).compactMapValues { $0.string }
            let cov = ChannelMatch.coverage(entries(c, "main"), ChannelMatcher(entries(c, "pool")), overrides: overrides)
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
}
