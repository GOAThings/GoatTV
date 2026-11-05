import Foundation

/// Limites e conversões para o que vem dos servidores IPTV e da conta: um valor fora do que faz sentido fica de fora
/// (ou é um pedido falhado), nunca termina a app. Os limites são folgados, porque há fornecedores com listas enormes.
public enum SafeInput {
    /// Respostas JSON (Xtream, Stalker).
    public static let maxPlaylistBytes = 200 * 1024 * 1024
    /// Listas M3U: as m3u_plus trazem filmes e séries às dezenas de milhar, e só ficam os canais.
    public static let maxM3UBytes = 1024 * 1024 * 1024
    /// Guias XMLTV: o corpo e o resultado de o descomprimir (aqui ficam em memória para se lerem).
    public static let maxGuideBytes = 1024 * 1024 * 1024
    public static let maxM3ULine = 4 * 1024 * 1024
    /// Dias de catch-up: dez anos é mais do que qualquer servidor guarda.
    public static let maxArchiveDays = 3650
    /// Ano 3000: uma data para lá disto (ou negativa) não é uma data.
    private static let maxEpochSeconds: Int64 = 32_503_680_000

    /// [value] como Int, ou nil se não for finito ou estiver fora de um intervalo que faça sentido.
    public static func int(_ value: Double) -> Int? {
        value.isFinite && abs(value) < 1e15 ? Int(value) : nil
    }

    /// Um número em texto ("12", "12.0", "1e3"), que os servidores misturam, como Int.
    public static func int(text: String) -> Int? {
        Double(text.trimmingCharacters(in: .whitespaces)).flatMap { int($0) }
    }

    /// Segundos do fornecedor em ms, ou nil fora de um intervalo que faça sentido.
    public static func epochMs(seconds: Int64) -> Int64? {
        (0...maxEpochSeconds).contains(seconds) ? seconds * 1000 : nil
    }

    /// a × b, ou nil se não couber.
    public static func product(_ a: Int, _ b: Int) -> Int? {
        let (value, overflow) = a.multipliedReportingOverflow(by: b)
        return overflow ? nil : value
    }

    /// User-Agent ou Referer de uma lista sem caracteres de controlo nem separadores de linha.
    public static func stripControl(_ value: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            let v = scalar.value
            if v < 0x20 || (0x7F...0x9F).contains(v) || v == 0x2028 || v == 0x2029 { continue }
            out.append(scalar)
        }
        return String(out)
    }
}
