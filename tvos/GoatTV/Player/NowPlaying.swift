import GoatCore
import MediaPlayer
import SwiftUI
import UIKit

/// O canal a dar no centro de controlo, no ecrã bloqueado (iPhone) e na app Comando da Apple TV: o nome, o programa e
/// o logótipo; e os comandos (reproduzir, pausa, canal seguinte e anterior), como a sessão de media do Android.
@MainActor
final class NowPlaying {
    struct Info: Equatable {
        var key: String
        var title: String
        var programme: String?
        var logo: URL?
        var playing: Bool
        var radio: Bool
    }

    var onPlay: (() -> Void)?
    var onPause: (() -> Void)?
    var onNext: (() -> Void)?
    var onPrevious: (() -> Void)?

    private var shown: Info?
    private var artwork: (url: URL, art: MPMediaItemArtwork)?
    private var artTask: Task<Void, Never>?
    private var registered = false

    /// Mostra [info] (nil: nada a dar, sai do centro de controlo).
    func update(_ info: Info?) {
        guard info != shown else { return }
        let logoChanged = info?.logo != shown?.logo
        shown = info
        let center = MPNowPlayingInfoCenter.default()
        guard let info else {
            center.nowPlayingInfo = nil
            return
        }
        register()
        var d: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: info.playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: NSNumber(value: (info.radio ? MPNowPlayingInfoMediaType.audio : MPNowPlayingInfoMediaType.video).rawValue),
        ]
        if let programme = info.programme { d[MPMediaItemPropertyArtist] = programme }
        if let a = artwork, a.url == info.logo { d[MPMediaItemPropertyArtwork] = a.art }
        center.nowPlayingInfo = d
        if logoChanged { loadArtwork(info.logo) }
    }

    /// O logótipo do canal (da cache das imagens, como os da lista), e põe-no quando chegar.
    private func loadArtwork(_ url: URL?) {
        artTask?.cancel()
        guard let url else { return }
        if artwork?.url == url { return }
        artTask = Task { [weak self] in
            guard let loaded = try? await URLSession.shared.data(from: url), let image = UIImage(data: loaded.0) else { return }
            guard !Task.isCancelled, let self else { return }
            let art = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.artwork = (url, art)
            guard self.shown?.logo == url else { return }
            let center = MPNowPlayingInfoCenter.default()
            var d = center.nowPlayingInfo ?? [:]
            d[MPMediaItemPropertyArtwork] = art
            center.nowPlayingInfo = d
        }
    }

    /// Os comandos do sistema (os botões do ecrã bloqueado, dos auriculares, do centro de controlo e da app Comando).
    private func register() {
        guard !registered else { return }
        registered = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onPlay?() }
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onPause?() }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.shown?.playing == true { self.onPause?() } else { self.onPlay?() }
            }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onNext?() }
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onPrevious?() }
            return .success
        }
        // Em direto não há onde saltar (os saltos são os do ecrã, pela pausa do direto ou pelo catch-up).
        c.skipForwardCommand.isEnabled = false
        c.skipBackwardCommand.isEnabled = false
        c.changePlaybackPositionCommand.isEnabled = false
    }
}

extension AppModel {
    /// O canal a dar é uma rádio (só som: ver RadioRules): o ecrã da rádio e, no iPhone, o som continua em segundo plano.
    var isRadio: Bool {
        guard let current else { return false }
        let group = content?.categories.first { $0.id == current.categoryId }?.name
        let audioOnly = player.streamAudioOnly
        return RadioRules.isRadio(hint: RadioRules.hint(current, group: group), tracksKnown: audioOnly != nil,
                                  hasVideo: audioOnly == false, hasAudio: audioOnly == true)
    }

    /// O programa a dar (o guia, ou o do catch-up).
    var currentProgramme: String? {
        guard let current else { return nil }
        return catchup?.title ?? nowNext(for: current).now?.title
    }

    /// O que o centro de controlo mostra; nil sem nada a dar.
    var nowPlayingInfo: NowPlaying.Info? {
        guard let current, player.state != .idle || remoteStopped else { return nil }
        return NowPlaying.Info(key: current.key, title: current.name, programme: currentProgramme, logo: logo(for: current),
                               playing: player.state == .playing && !player.paused && !remoteStopped, radio: isRadio)
    }

    /// Os comandos do sistema vão para o leitor: a pausa é a do direto (ou a do catch-up); sem ela, pára o stream e
    /// "reproduzir" volta ao direto. O seguinte e o anterior mudam de canal (como o CH+ e o CH− no Android).
    func connectNowPlaying() {
        nowPlaying.onPlay = { [weak self] in self?.remotePlay() }
        nowPlaying.onPause = { [weak self] in self?.remotePause() }
        nowPlaying.onNext = { [weak self] in
            guard let self else { return }
            self.zap(self.prefs.reverseZap ? -1 : 1)
        }
        nowPlaying.onPrevious = { [weak self] in
            guard let self else { return }
            self.zap(self.prefs.reverseZap ? 1 : -1)
        }
    }

    /// A pausa guarda o sítio: o catch-up, ou a pausa do direto neste canal.
    private var remoteCanPause: Bool { catchup != nil || (extras.isEmpty && player.timeshift.available) }

    func remotePlay() {
        if remoteStopped, let current {
            play(current, remember: false)
        } else if player.paused {
            player.togglePause()
        }
    }

    private func remotePause() {
        guard player.state == .playing, !player.paused else { return }
        if remoteCanPause {
            player.togglePause()
        } else {
            player.stop()
            remoteStopped = true
        }
    }
}

/// Liga o centro de controlo ao modelo (na raiz das duas apps): atualiza-se quando muda o canal, o programa ou a pausa,
/// e de meio em meio minuto (o programa seguinte começa sem mais nada mudar).
struct NowPlayingBridge: ViewModifier {
    let model: AppModel

    func body(content: Content) -> some View {
        content
            .onAppear { model.connectNowPlaying() }
            .onChange(of: model.nowPlayingInfo, initial: true) { _, info in model.nowPlaying.update(info) }
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    model.nowPlaying.update(model.nowPlayingInfo)
                }
            }
    }
}
