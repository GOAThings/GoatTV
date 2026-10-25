import GoatCore
import SwiftUI

/// Onde fica a pré-visualização do canal enquanto o guia está aberto (o vídeo encolhe para aqui).
enum GuideLayout {
    static let previewSize = CGSize(width: 512, height: 288)
    static let marginTop: CGFloat = 40
    static let marginSide: CGFloat = 64
}

private let channelColumn: CGFloat = 420
private let rowHeight: CGFloat = 92
private let rulerHeight: CGFloat = 52
private let halfHour: Int64 = 30 * 60_000
/// Largura visível da grelha: 2 horas.
private let windowMs: Int64 = 4 * halfHour
private let pastLimitMs: Int64 = 24 * 3_600_000
private let futureLimitMs: Int64 = 36 * 3_600_000

/// Um bloco da grelha: um programa, ou um intervalo sem informação.
struct GuideSlot: Equatable {
    var entry: EpgEntry?
    var start: Int64
    var end: Int64
}

/// Guia de TV em grelha (como no Android): canais nas linhas, horas nas colunas, o canal a dar no canto. O foco é
/// gerido à mão (linha + hora): ← → seguem os programas, ↑ ↓ mantêm a hora. ↑ na primeira linha passa para o grupo
/// (← → muda de grupo). OK: em direto vê o canal; já passou e há arquivo vê em catch-up; ainda vai dar marca um
/// lembrete. Play/pausa volta à hora atual.
struct GuideView: View {
    @Environment(AppModel.self) private var model

    let playing: Channel
    let onPlay: (Channel, String) -> Void
    let onCatchup: (Channel, EpgEntry) -> Void
    let onClose: () -> Void

    @State private var group: String
    @State private var row = -1
    @State private var windowStart: Int64
    @State private var focusTime: Int64
    @State private var onHeader = false
    @State private var now = nowMs()
    @FocusState private var focused: Bool
    private let opened: Int64

    init(playing: Channel, startGroup: String, onPlay: @escaping (Channel, String) -> Void,
         onCatchup: @escaping (Channel, EpgEntry) -> Void, onClose: @escaping () -> Void) {
        self.playing = playing
        self.onPlay = onPlay
        self.onCatchup = onCatchup
        self.onClose = onClose
        let t = nowMs()
        opened = t
        _group = State(initialValue: startGroup)
        _windowStart = State(initialValue: GuideView.floorHalfHour(t))
        _focusTime = State(initialValue: t)
    }

    private var groups: [ChannelGroup] { model.guideGroups }
    private var channels: [Channel] { model.channels(in: group) }
    private var minTime: Int64 { GuideView.floorHalfHour(opened - pastLimitMs) }
    private var maxTime: Int64 { opened + futureLimitMs }

    var body: some View {
        let list = channels
        let current = row >= 0 && row < list.count ? list[row] : nil
        let _ = model.guideVersion
        let selected = current.map { slotAt(model.programmes(for: $0), focusTime) }

        Button(action: select) {
            VStack(alignment: .leading, spacing: 28) {
                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 14) {
                        groupSwitcher
                        if let current, let selected {
                            detail(current, selected)
                        } else {
                            Text(model.strings.t("No channels in this group", "Não há canais neste grupo"))
                                .font(Fonts.rowTitle).foregroundStyle(Theme.textDim)
                        }
                    }
                    Spacer(minLength: 0)
                    // Aqui por baixo vê-se o canal a dar (o vídeo do player, encolhido).
                    Color.clear.frame(width: GuideLayout.previewSize.width, height: GuideLayout.previewSize.height)
                }
                .frame(height: GuideLayout.previewSize.height)

                GeometryReader { geo in
                    let timelineWidth = geo.size.width - channelColumn
                    let perPage = max(1, Int((geo.size.height - rulerHeight) / rowHeight))
                    let top = max(0, min(row - 2, list.count - perPage))
                    let visible = list.isEmpty ? [] : Array(list[top..<min(list.count, top + perPage)])
                    ZStack(alignment: .topLeading) {
                        VStack(alignment: .leading, spacing: 0) {
                            ruler(timelineWidth)
                            ForEach(Array(visible.enumerated()), id: \.element.uid) { offset, ch in
                                GuideRow(
                                    channel: ch,
                                    entries: model.programmes(for: ch),
                                    selected: (top + offset == row && !onHeader) ? selected : nil,
                                    isPlaying: ch.key == playing.key,
                                    windowStart: windowStart,
                                    timelineWidth: timelineWidth,
                                    now: now
                                )
                            }
                        }
                        // A linha da hora atual.
                        if now >= windowStart, now < windowStart + windowMs {
                            Rectangle()
                                .fill(Theme.accent(model.prefs.accent))
                                .frame(width: 3, height: geo.size.height)
                                .offset(x: channelColumn + CGFloat(now - windowStart) / CGFloat(windowMs) * timelineWidth - 1)
                        }
                    }
                }
            }
            .padding(.horizontal, GuideLayout.marginSide)
            .padding(.vertical, GuideLayout.marginTop)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Theme.background)
        }
        .buttonStyle(InvisibleButtonStyle())
        .focused($focused)
        .onMoveCommand(perform: move)
        .onExitCommand(perform: onClose)
        .onPlayPauseCommand {
            let t = nowMs()
            windowStart = GuideView.floorHalfHour(t)
            focusTime = t
        }
        .ignoresSafeArea()
        .onAppear {
            if row < 0 { row = max(0, list.firstIndex { $0.key == playing.key } ?? 0) }
            if !groups.contains(where: { $0.id == group }) { group = groups.first?.id ?? SpecialCategory.all }
            focused = true
        }
        .onChange(of: row) { _, _ in requestShortEpg() }
        .task {
            requestShortEpg()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                now = nowMs()
            }
        }
    }

    // MARK: Teclas

    private func move(_ direction: MoveCommandDirection) {
        let list = channels
        switch direction {
        case .up:
            if !onHeader {
                if row <= 0 { onHeader = true } else { row -= 1 }
            }
        case .down:
            if onHeader { onHeader = false } else { row = min(row + 1, max(0, list.count - 1)) }
        case .left, .right:
            let forward = direction == .right
            if onHeader {
                guard !groups.isEmpty else { return }
                let i = groups.firstIndex { $0.id == group } ?? 0
                group = groups[((i + (forward ? 1 : -1)) % groups.count + groups.count) % groups.count].id
                row = 0
            } else if row >= 0, row < list.count {
                let entries = model.programmes(for: list[row])
                let slot = slotAt(entries, focusTime)
                if forward {
                    if slot.end < maxTime { moveTo(slotAt(entries, slot.end)) }
                } else if slot.start < windowStart, windowStart > minTime {
                    // Um programa que começa antes da grelha: primeiro mostra-se o início dele.
                    windowStart -= halfHour
                    focusTime = max(slot.start, windowStart)
                } else if slot.start - 1 >= minTime {
                    moveTo(slotAt(entries, slot.start - 1))
                }
            }
        @unknown default:
            break
        }
    }

    private func select() {
        let list = channels
        guard !onHeader, row >= 0, row < list.count else { return }
        let ch = list[row]
        let slot = slotAt(model.programmes(for: ch), focusTime)
        let t = nowMs()
        if let e = slot.entry, !e.isOn(at: t) {
            if e.startMs > t {
                model.toggleReminder(ch, e)
            } else if model.canCatchup(ch, e) {
                onCatchup(ch, e)
            } else {
                model.showToast(model.strings.t("This programme isn't available on catch-up", "Este programa não está disponível em catch-up"))
            }
        } else {
            onPlay(ch, group)
        }
    }

    private func moveTo(_ slot: GuideSlot) {
        focusTime = max(slot.start, minTime)
        while focusTime < windowStart { windowStart -= halfHour }
        while focusTime >= windowStart + windowMs { windowStart += halfHour }
        // Ficar com o início do bloco visível, mas sem saltar a grelha para trás de mais.
        focusTime = max(focusTime, windowStart)
    }

    /// O bloco na hora [t] da linha: o programa, ou o intervalo vazio até à meia hora seguinte.
    private func slotAt(_ entries: [EpgEntry], _ t: Int64) -> GuideSlot {
        if let e = entries.first(where: { $0.startMs <= t && $0.endMs > t }) { return GuideSlot(entry: e, start: e.startMs, end: e.endMs) }
        let half = GuideView.floorHalfHour(t)
        let prevEnd = entries.last(where: { $0.endMs <= t })?.endMs ?? Int64.min
        let nextStart = entries.first(where: { $0.startMs > t })?.startMs ?? Int64.max
        return GuideSlot(entry: nil, start: max(prevEnd, half), end: min(nextStart, half + halfHour))
    }

    /// Canais sem guia completo: pede o EPG curto do canal escolhido (Xtream).
    private func requestShortEpg() {
        let list = channels
        guard row >= 0, row < list.count else { return }
        model.requestShortEpg(list[row])
    }

    // MARK: Partes

    private var groupSwitcher: some View {
        let name = groups.first { $0.id == group }?.name ?? model.strings.all
        return HStack(spacing: 18) {
            Text("‹").font(.system(size: 30, weight: .semibold)).opacity(0.6)
            Text(name).font(.system(size: 28, weight: .semibold)).lineLimit(1)
            Text("›").font(.system(size: 30, weight: .semibold)).opacity(0.6)
        }
        .foregroundStyle(onHeader ? Color.black : Theme.text)
        .padding(.horizontal, 26)
        .padding(.vertical, 10)
        .background(Capsule().fill(onHeader ? Color.white : Color.white.opacity(0.14)))
    }

    @ViewBuilder
    private func detail(_ ch: Channel, _ slot: GuideSlot) -> some View {
        let s = model.strings
        let e = slot.entry
        Text(e?.title ?? s.noGuideInfo)
            .font(.system(size: 46, weight: .semibold))
            .lineLimit(1)
        if let e {
            Text(["\(GuideView.dayLabel(s, e.startMs)) \(hhmm(e.start)) – \(hhmm(e.end))",
                  s.t("%@ min", "%@ min", "\((e.endMs - e.startMs) / 60_000)"),
                  ch.name].joined(separator: "  ·  "))
                .font(.system(size: 27))
                .foregroundStyle(Theme.textDim)
                .lineLimit(1)
        } else {
            Text(ch.name).font(.system(size: 27)).foregroundStyle(Theme.textDim)
        }
        let live = e == nil || e!.isOn(at: now)
        if let e, live {
            ProgressLine(value: e.progress(now: now), height: 5).frame(width: 440)
        }
        HStack(spacing: 34) {
            if onHeader {
                KeyHint(key: "← →", label: s.t("Change group", "Mudar de grupo"))
            } else if live {
                KeyHint(key: "OK", label: s.t("Watch", "Ver"))
            } else if let e, e.startMs > now {
                KeyHint(key: "OK", label: model.hasReminder(ch, e) ? s.t("Remove reminder", "Tirar lembrete") : s.t("Remind me", "Lembrar"))
            } else if let e, model.canCatchup(ch, e) {
                KeyHint(key: "OK", label: s.t("Watch on catch-up", "Ver em catch-up"))
            } else {
                Text(s.t("Not available on catch-up", "Sem catch-up")).font(.system(size: 25)).foregroundStyle(Theme.textFaint)
            }
            if !onHeader { KeyHint(key: "▶︎❚❚", label: s.t("Back to now", "Voltar a agora")) }
        }
    }

    private func ruler(_ timelineWidth: CGFloat) -> some View {
        HStack(spacing: 0) {
            Text(GuideView.dayLabel(model.strings, windowStart))
                .font(.system(size: 25, weight: .medium))
                .foregroundStyle(Theme.textDim)
                .frame(width: channelColumn, alignment: .leading)
            ZStack(alignment: .leading) {
                ForEach(0..<4, id: \.self) { i in
                    Text(hhmm(Date(timeIntervalSince1970: Double(windowStart + Int64(i) * halfHour) / 1000)))
                        .font(.system(size: 25, weight: .medium))
                        .foregroundStyle(Theme.textDim)
                        .offset(x: timelineWidth / 4 * CGFloat(i) + 12)
                }
            }
            .frame(width: timelineWidth, alignment: .leading)
        }
        .frame(height: rulerHeight)
    }

    // MARK: Datas

    static func floorHalfHour(_ t: Int64) -> Int64 {
        let date = Date(timeIntervalSince1970: Double(t) / 1000)
        var c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        c.minute = (c.minute ?? 0) < 30 ? 0 : 30
        c.second = 0
        let floored = Calendar.current.date(from: c) ?? date
        return Int64(floored.timeIntervalSince1970 * 1000)
    }

    /// "Hoje", "Ontem", "Amanhã" ou o dia da semana.
    static func dayLabel(_ s: Strings, _ t: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(t) / 1000)
        let cal = Calendar.current
        if cal.isDateInToday(date) { return s.t("Today", "Hoje") }
        if cal.isDateInYesterday(date) { return s.t("Yesterday", "Ontem") }
        if cal.isDateInTomorrow(date) { return s.t("Tomorrow", "Amanhã") }
        let f = DateFormatter()
        f.locale = s.locale
        f.setLocalizedDateFormatFromTemplate("EEEd")
        return f.string(from: date)
    }
}

/// Uma linha do guia: o canal e os programas da janela visível.
private struct GuideRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let entries: [EpgEntry]
    let selected: GuideSlot?
    let isPlaying: Bool
    let windowStart: Int64
    let timelineWidth: CGFloat
    let now: Int64

    private struct Cell: Identifiable {
        let id: String
        let title: String
        let startLabel: Int64?
        let start: Int64
        let end: Int64
        let past: Bool
        let live: Bool
        let selected: Bool
        let icon: String?
    }

    var body: some View {
        let prefs = model.prefs
        HStack(spacing: 0) {
            HStack(spacing: 14) {
                if prefs.showNumbers {
                    Text("\(channel.num)").font(.system(size: 23).monospacedDigit()).foregroundStyle(Theme.textDim).frame(width: 64, alignment: .leading)
                }
                if prefs.showLogos {
                    ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 64, height: 44))
                }
                Text(channel.name)
                    .font(.system(size: 27, weight: selected != nil ? .semibold : .regular))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(width: channelColumn - 12, height: rowHeight - 8)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(selected != nil ? 0.14 : (isPlaying ? 0.07 : 0))))
            .padding(.trailing, 12)

            ZStack(alignment: .topLeading) {
                ForEach(cells) { c in
                    cellView(c)
                }
            }
            .frame(width: timelineWidth, height: rowHeight - 8, alignment: .topLeading)
            .clipped()
        }
        .frame(height: rowHeight)
    }

    private var cells: [Cell] {
        let windowEnd = windowStart + windowMs
        let noInfo = model.strings.noGuideInfo
        var list: [Cell] = []
        let visible = entries.filter { $0.endMs > windowStart && $0.startMs < windowEnd }
        if visible.isEmpty, selected?.entry == nil {
            list.append(Cell(id: "empty", title: noInfo, startLabel: nil, start: windowStart, end: windowEnd, past: false, live: false, selected: false, icon: nil))
        }
        for e in visible {
            let past = e.endMs <= now
            var icon: String?
            if e.startMs > now, model.hasReminder(channel, e) { icon = "bell.fill" }
            if past, model.canCatchup(channel, e) { icon = "clock.arrow.circlepath" }
            list.append(Cell(
                id: "\(e.startMs)", title: e.title, startLabel: e.startMs >= windowStart ? e.startMs : nil,
                start: max(e.startMs, windowStart), end: min(e.endMs, windowEnd),
                past: past, live: e.isOn(at: now), selected: selected?.entry == e, icon: icon
            ))
        }
        // Bloco vazio escolhido (sem informação nessa hora).
        if let selected, selected.entry == nil {
            list.append(Cell(id: "sel", title: noInfo, startLabel: nil, start: max(selected.start, windowStart),
                             end: min(selected.end, windowEnd), past: false, live: false, selected: true, icon: nil))
        }
        return list
    }

    @ViewBuilder
    private func cellView(_ c: Cell) -> some View {
        let perMs = timelineWidth / CGFloat(windowMs)
        let left = CGFloat(c.start - windowStart) * perMs
        let width = CGFloat(c.end - c.start) * perMs - 6
        if width >= 8 {
            let ink: Color = c.selected ? .black : (c.past ? Theme.textDim : Theme.text)
            HStack(spacing: 10) {
                if let icon = c.icon, width > 80 {
                    Image(systemName: icon).font(.system(size: 20)).foregroundStyle(c.selected ? Color.black : Theme.textDim)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.title)
                        .font(.system(size: 26, weight: c.selected ? .semibold : .regular))
                        .foregroundStyle(ink)
                        .lineLimit(1)
                    if let start = c.startLabel, width > 180 {
                        Text(hhmm(Date(timeIntervalSince1970: Double(start) / 1000)))
                            .font(.system(size: 20))
                            .foregroundStyle(c.selected ? Color.black.opacity(0.6) : Theme.textFaint)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(width: width, height: rowHeight - 8, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(c.selected ? Color.white : Color.white.opacity(c.live ? 0.13 : (c.past ? 0.04 : 0.08)))
            )
            .offset(x: left)
        }
    }
}

/// Uma tecla do comando com o que faz (ex.: "OK  Ver").
struct KeyHint: View {
    let key: String
    let label: String

    var body: some View {
        HStack(spacing: 12) {
            Text(key)
                .font(.system(size: 21, weight: .bold))
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.white.opacity(0.4), lineWidth: 2))
            Text(label).font(.system(size: 25)).foregroundStyle(Theme.textDim)
        }
    }
}
