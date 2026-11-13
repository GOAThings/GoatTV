import Foundation

/// Saltar dentro de um TS gravado (as gravações no disco, o catch-up do Stalker) no motor GoatCore, como no Android
/// (TsSeek.kt):
/// 1. lê-se o primeiro e o último PTS (o início e o fim do ficheiro);
/// 2. estima-se o byte pela proporção;
/// 3. corrige-se uma vez com o PTS que lá estiver.
/// Cada leitura é uma de cada vez (o fornecedor pode só deixar uma ligação), fora da thread principal.
public enum TsSeek {
    /// Onde abrir ([position], em bytes) e o PTS do início do ficheiro ([firstPts], 90 kHz) para contar a posição.
    public struct Plan: Equatable, Sendable {
        public let position: Int64
        public let firstPts: Int64
    }

    public static let probe = 1024 * 1024
    private static let mask: Int64 = 0x1_FFFF_FFFF

    /// O plano para chegar a [startMs] desde o início de um ficheiro com [length] bytes; [read] lê um pedaço
    /// (posição, tamanho). nil se não der (sem PTS).
    public static func plan(length: Int64, startMs: Int64, read: (Int64, Int) throws -> Data) rethrows -> Plan? {
        guard length > 0, let first = pts(in: try read(0, probe), last: false) else { return nil }
        guard startMs > 0, length > 2 * Int64(probe) else { return Plan(position: 0, firstPts: first) }
        guard let last = pts(in: try read(length - Int64(probe), probe), last: true) else { return Plan(position: 0, firstPts: first) }
        let span = (last - first) & mask
        guard span > 0 else { return Plan(position: 0, firstPts: first) }
        let target = startMs * 90
        func at(_ t: Int64) -> Int64 { min(max(0, Int64(Double(length) * Double(t) / Double(span))), length - Int64(probe)) }
        var position = at(target)
        // Uma correção: o débito não é constante, e a proporção do sítio onde caiu acerta o resto.
        if let found = pts(in: try read(position, probe), last: false) {
            let reached = (found - first) & mask
            if reached > 0, reached < span { position = at(target + (target - reached)) }
        }
        return Plan(position: position / 188 * 188, firstPts: first)
    }

    /// A posição (ms) de [pts] (90 kHz, no tempo seguido do motor, que começa no PTS cru) desde [firstPts].
    public static func positionMs(pts: Int64, firstPts: Int64) -> Int64 { ((pts - firstPts) & mask) / 90 }

    /// O PTS do vídeo (ou, sem vídeo, de qualquer PES) em [data]: o mais baixo das primeiras imagens, ou o mais alto de
    /// todas com [last] (com imagens B a ordem não é a da apresentação). Não precisa do PMT: o stream_id do PES diz
    /// se é vídeo (0xE0–0xEF).
    public static func pts(in data: Data, last: Bool) -> Int64? {
        let bytes = [UInt8](data)
        guard let start = sync(bytes) else { return nil }
        var video: [Int64] = []
        var other: [Int64] = []
        var i = start
        while i + 188 <= bytes.count {
            defer { i += 188 }
            guard bytes[i] == 0x47, bytes[i + 1] & 0x40 != 0 else { continue }
            let afc = (bytes[i + 3] >> 4) & 3
            guard afc & 1 != 0 else { continue }
            var p = i + 4
            if afc & 2 != 0 { p += 1 + Int(bytes[i + 4]) }
            guard p + 14 <= i + 188, bytes[p] == 0, bytes[p + 1] == 0, bytes[p + 2] == 1 else { continue }
            let stream = bytes[p + 3]
            guard bytes[p + 7] & 0x80 != 0 else { continue }
            let q = p + 9
            // Aos bocados: numa expressão só, o compilador de Swift desiste.
            let b0: Int64 = (Int64(bytes[q]) >> 1) & 7
            let b1: Int64 = Int64(bytes[q + 1])
            let b2: Int64 = Int64(bytes[q + 2]) >> 1
            let b3: Int64 = Int64(bytes[q + 3])
            let b4: Int64 = Int64(bytes[q + 4]) >> 1
            var v: Int64 = b0 << 30
            v |= b1 << 22
            v |= b2 << 15
            v |= b3 << 7
            v |= b4
            if (0xE0...0xEF).contains(stream) { video.append(v) } else { other.append(v) }
        }
        let list = video.isEmpty ? other : video
        guard !list.isEmpty else { return nil }
        return last ? list.max() : list.prefix(8).min()
    }

    /// O primeiro byte de um pacote (0x47 seguido de outro 0x47 188 bytes depois).
    private static func sync(_ b: [UInt8]) -> Int? {
        var i = 0
        while i + 188 < b.count {
            if b[i] == 0x47, b[i + 188] == 0x47 { return i }
            i += 1
        }
        return nil
    }
}
