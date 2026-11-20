import Foundation
import GoatShared
import os

/// A qualidade do HLS do canal principal, entre o leitor (HlsReader) e o menu, como o HlsQualityControl do Android:
/// - a definição ([preference], o `hls_quality` da conta);
/// - a escolha à mão no leitor ([pin]), só para a playlist que estava a dar e até se mudar de canal ([clearPin]);
/// - o que está a dar ([now]), para o menu e a informação do stream.
/// As contas (o plano, os nomes, o menu) são as partilhadas (GoatShared.HlsQuality). O segundo canal segue só a
/// definição. Muda em qualquer thread; [changed] avisa na thread principal.
public final class HlsQualityControl: @unchecked Sendable {
    public static let shared = HlsQualityControl()
    /// Mudou o que está a dar (ou a escolha à mão).
    public static let changed = Notification.Name("HlsQualityControl.changed")

    /// O que o leitor do canal principal está a ler: o nome de cada qualidade (pela ordem do leitor), a atual e o menu.
    public struct Now: Sendable {
        public let url: String
        public let labels: [String]
        public let level: Int
        /// A escolha à mão (índice em [labels]); -1 = Automático (a definição).
        public let pinned: Int
        /// O menu, da mais alta à mais baixa, sem nomes repetidos; vazio com menos de duas.
        public let options: [Option]

        public struct Option: Sendable {
            public let index: Int
            public let label: String
        }

        /// O nome da qualidade a dar.
        public var label: String? { labels.indices.contains(level) ? labels[level] : nil }
        /// A linha do menu escolhida (pelo nome, que o menu não repete); -1 = Automático.
        public var selected: Int {
            guard labels.indices.contains(pinned) else { return -1 }
            let name = labels[pinned]
            return options.first { $0.label == name }?.index ?? -1
        }
    }

    private struct State: Sendable {
        var preference = "auto"
        var version = 0
        var pinnedUrl: String?
        var pinned = -1
        var now: Now?
        var owner: ObjectIdentifier?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    private init() {}

    /// A definição: "auto", "best", "data_saver" ou "lowest".
    public var preference: String {
        get { state.withLock { $0.preference } }
        set {
            state.withLock { (s: inout State) in
                guard s.preference != newValue else { return }
                s.preference = newValue
                s.version += 1
            }
        }
    }

    /// Muda sempre que a definição ou a escolha à mão mudam: o leitor volta a fazer o plano.
    public var version: Int { state.withLock { $0.version } }

    public var now: Now? { state.withLock { $0.now } }

    /// O plano para [url] ([variants] já pela ordem de HlsQuality.ordered); [primary]: o canal principal.
    public func plan(url: String, variants: [HlsVariant], primary: Bool) -> HlsQualityPlan {
        let (preference, pin) = state.withLock { (s: inout State) -> (String, Int) in
            (s.preference, primary && s.pinnedUrl == url ? s.pinned : -1)
        }
        return HlsQuality.shared.plan(ordered: variants, preference: preference, pinned: Int32(pin))
    }

    /// Fixa a qualidade [index] (de [Now.labels]) no que está a dar; -1 volta ao Automático.
    public func pin(_ index: Int) {
        let changed = state.withLock { (s: inout State) -> Bool in
            guard let n = s.now else { return false }
            s.pinnedUrl = index >= 0 ? n.url : nil
            s.pinned = index
            s.version += 1
            s.now = Now(url: n.url, labels: n.labels, level: n.level, pinned: index, options: n.options)
            return true
        }
        if changed { notify() }
    }

    /// Mudou-se de canal: a escolha à mão acaba.
    public func clearPin() {
        state.withLock { (s: inout State) in
            guard s.pinned >= 0 else { return }
            s.pinnedUrl = nil
            s.pinned = -1
            s.version += 1
        }
    }

    /// O leitor do canal principal ([owner]) está a ler a qualidade [level] de [variants].
    func publish(owner: AnyObject, url: String, variants: [HlsVariant], level: Int) {
        let labels = variants.enumerated().map { HlsQuality.shared.label(v: $0.element, index: Int32($0.offset)) }
        let options = HlsQuality.shared.menu(ordered: variants).map { Now.Option(index: Int($0.index), label: $0.label) }
        let id = ObjectIdentifier(owner)
        state.withLock { (s: inout State) in
            let pin = s.pinnedUrl == url ? s.pinned : -1
            s.now = Now(url: url, labels: labels, level: level, pinned: pin, options: options)
            s.owner = id
        }
        notify()
    }

    /// O leitor [owner] parou: se era o que estava a dar, deixa de haver qualidade a mostrar.
    func closed(owner: AnyObject) {
        let id = ObjectIdentifier(owner)
        let was = state.withLock { (s: inout State) -> Bool in
            guard s.owner == id else { return false }
            s.now = nil
            s.owner = nil
            return true
        }
        if was { notify() }
    }

    private func notify() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: Self.changed, object: nil) }
    }
}
