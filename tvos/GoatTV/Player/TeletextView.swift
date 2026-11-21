import GoatCore
import Observation
import SwiftUI

/// O teletexto a ver (o mesmo no Apple TV e no iPhone, como o TeletextController do Android): a página, a subpágina,
/// o número a meio de escrever, o fundo e o revelar. Quem mostra chama [poll] a cada 250 ms com o teletexto do canal a
/// dar (as páginas chegam a rodar) e [detach] ao fechar.
@MainActor
@Observable
final class TeletextModel {
    static let startPage = 100
    static let red = 10, green = 11, yellow = 12, blue = 13, background = 14, reveal = 15
    /// O teclado no ecrã, 4×4: os algarismos, as cores à direita, o fundo e o revelar ao lado do 0 (como no Android).
    static let pad = [1, 2, 3, red, 4, 5, 6, green, 7, 8, 9, yellow, background, 0, reveal, blue]
    static let padColumns = 4
    /// O fundo fica como estava da última vez (enquanto a app está aberta).
    nonisolated(unsafe) private static var lastTransparent = false

    private(set) var page = 100
    /// A subpágina escolhida (-1 = a última que chegou, a rodar como numa TV).
    private(set) var subpage = -1
    private(set) var typed = ""
    private(set) var transparent = false
    private(set) var revealed = false
    private(set) var frame: TeletextFrame?
    /// Pisca: o texto com FLASH vê-se nesta fase.
    private(set) var flashOn = true
    /// No Apple TV: o teclado no ecrã (o comando não tem números nem cores) e a tecla com o cursor.
    var padOpen = false
    var padIndex = 0
    @ObservationIgnored private var feed: TeletextFeed?

    init() {
        transparent = Self.lastTransparent
    }

    private var label: String { typed.isEmpty ? "" : typed.padding(toLength: 3, withPad: "-", startingAt: 0) }

    /// O teletexto do canal a dar (muda com o canal): refaz a página.
    func poll(_ feed: TeletextFeed?) {
        if feed !== self.feed {
            self.feed?.viewing = false
            self.feed = feed
        }
        flashOn = (Int(Date().timeIntervalSince1970 * 1000) / 333) % 3 != 2
        refresh()
    }

    func detach() {
        feed?.viewing = false
        feed = nil
        padOpen = false
    }

    func refresh() {
        guard let feed else {
            frame = nil
            return
        }
        feed.viewing = true
        frame = feed.frame(page: page, subpage: subpage, label: label)
    }

    /// Um algarismo do número da página: ao terceiro, vai para ela (o primeiro só pode ser 1–8).
    func digit(_ d: Int) {
        if typed.isEmpty, !(1...8).contains(d) { return }
        typed += String(d)
        if typed.count == 3, let n = Int(typed) { go(n) } else { refresh() }
    }

    func clearTyped() {
        typed = ""
        refresh()
    }

    func go(_ target: Int) {
        page = TeletextFeed.wrap(target)
        subpage = -1
        typed = ""
        refresh()
    }

    /// ↑/↓: a página seguinte ou a anterior das que já chegaram.
    func step(_ direction: Int) {
        go(feed?.nextPage(page, step: direction) ?? TeletextFeed.wrap(page + direction))
    }

    /// ←/→: a subpágina ao lado (à volta).
    func sub(_ direction: Int) {
        guard let frame, frame.subpages > 1 else { return }
        let current = subpage >= 0 ? subpage : max(frame.subpage, 0)
        subpage = (current + direction + frame.subpages) % frame.subpages
        refresh()
    }

    /// As teclas de cor: a ligação do FastText da página (sem ela, nada).
    func colour(_ index: Int) {
        guard let link = frame?.links[index], (100...899).contains(link) else { return }
        go(link)
    }

    func toggleBackground() {
        transparent.toggle()
        Self.lastTransparent = transparent
    }

    func toggleReveal() { revealed.toggle() }

    /// A tecla [index] do teclado no ecrã.
    func press(_ index: Int) {
        let key = Self.pad[index]
        switch key {
        case 0...9: digit(key)
        case Self.red, Self.green, Self.yellow, Self.blue: colour(key - Self.red)
        case Self.background: toggleBackground()
        case Self.reveal: toggleReveal()
        default: break
        }
    }

    /// As setas no teclado no ecrã: andam pelas teclas (sem dar a volta).
    func movePad(dx: Int, dy: Int) {
        let columns = Self.padColumns
        let row = padIndex / columns + dy
        let column = padIndex % columns + dx
        guard row >= 0, row < Self.pad.count / columns, column >= 0, column < columns else { return }
        padIndex = row * columns + column
    }
}

/// As 8 cores do teletexto (preto, vermelho, verde, amarelo, azul, magenta, ciano, branco).
let teletextPalette: [Color] = [
    Color(red: 0, green: 0, blue: 0), Color(red: 1, green: 0, blue: 0), Color(red: 0, green: 1, blue: 0), Color(red: 1, green: 1, blue: 0),
    Color(red: 0, green: 0, blue: 1), Color(red: 1, green: 0, blue: 1), Color(red: 0, green: 1, blue: 1), Color(red: 1, green: 1, blue: 1),
]

/// As cores das teclas do FastText.
let teletextKeyColours: [Color] = [
    Color(red: 0.90, green: 0.22, blue: 0.21), Color(red: 0.26, green: 0.63, blue: 0.28),
    Color(red: 0.99, green: 0.85, blue: 0.21), Color(red: 0.12, green: 0.53, blue: 0.90),
]

/// A página numa grelha de 40×25 células, em 4:3, o maior possível dentro do espaço (ao centro). No fundo
/// transparente (e nas legendas e notícias, que só mostram as caixas) o preto não se pinta e o texto leva sombra.
struct TeletextCanvas: View {
    let model: TeletextModel
    /// O "à espera da página NNN", já traduzido.
    let waiting: String

    var body: some View {
        GeometryReader { geo in
            let width = min(geo.size.width, geo.size.height * 4 / 3)
            ZStack {
                if let frame = model.frame {
                    let clear = model.transparent || frame.boxed
                    let reveal = model.revealed
                    let flashOn = model.flashOn
                    Canvas { context, size in
                        Self.draw(frame, in: &context, size: size, clear: clear, reveal: reveal, flashOn: flashOn)
                    }
                }
                if model.frame?.found != true {
                    Text(waiting)
                        .font(.system(size: max(11, min(width / 36, 30)), weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private static func draw(_ frame: TeletextFrame, in context: inout GraphicsContext, size: CGSize, clear: Bool, reveal: Bool, flashOn: Bool) {
        let gridW = min(size.width, size.height * 4 / 3)
        let gridH = gridW * 3 / 4
        let ox = (size.width - gridW) / 2
        let oy = (size.height - gridH) / 2
        let cw = gridW / CGFloat(TeletextFrame.columns)
        let ch = gridH / CGFloat(TeletextFrame.rows)
        if !clear { context.fill(Path(CGRect(x: ox, y: oy, width: gridW, height: gridH)), with: .color(.black)) }
        let fontSize = ch * 0.92
        let font = Font.system(size: fontSize, design: .monospaced)
        // O tipo monoespaçado tem ~0,6 da altura de largura: estica-se para encher a célula, como numa TV.
        let stretch = min(1.6, cw / (fontSize * 0.6))
        for r in 0..<TeletextFrame.rows {
            for c in 0..<TeletextFrame.columns {
                let cell = frame.cell(r, c)
                let attrs = cell.attributes
                if attrs & TeletextFrame.hidden != 0 { continue }
                let rect = CGRect(x: ox + CGFloat(c) * cw, y: oy + CGFloat(r) * ch, width: cw, height: ch)
                if !(cell.background == 0 && clear) {
                    context.fill(Path(rect.insetBy(dx: -0.25, dy: -0.25)), with: .color(teletextPalette[cell.background]))
                }
                if attrs & TeletextFrame.flash != 0, !flashOn { continue }
                if attrs & TeletextFrame.conceal != 0, !reveal { continue }
                if cell.mosaic <= 0, cell.text.trimmingCharacters(in: .whitespaces).isEmpty { continue }
                var cellContext = context
                cellContext.clip(to: Path(rect))
                // Altura dupla: desenha-se esticado a partir da linha de cima, e cada linha mostra a sua metade.
                var top = rect.minY
                if attrs & TeletextFrame.doubleTop != 0 || attrs & TeletextFrame.doubleBottom != 0 {
                    if attrs & TeletextFrame.doubleBottom != 0 { top = rect.minY - ch }
                    cellContext.translateBy(x: 0, y: top)
                    cellContext.scaleBy(x: 1, y: 2)
                    cellContext.translateBy(x: 0, y: -top)
                }
                let colour = teletextPalette[cell.foreground]
                if cell.mosaic > 0 {
                    let separated = attrs & TeletextFrame.separated != 0
                    let bw = cw / 2
                    let bh = ch / 3
                    let gap = separated ? min(bw, bh) * 0.2 : 0
                    for bit in 0..<6 where (cell.mosaic >> bit) & 1 == 1 {
                        let block = CGRect(x: rect.minX + CGFloat(bit % 2) * bw + gap, y: top + CGFloat(bit / 2) * bh,
                                           width: bw - gap + (separated ? 0 : 0.5), height: bh - gap + (separated ? 0 : 0.5))
                        cellContext.fill(Path(block), with: .color(colour))
                    }
                } else {
                    if clear { cellContext.addFilter(.shadow(color: .black, radius: ch * 0.12)) }
                    cellContext.translateBy(x: rect.midX, y: 0)
                    cellContext.scaleBy(x: stretch, y: 1)
                    cellContext.draw(Text(cell.text).font(font).foregroundColor(colour), at: CGPoint(x: 0, y: top + ch / 2), anchor: .center)
                }
            }
        }
    }
}
