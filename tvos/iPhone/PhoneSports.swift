import GoatCore
import SwiftUI

/// Sports Hub no iPhone (o mesmo da TV, em coluna): os desportos, o jogo em destaque com onde ver, e os jogos a
/// decorrer e os próximos. Tocar num jogo mostra-o em destaque; "Ver no…" abre-o no melhor canal.
struct PhoneSports: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.played) private var played
    @State private var hub: SportsHubData?
    @State private var sport: String?
    @State private var featuredId: String?
    @State private var now = nowMs()

    var body: some View {
        let s = model.strings
        let games = (hub?.games ?? []).filter { sport == nil || $0.sport == sport }
        let featured = games.first { $0.id == featuredId } ?? games.first
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            chip(s.r("sports_all"), selected: sport == nil) { sport = nil; featuredId = nil }
                            ForEach(hub?.sports ?? [], id: \.self) { sp in
                                chip(SportsUI.sportName(sp, s), selected: sport == sp) { sport = sp; featuredId = nil }
                            }
                        }
                    }
                    if hub == nil {
                        note(s.r("sports_loading"))
                    } else if let featured {
                        featuredCard(featured, s)
                        if featured.channels.count > 1 {
                            label(s.r("sports_where"))
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(featured.channels, id: \.key) { c in
                                        Button { play(featured, from: c.key) } label: {
                                            HStack(spacing: 8) {
                                                ChannelLogo(url: model.content?.byKey[c.key]?.logo.flatMap(URL.init(string:)), name: c.name, size: CGSize(width: 34, height: 24))
                                                VStack(alignment: .leading, spacing: 1) {
                                                    Text(c.name).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                                                    if let country = c.country {
                                                        Text(GroupCountry.name(country, locale: s.locale, noneName: "")).font(.caption).foregroundStyle(Theme.textFaint).lineLimit(1)
                                                    }
                                                }
                                            }
                                            .padding(.horizontal, 10).padding(.vertical, 8)
                                            .frame(width: 190, alignment: .leading)
                                            .background(SportsUI.raised, in: RoundedRectangle(cornerRadius: 12))
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                        let live = games.filter { $0.isLive(now) }
                        let next = games.filter { !$0.isLive(now) }
                        if !live.isEmpty {
                            label("\(s.r("sports_live")) · \(live.count)", color: SportsUI.liveText)
                            ForEach(live) { row($0, s, selected: $0.id == featured.id) }
                        }
                        if !next.isEmpty {
                            label(s.r("sports_next"))
                            ForEach(next) { row($0, s, selected: $0.id == featured.id) }
                        }
                    } else {
                        note(s.r("sports_empty"))
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 24)
            }
            .background(Theme.background)
            .navigationTitle(s.r("sports_hub"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(s.r("done")) { dismiss() } }
            }
        }
        .task(id: model.guideVersion) {
            if hub == nil { hub = model.sportsCached }
            if let fresh = await model.sportsHub() { hub = fresh }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                now = nowMs()
                if let fresh = await model.sportsHub() { hub = fresh }
            }
        }
    }

    private func featuredCard(_ game: SportsGameInfo, _ s: Strings) -> some View {
        let live = game.isLive(now)
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Text(live ? s.r("sports_live").uppercased() : SportsUI.when(game.startMs, now: now, s).uppercased())
                    .font(.caption.weight(.heavy)).foregroundStyle(.white)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(live ? SportsUI.liveRed : Color.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 5))
                Text([SportsUI.sportName(game.sport, s), game.league.isEmpty ? nil : game.league].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(SportsUI.soft).lineLimit(1)
            }
            if game.home.isEmpty {
                Text(game.name).font(.title2.weight(.heavy)).foregroundStyle(.white).lineLimit(2)
            } else {
                HStack {
                    team(game.home)
                    Text("–").font(.system(size: 30, weight: .heavy)).foregroundStyle(.white)
                    team(game.away)
                }
            }
            if live {
                Text(s.r("sports_started", hhmm(Date(timeIntervalSince1970: TimeInterval(game.startMs) / 1000))))
                    .font(.caption).foregroundStyle(SportsUI.soft)
            }
            Button { play(game) } label: {
                Text(s.r("sports_watch_on", game.channels.first?.name ?? "")).font(.subheadline.weight(.heavy)).foregroundStyle(.black).lineLimit(1)
                    .padding(.horizontal, 16).frame(maxWidth: .infinity).frame(height: 44)
                    .background(Color.white, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(SportsUI.gradient(game.sport), in: RoundedRectangle(cornerRadius: 18))
    }

    private func team(_ name: String) -> some View {
        VStack(spacing: 6) {
            TeamBadge(name: name, size: 52)
            Text(name).font(.subheadline.weight(.bold)).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    private func row(_ g: SportsGameInfo, _ s: Strings, selected: Bool) -> some View {
        let live = g.isLive(now)
        return Button { featuredId = g.id } label: {
            HStack(spacing: 10) {
                if !g.home.isEmpty {
                    ZStack(alignment: .leading) {
                        TeamBadge(name: g.home, size: 32)
                        TeamBadge(name: g.away, size: 32).offset(x: 22)
                    }
                    .frame(width: 56, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(g.title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                    Text([SportsUI.sportName(g.sport, s), g.channels.first?.name].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(Theme.textFaint).lineLimit(1)
                }
                Spacer(minLength: 0)
                Text(live ? s.r("sports_live") : SportsUI.when(g.startMs, now: now, s))
                    .font(.caption.weight(.bold)).foregroundStyle(live ? SportsUI.liveText : SportsUI.nextText)
            }
            .padding(12)
            .background(selected ? Color.white.opacity(0.14) : SportsUI.raised, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.subheadline.weight(selected ? .bold : .medium)).foregroundStyle(selected ? .black : Theme.text)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(selected ? Color.white : SportsUI.raised, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func label(_ text: String, color: Color = Theme.textFaint) -> some View {
        Text(text.uppercased()).font(.caption.weight(.heavy)).foregroundStyle(color).padding(.top, 4)
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(Theme.textDim).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.vertical, 40)
    }

    private func play(_ game: SportsGameInfo, from key: String? = nil) {
        model.playGame(game, from: key)
        played()
        dismiss()
    }
}
