import GoatCore
import SwiftUI

/// Sports Hub na Apple TV (o mesmo do Android TV, fase 1 só com o guia): o jogo em destaque com onde ver, os jogos a
/// decorrer e os próximos, e os canais onde dão. OK num jogo abre-o no melhor canal; se falhar, passa aos seguintes.
struct SportsHubView: View {
    @Environment(AppModel.self) private var model
    let onPlayed: () -> Void
    let onClose: () -> Void

    @State private var hub: SportsHubData?
    @State private var sport: String?
    @State private var featuredId: String?
    @State private var now = nowMs()
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        let games = (hub?.games ?? []).filter { sport == nil || $0.sport == sport }
        let featured = games.first { $0.id == featuredId } ?? games.first
        VStack(spacing: 20) {
            topBar(s)
            if hub == nil {
                message(s.r("sports_loading"))
            } else if let featured {
                HStack(spacing: 20) {
                    featuredCard(featured, s)
                    wherePanel(featured, s).frame(width: 500)
                }
                .frame(height: 440)
                bottomPanel(games, s)
            } else {
                message(s.r("sports_empty"))
            }
        }
        .padding(.horizontal, 56)
        .padding(.vertical, 36)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(SportsUI.background)
        .ignoresSafeArea()
        .onExitCommand(perform: onClose)
        .task(id: model.guideVersion) { await refresh() }
        .task {
            // A cada minuto: os jogos passam a "ao vivo" e os acabados saem.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                now = nowMs()
                await refresh()
            }
        }
        .onAppear {
            hub = model.sportsCached
            focus = "watch"
        }
    }

    private func refresh() async {
        if let fresh = await model.sportsHub() {
            hub = fresh
            if focus == nil { focus = "watch" }
        }
    }

    private func topBar(_ s: Strings) -> some View {
        HStack(spacing: 14) {
            Text(s.r("sports_hub").uppercased()).font(.system(size: 26, weight: .heavy)).tracking(2).padding(.trailing, 10)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    chip(s.r("sports_all"), id: "chip:all", selected: sport == nil) { sport = nil; featuredId = nil }
                    ForEach(hub?.sports ?? [], id: \.self) { sp in
                        chip(SportsUI.sportName(sp, s), id: "chip:" + sp, selected: sport == sp) { sport = sp; featuredId = nil }
                    }
                }
                .padding(.vertical, 10)
            }
            Text(hhmm(Date(timeIntervalSince1970: TimeInterval(now) / 1000))).font(.system(size: 30, weight: .bold))
        }
        .padding(.horizontal, 24)
        .frame(height: 88)
        .background(SportsUI.panel, in: RoundedRectangle(cornerRadius: 28))
        .focusSection()
    }

    private func chip(_ text: String, id: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: 24, weight: selected ? .heavy : .semibold)).lineLimit(1)
        }
        .buttonStyle(HubPillStyle(rest: selected ? Color.white.opacity(0.3) : SportsUI.raised))
        .focused($focus, equals: id)
    }

    private func featuredCard(_ game: SportsGameInfo, _ s: Strings) -> some View {
        let live = game.isLive(now)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                Text(live ? s.r("sports_live").uppercased() : SportsUI.when(game.startMs, now: now, s).uppercased())
                    .font(.system(size: 20, weight: .heavy))
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background(live ? SportsUI.liveRed : Color.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 8))
                let meta = [SportsUI.sportName(game.sport, s), game.league.isEmpty ? nil : game.league,
                            live ? s.r("sports_started", hhmm(Date(timeIntervalSince1970: TimeInterval(game.startMs) / 1000))) : nil]
                Text(meta.compactMap { $0 }.joined(separator: " · ")).font(.system(size: 22)).foregroundStyle(SportsUI.soft).lineLimit(1)
            }
            Spacer(minLength: 0)
            if game.home.isEmpty {
                Text(game.name).font(.system(size: 50, weight: .heavy)).lineLimit(2)
            } else {
                HStack(spacing: 40) {
                    team(game.home)
                    Text("–").font(.system(size: 70, weight: .heavy))
                    team(game.away)
                }
            }
            Spacer(minLength: 0)
            if live, game.endMs > game.startMs {
                ProgressLine(value: min(max(Double(now - game.startMs) / Double(game.endMs - game.startMs), 0), 1))
                    .frame(width: 520).padding(.bottom, 16)
            }
            HStack(spacing: 16) {
                Button { play(game) } label: {
                    Text(s.r("sports_watch_on", game.channels.first?.name ?? "")).font(.system(size: 26, weight: .heavy)).lineLimit(1)
                }
                .buttonStyle(HubPillStyle(rest: Color.white.opacity(0.92), restText: .black))
                .focused($focus, equals: "watch")
                if game.channels.count > 1 {
                    Button { focus = "where:" + game.channels[0].key } label: {
                        Text(s.r("sports_other_channels", "\(game.channels.count - 1)")).font(.system(size: 26, weight: .heavy))
                    }
                    .buttonStyle(HubPillStyle(rest: Color.white.opacity(0.16)))
                }
            }
        }
        .padding(.horizontal, 44).padding(.vertical, 34)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(SportsUI.gradient(game.sport), in: RoundedRectangle(cornerRadius: 32))
        .focusSection()
    }

    private func team(_ name: String) -> some View {
        VStack(spacing: 10) {
            TeamBadge(name: name, size: 100)
            Text(name).font(.system(size: 28, weight: .bold)).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    private func wherePanel(_ game: SportsGameInfo, _ s: Strings) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(s.r("sports_where").uppercased()).font(.system(size: 20, weight: .heavy)).foregroundStyle(SportsUI.dim)
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(spacing: 8) {
                    ForEach(game.channels, id: \.key) { c in
                        Button { play(game, from: c.key) } label: {
                            HStack(spacing: 14) {
                                ChannelLogo(url: model.content?.byKey[c.key]?.logo.flatMap(URL.init(string:)), name: c.name, size: CGSize(width: 64, height: 40))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.name).font(.system(size: 24, weight: .bold)).lineLimit(1)
                                    if let country = c.country {
                                        Text(GroupCountry.name(country, locale: s.locale, noneName: "")).font(.system(size: 18)).opacity(0.7).lineLimit(1)
                                    }
                                }
                                Spacer(minLength: 0)
                            }
                        }
                        .buttonStyle(HubCardStyle(padding: 14))
                        .focused($focus, equals: "where:" + c.key)
                    }
                }
                .padding(8)
            }
        }
        .padding(20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(SportsUI.panel, in: RoundedRectangle(cornerRadius: 32))
        .focusSection()
    }

    private func bottomPanel(_ games: [SportsGameInfo], _ s: Strings) -> some View {
        let live = games.filter { $0.isLive(now) }.count
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                if live > 0 {
                    Circle().fill(SportsUI.liveRed).frame(width: 12, height: 12)
                    Text("\(s.r("sports_live").uppercased()) · \(live)").foregroundStyle(SportsUI.liveText).padding(.trailing, 26)
                }
                Text(s.r("sports_next").uppercased()).foregroundStyle(SportsUI.dim)
            }
            .font(.system(size: 20, weight: .heavy))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(games.prefix(40)) { g in
                        Button { play(g) } label: { gameCard(g, s) }
                            .buttonStyle(HubCardStyle(padding: 18))
                            .frame(width: 330, height: 150)
                            .focused($focus, equals: "game:" + g.id)
                    }
                }
                .padding(10)
            }
            .onChange(of: focus) { _, f in
                if let f, f.hasPrefix("game:") { featuredId = String(f.dropFirst(5)) }
            }
            Text(s.r("sports_channels").uppercased()).font(.system(size: 20, weight: .heavy)).foregroundStyle(SportsUI.dim)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach((hub?.channels ?? []).prefix(40), id: \.key) { c in
                        if let ch = model.content?.byKey[c.key] {
                            Button {
                                model.play(ch, from: model.groupFor(ch))
                                onPlayed()
                            } label: {
                                VStack(spacing: 6) {
                                    ChannelLogo(url: ch.logo.flatMap(URL.init(string:)), name: ch.name, size: CGSize(width: 70, height: 42))
                                    Text(ch.name).font(.system(size: 18, weight: .bold)).lineLimit(1)
                                }
                            }
                            .buttonStyle(HubCardStyle(padding: 10))
                            .frame(width: 230, height: 110)
                        }
                    }
                }
                .padding(10)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(SportsUI.panel, in: RoundedRectangle(cornerRadius: 32))
        .focusSection()
    }

    private func gameCard(_ g: SportsGameInfo, _ s: Strings) -> some View {
        let live = g.isLive(now)
        let label = live ? "\(SportsUI.sportName(g.sport, s)) · \(s.r("sports_live"))" : "\(SportsUI.when(g.startMs, now: now, s)) · \(SportsUI.sportName(g.sport, s))"
        return VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased()).font(.system(size: 16, weight: .heavy)).foregroundStyle(live ? SportsUI.liveText : SportsUI.nextText).lineLimit(1)
            Text(g.title).font(.system(size: 24, weight: .bold)).lineLimit(2)
            Spacer(minLength: 0)
            Text(g.channels.first?.name ?? "").font(.system(size: 18)).opacity(0.65).lineLimit(1)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func message(_ text: String) -> some View {
        Text(text).font(.system(size: 30)).foregroundStyle(SportsUI.soft).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(SportsUI.panel, in: RoundedRectangle(cornerRadius: 32))
            // Algo com foco, para o Voltar fechar o hub.
            .focusable()
    }

    private func play(_ game: SportsGameInfo, from key: String? = nil) {
        model.playGame(game, from: key)
        onPlayed()
    }
}

/// Os cartões do hub: escuros em repouso, brancos com texto escuro com foco (como as linhas da app).
private struct HubCardStyle: ButtonStyle {
    let padding: CGFloat

    func makeBody(configuration: Configuration) -> some View { Card(configuration: configuration, padding: padding) }

    private struct Card: View {
        let configuration: Configuration
        let padding: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color.black : Theme.text)
                .padding(padding)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(focused ? Color.white : SportsUI.raised, in: RoundedRectangle(cornerRadius: 22))
                .scaleEffect(focused ? 1.04 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}

/// Os botões em cápsula do hub (desportos, ver no canal).
private struct HubPillStyle: ButtonStyle {
    var rest: Color
    var restText: Color = Theme.text

    func makeBody(configuration: Configuration) -> some View { Pill(configuration: configuration, rest: rest, restText: restText) }

    private struct Pill: View {
        let configuration: Configuration
        let rest: Color
        let restText: Color
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color.black : restText)
                .padding(.horizontal, 28).padding(.vertical, 14)
                .background(Capsule().fill(focused ? Color.white : rest))
                .scaleEffect(focused ? 1.06 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}
