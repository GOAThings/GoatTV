import GoatCore
import SwiftUI

/// Quem abre um canal a partir de uma lista (canais, guia, procura, catch-up): no ecrã inicial, passa ao vídeo.
private struct PlayedKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var played: () -> Void {
        get { self[PlayedKey.self] }
        set { self[PlayedKey.self] = newValue }
    }
}

/// O ecrã inicial do telemóvel (a app já não abre a dar o último canal): os canais vistos há pouco em cartões
/// "Continuar" a deslizar, e os grupos numa grelha. Tocar num cartão abre o canal; num grupo, a lista dele.
struct PhoneHome: View {
    @Environment(AppModel.self) private var model
    let onPlay: (Channel) -> Void
    let onGroup: (String) -> Void
    let onLockedGroup: (ChannelGroup) -> Void
    let onSearch: () -> Void
    @State private var page: Int? = 0

    var body: some View {
        let s = model.strings
        let recents = homeRecents
        let groups = model.groups.filter { $0.id != SpecialCategory.recent }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(greeting).font(.system(size: 30, weight: .heavy)).lineLimit(1)
                        Text(model.content?.playlist.name ?? "").font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                    }
                    Spacer()
                    Button(action: onSearch) {
                        Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.textDim)
                            .frame(width: 38, height: 38).background(tileFill, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(s.r("search"))
                }
                .padding(.horizontal, 18).padding(.top, 6)

                if !recents.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(Array(recents.enumerated()), id: \.element.key) { i, channel in
                                ContinueCard(channel: channel, first: i == 0) { onPlay(channel) }
                                    .containerRelativeFrame(.horizontal) { width, _ in recents.count > 1 ? width - 60 : width - 36 }
                                    .id(i)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .contentMargins(.horizontal, 18, for: .scrollContent)
                    .scrollTargetBehavior(.viewAligned)
                    .scrollPosition(id: $page)
                    .padding(.top, 14)
                    if recents.count > 1 {
                        HStack(spacing: 6) {
                            ForEach(0..<recents.count, id: \.self) { i in
                                Capsule().fill((page ?? 0) == i ? Theme.text : Theme.textFaint.opacity(0.5))
                                    .frame(width: (page ?? 0) == i ? 18 : 6, height: 6)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 12)
                        .animation(.easeOut(duration: 0.2), value: page)
                    }
                }

                Text(s.r("groups")).font(.system(size: 19, weight: .bold))
                    .padding(.horizontal, 18).padding(.top, 22).padding(.bottom, 10)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())], spacing: 10) {
                    ForEach(groups, id: \.id) { group in
                        GroupTile(group: group) {
                            if model.isGroupLocked(group.id) { onLockedGroup(group) } else { onGroup(group.id) }
                        }
                    }
                }
                .padding(.horizontal, 18)
            }
            .padding(.bottom, 16)
        }
    }

    /// O último canal primeiro (mesmo que ainda não tenha entrado nos Recentes), depois os outros.
    private var homeRecents: [Channel] {
        var seen = Set<String>()
        return ([model.current].compactMap { $0 } + model.channels(in: SpecialCategory.recent))
            .filter { seen.insert($0.key).inserted && !model.isGroupLocked($0.categoryId) }
            .prefix(6).map { $0 }
    }

    private var greeting: String {
        let s = model.strings
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...11: return s.r("greeting_morning")
        case 12...19: return s.r("greeting_afternoon")
        default: return s.r("greeting_evening")
        }
    }
}

/// Um canal visto há pouco: logótipo, o que está a dar, quanto falta e "Ver".
private struct ContinueCard: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let first: Bool
    let action: () -> Void

    var body: some View {
        let s = model.strings
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let current = model.nowNext(for: channel).now
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 68, height: 44))
                    .frame(width: 84, height: 56)
                    .background(Color.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Spacer(minLength: 0)
                Text((first ? "\(s.r("home_continue")) · \(channel.name)" : channel.name).uppercased())
                    .font(.system(size: 12, weight: .semibold)).tracking(0.5).foregroundStyle(Theme.textDim).lineLimit(1)
                Text(current?.title ?? channel.name).font(.system(size: 24, weight: .heavy)).lineLimit(1).padding(.top, 2)
                HStack(spacing: 12) {
                    HStack(spacing: 6) {
                        Image(systemName: "play.fill").font(.system(size: 14))
                        Text(s.r("watch")).font(.system(size: 15, weight: .bold))
                    }
                    .foregroundStyle(.black)
                    .padding(.leading, 12).padding(.trailing, 16).padding(.vertical, 8)
                    .background(.white, in: Capsule())
                    if let current {
                        VStack(alignment: .leading, spacing: 5) {
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.white.opacity(0.12))
                                    Capsule().fill(Theme.accent).frame(width: geo.size.width * CGFloat(min(max(current.progress(now: now), 0), 1)))
                                }
                            }
                            .frame(height: 3)
                            Text("\(hhmm(current.start)) – \(hhmm(current.end)) · \(s.r("minutes_left", "\(max(0, (current.endMs - now + 59_999) / 60_000))"))")
                                .font(.caption).foregroundStyle(Theme.textDim).lineLimit(1)
                        }
                    }
                }
                .padding(.top, 10)
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 196, maxHeight: 196, alignment: .topLeading)
            .background {
                ZStack {
                    LinearGradient(colors: [Theme.hex(0x1D2236), Theme.hex(0x0E1018)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    // Um brilho da cor de destaque no canto, para o cartão não ser um bloco liso.
                    RadialGradient(colors: [Theme.sky.opacity(0.28), .clear], center: UnitPoint(x: 0.78, y: 0.2), startRadius: 0, endRadius: 220)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.text)
        .task { model.requestShortEpg(channel) }
    }
}

/// Um grupo: até três logótipos dos canais dele, o nome e quantos canais tem. Os bloqueados pedem o PIN.
private struct GroupTile: View {
    @Environment(AppModel.self) private var model
    let group: ChannelGroup
    let action: () -> Void

    var body: some View {
        let locked = model.isGroupLocked(group.id)
        let channels = locked ? [] : model.channels(in: group.id)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    ForEach(channels.prefix(3)) { channel in
                        ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 28, height: 18))
                            .frame(width: 34, height: 24)
                            .background(tileFill, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    }
                }
                .frame(height: 24)
                Text(locked ? "🔒 \(group.name)" : group.name).font(.system(size: 16, weight: .bold))
                    .foregroundStyle(locked ? Theme.textFaint : Theme.text).lineLimit(1).padding(.top, 12)
                Text(locked ? " " : model.strings.r("group_channels", "\(channels.count)")).font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tileFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private let tileFill = Color.white.opacity(0.06)
