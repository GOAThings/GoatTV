import Foundation
import GoatShared

/// Uma célula de uma página do teletexto: o texto (ou o mosaico), as cores (0–7) e os atributos (ver TeletextFrame).
public struct TeletextCell: Sendable {
    /// Um carácter (às vezes com um acento a seguir); vazio num mosaico.
    public let text: String
    /// Os 6 blocos do mosaico (bit 0 em cima à esquerda … bit 5 em baixo à direita); -1 = texto.
    public let mosaic: Int
    public let foreground: Int
    public let background: Int
    public let attributes: Int
}

/// Uma página do teletexto pronta a desenhar (40×25 células, linha × 40 + coluna), tirada do núcleo partilhado
/// (TeletextScreen, o mesmo do Android).
public struct TeletextFrame: Sendable {
    public let page: Int
    /// A página já chegou; senão só o cabeçalho tem alguma coisa ("à espera da página").
    public let found: Bool
    /// A subpágina que se mostra (0…[subpages]−1), -1 sem página.
    public let subpage: Int
    public let subpages: Int
    /// Legendas ou notícia: só as caixas se veem, por cima do vídeo.
    public let boxed: Bool
    public let cells: [TeletextCell]
    /// As páginas das teclas de cor (vermelho, verde, amarelo, azul) pelo FastText; -1 sem ligação.
    public let links: [Int]

    public static let rows = Int(GoatShared.TeletextScreen.companion.ROWS)
    public static let columns = Int(GoatShared.TeletextScreen.companion.COLUMNS)
    public static let flash = Int(GoatShared.TeletextScreen.companion.FLASH)
    public static let conceal = Int(GoatShared.TeletextScreen.companion.CONCEAL)
    public static let boxedCell = Int(GoatShared.TeletextScreen.companion.BOXED)
    public static let doubleTop = Int(GoatShared.TeletextScreen.companion.DOUBLE_TOP)
    public static let doubleBottom = Int(GoatShared.TeletextScreen.companion.DOUBLE_BOTTOM)
    public static let separated = Int(GoatShared.TeletextScreen.companion.SEPARATED)
    public static let hidden = Int(GoatShared.TeletextScreen.companion.HIDDEN)

    public func cell(_ row: Int, _ column: Int) -> TeletextCell { cells[row * Self.columns + column] }
}

/// O teletexto todo do canal (GoatShared.TeletextStore): a fila do motor escreve (ver MediaPipeline), o ecrã lê.
/// Lê-se sempre a faixa do teletexto, com o limite da pré-cache; aberto ([viewing]), guarda até 1000 páginas.
public final class TeletextFeed: @unchecked Sendable {
    private let store = GoatShared.TeletextStore()
    private let lock = NSLock()
    /// O PID da faixa que se lê (-1 sem nenhuma), para a fila do motor não precisar do lock em cada PES.
    private(set) var pid = -1

    public init() {}

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// As faixas do canal (do PMT): escolhe a do teletexto (outra faixa limpa o que havia).
    func tracks(_ list: [TsTrack]) {
        pid = locked { Int(store.tracks(list: list)) }
    }

    /// Um PES da faixa [pid].
    func feed(pid: Int, data: KotlinByteArray) {
        locked { store.feed(fromPid: Int32(pid), pes: data) }
    }

    /// O canal tem teletexto (que não é só legendas): mostra-se a entrada no menu.
    public var available: Bool { locked { store.available } }

    /// O teletexto está aberto (guarda tudo, até ao limite) ou fechado (só a pré-cache).
    public var viewing: Bool {
        get { locked { store.viewing } }
        set { locked { store.viewing = newValue } }
    }

    /// A página a seguir a [page] (+1/−1) entre as que já chegaram.
    public func nextPage(_ page: Int, step: Int) -> Int {
        locked { Int(store.nextPage(page: Int32(page), step: Int32(step))) }
    }

    /// A página [page] (100…899), a subpágina [subpage] (-1 = a última que chegou), com [label] no início do
    /// cabeçalho (o número a meio de escrever; vazio = o da página).
    public func frame(page: Int, subpage: Int, label: String) -> TeletextFrame {
        let s: GoatShared.TeletextScreen = locked { store.screen(page: Int32(page), subpage: Int32(subpage), label: label) }
        var cells: [TeletextCell] = []
        cells.reserveCapacity(TeletextFrame.rows * TeletextFrame.columns)
        for r in 0..<Int32(TeletextFrame.rows) {
            for c in 0..<Int32(TeletextFrame.columns) {
                cells.append(TeletextCell(text: s.text(row: r, column: c), mosaic: Int(s.mosaic(row: r, column: c)),
                                          foreground: Int(s.foreground(row: r, column: c)), background: Int(s.background(row: r, column: c)),
                                          attributes: Int(s.attributes(row: r, column: c))))
            }
        }
        return TeletextFrame(page: Int(s.page), found: s.found, subpage: Int(s.subpage), subpages: Int(s.subpages), boxed: s.boxed,
                             cells: cells, links: (0..<4).map { Int(s.link(index: Int32($0))) })
    }

    /// [page] dentro de 100…899, à volta.
    public static func wrap(_ page: Int) -> Int { Int(GoatShared.TeletextStore.companion.wrap(page: Int32(page))) }
}
