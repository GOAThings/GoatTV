import GoatCore
import SwiftUI

private let cardFill = Color.white.opacity(0.06)
private let pillFill = Color.white.opacity(0.08)

private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// "1 canal" / "N canais".
func channelCount(_ s: Strings, _ n: Int) -> String {
    n == 1 ? s.r("group_channels_one") : s.r("group_channels", "\(n)")
}

/// "Hoje", "Amanhã" ou o dia da semana e o dia (ex.: "Dom 8").
func phoneDayLabel(_ s: Strings, _ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(ms) / 1000)
    let cal = Calendar.current
    if cal.isDateInToday(date) { return s.r("today") }
    if cal.isDateInTomorrow(date) { return s.r("tomorrow") }
    if cal.isDateInYesterday(date) { return s.r("yesterday") }
    return date.formatted(.dateTime.weekday(.abbreviated).day())
}

// MARK: Ecrã do canal

/// Por baixo do vídeo: o canal, o programa a dar (com quanto falta) e o seguinte, com lembrete.
struct NowStrip: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    /// Volta ao ecrã inicial (o canal continua no mini-leitor).
    let onMinimize: () -> Void

    var body: some View {
        let s = model.strings
        let now = nowMillis()
        let pair = model.nowNext(for: channel)
        let favorite = model.isFavorite(channel)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 52, height: 34))
                    .frame(width: 64, height: 44)
                    .background(cardFill, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text("\(channel.num) · \(channel.name)".uppercased()).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.textDim).lineLimit(1)
                    Text(pair.now?.title ?? channel.name).font(.system(size: 20, weight: .heavy)).lineLimit(1)
                }
                Spacer(minLength: 0)
                Button { model.toggleFavorite(channel) } label: {
                    Image(systemName: favorite ? "star.fill" : "star").font(.system(size: 20))
                        .foregroundStyle(favorite ? Theme.hex(0xF5C04A) : Theme.textFaint).frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                Button(action: onMinimize) {
                    Image(systemName: "chevron.down").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.textFaint).frame(width: 36, height: 40)
                }
                .buttonStyle(.plain)
            }
            if let current = pair.now {
                ProgressBarLine(value: Double(current.progress(now: now)), tint: AnyShapeStyle(Theme.accent(model.prefs.accent)), height: 3)
                    .padding(.top, 12)
                HStack {
                    Text("\(hhmm(current.start)) – \(hhmm(current.end))")
                    Spacer()
                    Text(s.r("minutes_left", "\(max(0, (current.endMs - now + 59_999) / 60_000))"))
                }
                .font(.caption).foregroundStyle(Theme.textDim).padding(.top, 6)
            }
            if let next = pair.next {
                let reminded = model.hasReminder(channel, next)
                Button { model.toggleReminder(channel, next) } label: {
                    HStack(spacing: 10) {
                        Text(s.r("next", hhmm(next.start))).font(.footnote.weight(.semibold)).foregroundStyle(Theme.textFaint)
                        Text(next.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Spacer(minLength: 0)
                        Image(systemName: reminded ? "bell.fill" : "bell").foregroundStyle(reminded ? Theme.sky : Theme.textFaint)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(cardFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, 10)
            }
        }
        .foregroundStyle(Theme.text)
        .padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 4)
        .task(id: channel.key) { model.requestShortEpg(channel) }
    }
}

/// Uma barra de progresso fina.
struct ProgressBarLine: View {
    let value: Double
    let tint: AnyShapeStyle
    var height: CGFloat = 2

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.1))
                Capsule().fill(tint).frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: height)
    }
}

// MARK: Mini-leitor

/// O canal a dar fora do ecrã do canal: uma barra por cima dos separadores com o vídeo pequeno, o programa, pausa e
/// fechar. Tocar nela volta ao canal em grande.
struct MiniPlayer: View {
    @Environment(AppModel.self) private var model
    let onOpen: () -> Void
    let onClose: () -> Void

    var body: some View {
        let s = model.strings
        let now = nowMillis()
        let current = model.current.flatMap { model.nowNext(for: $0).now }
        HStack(spacing: 12) {
            VideoSurface(view: model.player.view)
                .allowsHitTesting(false)
                .frame(width: 84, height: 47)
                .background(Color.black)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(model.current?.name ?? "").font(.system(size: 15, weight: .bold)).lineLimit(1)
                if let current {
                    Text("\(current.title) · \(s.r("minutes_left", "\(max(0, (current.endMs - now + 59_999) / 60_000))"))")
                        .font(.caption).foregroundStyle(Theme.textDim).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Button { model.player.togglePause() } label: {
                Image(systemName: model.player.paused ? "play.fill" : "pause.fill").font(.system(size: 20)).frame(width: 40, height: 44)
            }
            .buttonStyle(.plain)
            Button(action: onClose) {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.textDim).frame(width: 36, height: 44)
            }
            .buttonStyle(.plain)
        }
        .foregroundStyle(Theme.text)
        .padding(.leading, 8).padding(.trailing, 6)
        .frame(height: 62)
        .background(Theme.hex(0x1A1E2A))
        .overlay(alignment: .bottom) {
            if let current { ProgressBarLine(value: Double(current.progress(now: now)), tint: AnyShapeStyle(Theme.accent(model.prefs.accent))) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.5), radius: 14, y: 6)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }
}

// MARK: Guia em grelha

/// O guia do telemóvel em grelha, como o da TV: os canais à esquerda, os programas a deslizar de lado (com o dedo),
/// a hora atual numa linha vermelha e os dias por cima. Tocar num programa a dar abre o canal; num que já passou,
/// o catch-up (se houver); num que vem aí, marca um lembrete. Manter o dedo abre o guia do canal.
struct PhoneGuideGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.played) private var played
    let channels: [Channel]
    let group: String
    @Binding var epgFor: Channel?
    @State private var origin = PhoneGuideGrid.halfHour(nowMillis() - PhoneGuideGrid.pastMs)
    @State private var offset: CGFloat?
    @State private var dragStart: CGFloat?
    @State private var horizontal: Bool?
    @State private var width: CGFloat = 0
    @State private var now = nowMillis()

    private static let halfHourMs: Int64 = 1_800_000
    private static let pastMs: Int64 = 12 * 3_600_000
    private static let futureMs: Int64 = 7 * 86_400_000
    private let slot: CGFloat = 120
    private let column: CGFloat = 76
    private var perMs: CGFloat { slot / CGFloat(Self.halfHourMs) }
    private var maxOffset: CGFloat { max(0, CGFloat(Self.pastMs + Self.futureMs) * perMs - (width - column)) }
    private var shift: CGFloat { offset ?? CGFloat(now - Self.halfHourMs / 2 - origin) * perMs }

    var body: some View {
        let s = model.strings
        VStack(spacing: 0) {
            dayChips(s)
            ruler
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(channels) { channel in row(channel) }
                }
                .padding(.bottom, 12)
            }
            .overlay(alignment: .topLeading) {
                let x = column + CGFloat(now - origin) * perMs - shift
                if x >= column {
                    Rectangle().fill(Theme.hex(0xF2777E).opacity(0.9)).frame(width: 2).offset(x: x - 1).allowsHitTesting(false)
                }
            }
        }
        .background { GeometryReader { geo in Color.clear.onAppear { width = geo.size.width }.onChange(of: geo.size.width) { _, w in width = w } } }
        .simultaneousGesture(
            DragGesture(minimumDistance: 12)
                .onChanged { v in
                    if horizontal == nil { horizontal = abs(v.translation.width) > abs(v.translation.height) }
                    guard horizontal == true else { return }
                    if dragStart == nil { dragStart = shift }
                    offset = clamp((dragStart ?? 0) - v.translation.width)
                }
                .onEnded { v in
                    if horizontal == true, let start = dragStart {
                        withAnimation(.easeOut(duration: 0.45)) { offset = clamp(start - v.predictedEndTranslation.width) }
                    }
                    dragStart = nil
                    horizontal = nil
                }
        )
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                now = nowMillis()
            }
        }
    }

    private func clamp(_ x: CGFloat) -> CGFloat { min(max(x, 0), maxOffset) }

    /// Os dias: Hoje (volta à hora atual), Amanhã, e os seguintes às 18:00.
    private func dayChips(_ s: Strings) -> some View {
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        let left = origin + Int64(shift / perMs) + Self.halfHourMs
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(0..<7, id: \.self) { i in
                    let day = cal.date(byAdding: .day, value: i, to: today) ?? today
                    let start = Int64(day.timeIntervalSince1970 * 1000)
                    let end = Int64((cal.date(byAdding: .day, value: 1, to: day) ?? day).timeIntervalSince1970 * 1000)
                    let on = left >= start && left < end
                    Button {
                        let target = i == 0 ? now - Self.halfHourMs / 2 : start + 18 * 3_600_000
                        withAnimation(.easeInOut(duration: 0.35)) { offset = clamp(CGFloat(target - origin) * perMs) }
                    } label: {
                        Text(phoneDayLabel(s, start + 12 * 3_600_000)).font(.subheadline.weight(.semibold))
                            .foregroundStyle(on ? Color.black : Theme.text)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(on ? Color.white : pillFill, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.bottom, 6)
    }

    /// As horas de meia em meia hora, com a hora atual numa etiqueta vermelha.
    private var ruler: some View {
        let first = origin + Int64(shift / perMs) / Self.halfHourMs * Self.halfHourMs
        let nowX = CGFloat(now - origin) * perMs - shift
        let count = Int((width - column) / slot) + 2
        return ZStack(alignment: .leading) {
            ForEach(0..<max(count, 1), id: \.self) { i in
                let t = first + Int64(i) * Self.halfHourMs
                let x = CGFloat(t - origin) * perMs - shift
                // A hora debaixo da etiqueta vermelha, e a que já vai a sair pela esquerda, não se mostram.
                if x >= 0, abs(x + 22 - nowX) > 46 {
                    Text(hhmm(Date(timeIntervalSince1970: Double(t) / 1000))).font(.caption).foregroundStyle(Theme.textDim)
                        .fixedSize().offset(x: x + 4)
                }
            }
            if nowX >= 0 {
                Text(hhmm(Date(timeIntervalSince1970: Double(now) / 1000))).font(.caption.weight(.bold)).foregroundStyle(Theme.hex(0x0B0B0B))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Theme.hex(0xF2777E), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .fixedSize()
                    .alignmentGuide(.leading) { $0.width / 2 }
                    .offset(x: nowX)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: 30)
        .clipped()
        .padding(.leading, column)
    }

    private func row(_ channel: Channel) -> some View {
        let entries = model.programmes(for: channel)
        let left = origin + Int64(shift / perMs)
        let right = left + Int64((width - column) / perMs)
        let visible = entries.filter { $0.endMs > left && $0.startMs < right }
        let playing = model.current?.key == channel.key
        return HStack(spacing: 0) {
            ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 44, height: 30))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(playing ? Theme.sky.opacity(0.18) : pillFill, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 4)
                .frame(width: column)
            ZStack(alignment: .topLeading) {
                if entries.isEmpty {
                    Text(model.strings.r("no_guide")).font(.footnote).foregroundStyle(Theme.textFaint)
                        .padding(8).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .background(Color.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .padding(.vertical, 4)
                }
                ForEach(visible, id: \.startMs) { e in cell(channel, e) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .clipped()
        }
        .frame(height: 58)
        .task(id: channel.key) { model.requestShortEpg(channel) }
    }

    private func cell(_ channel: Channel, _ e: EpgEntry) -> some View {
        let x = CGFloat(e.startMs - origin) * perMs - shift
        let w = max(CGFloat(e.endMs - e.startMs) * perMs - 3, 4)
        let live = e.startMs <= now && e.endMs > now
        let past = e.endMs <= now
        let reminded = model.hasReminder(channel, e)
        return VStack(alignment: .leading, spacing: 1) {
            Text(e.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(past ? Theme.textFaint : Theme.text).lineLimit(1)
            if !live { Text(hhmm(e.start)).font(.system(size: 11)).foregroundStyle(Theme.textFaint) }
            Spacer(minLength: 0)
            if live { ProgressBarLine(value: Double(e.progress(now: now)), tint: AnyShapeStyle(Theme.accent(model.prefs.accent))) }
        }
        // O título fica à vista mesmo com o programa a começar fora do ecrã (como no guia da TV).
        .padding(.leading, max(0, -x) + 8).padding(.trailing, 8).padding(.vertical, 7)
        .frame(width: w, height: 50, alignment: .topLeading)
        .background(Color.white.opacity(live ? 0.1 : past ? 0.025 : 0.06), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(alignment: .topTrailing) {
            if reminded { Circle().fill(Theme.sky).frame(width: 7, height: 7).padding(7) }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            let t = nowMillis()
            if e.startMs <= t, e.endMs > t {
                model.play(channel, from: group)
                played()
            } else if e.startMs > t {
                model.toggleReminder(channel, e)
            } else if model.canCatchup(channel, e) {
                model.playCatchup(channel, e)
                played()
            } else {
                epgFor = channel
            }
        }
        .onLongPressGesture { epgFor = channel }
        .offset(x: x, y: 4)
    }

    private static func halfHour(_ ms: Int64) -> Int64 { ms - ms % halfHourMs }
}

// MARK: Procurar

/// Procurar: antes de escrever, as pesquisas recentes, o que está a dar nos canais vistos há pouco e os grupos;
/// com texto, os resultados separados em canais, a dar agora (Ver), mais tarde (lembrete) e já passou (catch-up).
struct PhoneSearch: View {
    @Environment(AppModel.self) private var model
    @Environment(\.played) private var played
    @Binding var epgFor: Channel?
    let onGroup: (String) -> Void
    @State private var query = ""
    @State private var filter = 0
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: PhoneSearch.recentKey) ?? []

    private static let recentKey = "phone_recent_searches"

    var body: some View {
        let s = model.strings
        let typed = query.trimmingCharacters(in: .whitespaces).count >= 2
        let now = nowMillis()
        let hits = typed ? model.search(query, withPast: true) : []
        VStack(alignment: .leading, spacing: 0) {
            Text(s.r("search")).font(.system(size: 30, weight: .heavy)).padding(.horizontal, 18).padding(.top, 6).padding(.bottom, 10)
            TextField(s.r("search_hint"), text: $query)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(14).background(pillFill, in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 12)
            if typed {
                HStack(spacing: 8) {
                    pill(s.r("all"), on: filter == 0) { filter = 0 }
                    pill(s.r("search_channels"), on: filter == 1) { filter = 1 }
                    pill(s.r("search_programmes"), on: filter == 2) { filter = 2 }
                }
                .padding(.horizontal, 18).padding(.top, 12)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !typed {
                        if !recent.isEmpty {
                            caption(s.r("search_recent"))
                            FlowChips(items: recent) { q in
                                HStack(spacing: 6) {
                                    Image(systemName: "clock").font(.caption).foregroundStyle(Theme.textFaint)
                                    Text(q).font(.subheadline)
                                }
                                .padding(.horizontal, 12).padding(.vertical, 8).background(pillFill, in: Capsule())
                                .onTapGesture { query = q }
                            }
                            .padding(.horizontal, 12)
                        }
                        let recents = Array(model.channels(in: SpecialCategory.recent).prefix(3))
                        if !recents.isEmpty {
                            caption(s.r("search_now"))
                            ForEach(recents) { ch in ChannelCard(channel: ch, group: model.groupFor(ch), epgFor: $epgFor) }
                                .padding(.horizontal, 6)
                        }
                        caption(s.r("groups"))
                        FlowChips(items: model.groups.filter { $0.id != SpecialCategory.recent && !model.isGroupLocked($0.id) }.map(\.id)) { id in
                            Text(model.groups.first { $0.id == id }?.name ?? "").font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .background(pillFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .onTapGesture { onGroup(id) }
                        }
                        .padding(.horizontal, 12)
                    } else if hits.isEmpty {
                        Text(s.r("search_no_results")).foregroundStyle(Theme.textFaint).padding(16)
                    } else {
                        let channels = hits.filter { $0.programme == nil }
                        let live = hits.filter { h in h.programme.map { $0.startMs <= now && $0.endMs > now } == true }
                        let later = hits.filter { h in h.programme.map { $0.startMs > now } == true }
                        let past = hits.filter { h in h.programme.map { $0.endMs <= now } == true }
                        if filter != 2, !channels.isEmpty {
                            caption(s.r("search_channels"))
                            ForEach(channels) { h in ChannelCard(channel: h.channel, group: model.groupFor(h.channel), epgFor: $epgFor) }
                                .padding(.horizontal, 6)
                                .simultaneousGesture(TapGesture().onEnded { remember() })
                        }
                        if filter != 1 {
                            if !live.isEmpty {
                                caption(s.r("search_now"))
                                ForEach(live) { h in
                                    let e = h.programme!
                                    result(h.channel, e.title, "\(h.channel.name) · \(hhmm(e.start)) – \(hhmm(e.end))", progress: Double(e.progress(now: now)), action: s.r("watch")) {
                                        remember()
                                        model.play(h.channel, from: model.groupFor(h.channel))
                                        played()
                                    }
                                }
                            }
                            if !later.isEmpty {
                                caption(s.r("search_later"))
                                ForEach(later) { h in
                                    let e = h.programme!
                                    result(h.channel, e.title, "\(phoneDayLabel(s, e.startMs)) \(hhmm(e.start)) · \(h.channel.name)", bell: model.hasReminder(h.channel, e)) {
                                        remember()
                                        model.toggleReminder(h.channel, e)
                                    }
                                }
                            }
                            if !past.isEmpty {
                                caption(s.r("search_past"))
                                ForEach(past) { h in
                                    let e = h.programme!
                                    result(h.channel, e.title, "\(phoneDayLabel(s, e.startMs)) \(hhmm(e.start)) · \(h.channel.name)", action: s.r("watch_again")) {
                                        remember()
                                        model.playCatchup(h.channel, e)
                                        played()
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.bottom, 12)
            }
        }
        .foregroundStyle(Theme.text)
    }

    private func remember() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return }
        recent = Array(([q] + recent.filter { $0.caseInsensitiveCompare(q) != .orderedSame }).prefix(6))
        UserDefaults.standard.set(recent, forKey: Self.recentKey)
    }

    private func caption(_ text: String) -> some View {
        Text(text.uppercased()).font(.caption.weight(.bold)).tracking(0.6).foregroundStyle(Theme.textFaint)
            .padding(.horizontal, 18).padding(.top, 18).padding(.bottom, 8)
    }

    private func pill(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(on ? Color.black : Theme.text)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(on ? Color.white : pillFill, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func result(_ channel: Channel, _ title: String, _ sub: String, progress: Double? = nil, action: String? = nil, bell: Bool? = nil, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack(spacing: 14) {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 56, height: 38))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.body.weight(.semibold)).lineLimit(1)
                    Text(sub).font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                    if let progress { ProgressBarLine(value: progress, tint: AnyShapeStyle(Color.white.opacity(0.34))).padding(.top, 5) }
                }
                Spacer(minLength: 0)
                if let action { Text(action).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.sky) }
                if let bell { Image(systemName: bell ? "bell.fill" : "bell").foregroundStyle(bell ? Theme.sky : Theme.textFaint) }
            }
            .padding(.horizontal, 16).padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(LongPressGesture().onEnded { _ in epgFor = channel })
    }
}

/// Pastilhas que passam para a linha seguinte quando não cabem.
struct FlowChips<Content: View>: View {
    let items: [String]
    @ViewBuilder let content: (String) -> Content

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { content($0) }
        }
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(proposal.width ?? .infinity, subviews)
        return CGSize(width: proposal.width ?? rows.width, height: rows.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += line + spacing
                line = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += size.width + spacing
            line = max(line, size.height)
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (width: CGFloat, height: CGFloat) {
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += line + spacing
                line = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, size.height)
        }
        return (widest, y + line)
    }
}

// MARK: Favoritos

/// Favoritos em cartões (logótipo, o que está a dar e quanto já passou), com "Ordenar" para mudar a ordem com ↑ ↓.
struct PhoneFavorites: View {
    @Environment(AppModel.self) private var model
    @Environment(\.played) private var played
    @Binding var epgFor: Channel?
    @State private var reordering = false

    var body: some View {
        let s = model.strings
        let channels = model.channels(in: SpecialCategory.favorites)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.r("favorites")).font(.system(size: 30, weight: .heavy))
                    Text(channelCount(s, channels.count)).font(.footnote).foregroundStyle(Theme.textFaint)
                }
                Spacer()
                if channels.count > 1 {
                    Button(reordering ? s.r("done") : s.r("favorites_reorder")) { reordering.toggle() }
                        .font(.body.weight(.semibold)).foregroundStyle(Theme.sky)
                }
            }
            .padding(.horizontal, 18).padding(.top, 6)
            ScrollView {
                if reordering {
                    LazyVStack(spacing: 0) {
                        ForEach(channels) { ch in
                            HStack(spacing: 14) {
                                ChannelLogo(url: model.logo(for: ch), name: ch.name, size: CGSize(width: 56, height: 38))
                                Text(ch.name).font(.body.weight(.semibold)).lineLimit(1)
                                Spacer(minLength: 0)
                                Button { model.moveFavorite(ch, by: -1) } label: { Image(systemName: "chevron.up").frame(width: 40, height: 40) }
                                Button { model.moveFavorite(ch, by: 1) } label: { Image(systemName: "chevron.down").frame(width: 40, height: 40) }
                            }
                            .foregroundStyle(Theme.textDim)
                            .padding(.horizontal, 16).padding(.vertical, 6)
                        }
                    }
                    .padding(.top, 12)
                } else {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())], spacing: 10) {
                        ForEach(channels) { ch in card(ch) }
                    }
                    .padding(.horizontal, 18).padding(.top, 14)
                    HStack(spacing: 12) {
                        Image(systemName: "star").foregroundStyle(Theme.textDim)
                        Text(s.r("favorites_hint")).font(.subheadline).foregroundStyle(Theme.textFaint)
                        Spacer(minLength: 0)
                    }
                    .padding(14)
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.12)))
                    .padding(.horizontal, 18).padding(.top, 18)
                }
            }
        }
        .foregroundStyle(Theme.text)
    }

    private func card(_ channel: Channel) -> some View {
        let now = nowMillis()
        let entry = model.nowNext(for: channel).now
        let playing = model.current?.key == channel.key
        return Button {
            model.play(channel, from: SpecialCategory.favorites)
            played()
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    RadialGradient(colors: [Theme.sky.opacity(playing ? 0.35 : 0.16), .clear], center: .topLeading, startRadius: 0, endRadius: 160)
                    ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 58, height: 38))
                        .frame(width: 72, height: 48)
                        .background(Color.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.hex(0xF5C04A)).padding(10)
                }
                .frame(height: 92)
                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.name).font(.system(size: 15, weight: .bold)).foregroundStyle(playing ? Theme.sky : Theme.text).lineLimit(1)
                    Text(entry?.title ?? " ").font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                    ProgressBarLine(value: entry.map { Double($0.progress(now: now)) } ?? 0, tint: AnyShapeStyle(Theme.accent(model.prefs.accent))).padding(.top, 6)
                }
                .padding(.horizontal, 12).padding(.top, 10).padding(.bottom, 12)
            }
            .background(cardFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(LongPressGesture().onEnded { _ in epgFor = channel })
        .task(id: channel.key) { model.requestShortEpg(channel) }
    }
}
