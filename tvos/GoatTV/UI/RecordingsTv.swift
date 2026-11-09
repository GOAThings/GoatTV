import GoatCore
import SwiftUI

// As gravações no Apple TV, como no Android TV (RecordingsTv.kt): a cápsula REC, o cartão "Estás a gravar X" ao mudar
// de canal, o aviso de uma marcada noutro canal e o painel das gravações.

/// A cápsula "REC" no canto enquanto há uma gravação (com o título do que grava).
struct RecPill: View {
    @Environment(AppModel.self) private var model
    let title: String
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(Color.red).frame(width: 14, height: 14).opacity(pulse ? 1 : 0.45)
            Text(model.strings.r("rec_badge")).font(.system(size: 22, weight: .bold))
            Text(title).font(.system(size: 22)).foregroundStyle(Theme.textDim).lineLimit(1)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .frame(maxWidth: 640, alignment: .leading)
        .fixedSize(horizontal: true, vertical: false)
        .glass(radius: 30)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}

/// O cartão das gravações, com a forma do lembrete ([ReminderCard]): ícone, títulos e dois botões (OK e Voltar).
private struct RecordingCard: View {
    @Environment(AppModel.self) private var model
    let icon: String
    let overline: String
    let title: String
    let text: String
    let ok: String
    let back: String
    let onOk: () -> Void
    let onBack: () -> Void
    @FocusState private var focus: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack(spacing: 24) {
                Image(systemName: icon)
                    .font(.system(size: 34))
                    .foregroundStyle(.white)
                    .frame(width: 80, height: 80)
                    .background(Circle().fill(Color.red.opacity(0.85)))
                VStack(alignment: .leading, spacing: 6) {
                    Text(overline).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
                    Text(title).font(.system(size: 36, weight: .semibold)).lineLimit(2)
                    Text(text).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).lineLimit(3)
                }
            }
            HStack(spacing: 20) {
                Button(ok, action: onOk).buttonStyle(ChipButtonStyle()).focused($focus, equals: "ok").accessibilityIdentifier("recording-ok")
                Button(back, action: onBack).buttonStyle(ChipButtonStyle()).focused($focus, equals: "back").accessibilityIdentifier("recording-back")
            }
        }
        .padding(44)
        .frame(maxWidth: 1040, alignment: .leading)
        .glass(radius: 34)
        .focusSection()
        .onExitCommand(perform: onBack)
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = "ok"
        }
    }
}

/// Mudar de canal com uma gravação a decorrer: OK pára a gravação e muda, Voltar fica a gravar (neste canal).
struct RecordingSwitchCard: View {
    @Environment(AppModel.self) private var model
    let prompt: AppModel.SwitchPrompt

    var body: some View {
        let s = model.strings
        RecordingCard(icon: "record.circle", overline: s.r("rec_badge"), title: s.r("recording_switch_title", prompt.title),
                      text: s.r("recording_switch_text"), ok: s.r("recording_stop_and_switch"), back: s.r("recording_keep"),
                      onOk: { model.answerSwitch(stop: true) }, onBack: { model.answerSwitch(stop: false) })
    }
}

/// Uma marcada noutro canal quase a começar: OK muda para esse canal (e grava), Voltar não grava. Sem resposta até à
/// hora, muda-se (conta-se até lá).
struct RecordingPromptCard: View {
    @Environment(AppModel.self) private var model
    let recording: ScheduledRecording
    @State private var now = nowMs()

    var body: some View {
        let s = model.strings
        let schedule = model.recordings.schedule
        let from = RecordingPlan.window(recording, schedule.paddingBeforeMin, schedule.paddingAfterMin).fromMs
        let left = max(0, Int((from - now + 999) / 1000))
        RecordingCard(icon: "record.circle", overline: s.r("recording_soon", recording.channelName), title: recording.title,
                      text: "\(hhmm(Date(timeIntervalSince1970: Double(recording.startMs) / 1000))) · \(s.r("recording_switching_in", "\(left)"))",
                      ok: s.r("recording_switch_to", recording.channelName), back: s.r("recording_skip"),
                      onOk: { model.answerRecordingPrompt(recording.id, switchChannel: true) },
                      onBack: { model.answerRecordingPrompt(recording.id, switchChannel: false) })
            .task {
                while !Task.isCancelled {
                    now = nowMs()
                    try? await Task.sleep(for: .seconds(1))
                }
            }
    }
}

/// "12,3 GB", "850 MB".
func recordingSizeLabel(_ bytes: Int64) -> String {
    bytes >= 1_000_000_000 ? String(format: "%.1f GB", Double(bytes) / 1e9) : "\(max(0, bytes / 1_000_000)) MB"
}

/// A linha de baixo de uma gravação: canal, dia e hora, duração e, se for o caso, como acabou.
func recordingLine(_ s: Strings, _ info: RecordingInfo) -> String {
    let start = Date(timeIntervalSince1970: Double(info.fileStartMs) / 1000)
    var parts = [info.channelName, "\(GuideView.dayLabel(s, info.fileStartMs)) \(hhmm(start))", CatchupBar.duration(info.durationMs)]
    if info.recording {
        parts.append(s.r("rec_badge"))
    } else if info.state == RecordingState.failed {
        parts.append(s.r("recording_state_failed"))
    } else if info.state == RecordingState.stopped {
        parts.append(s.r("recording_state_stopped"))
    }
    return parts.joined(separator: " · ")
}

/// A linha de baixo de uma marcada: canal, dia e hora (e "série").
func scheduledLine(_ s: Strings, _ r: ScheduledRecording) -> String {
    let start = Date(timeIntervalSince1970: Double(r.startMs) / 1000)
    let end = Date(timeIntervalSince1970: Double(r.endMs) / 1000)
    var parts = [r.channelName, "\(GuideView.dayLabel(s, r.startMs)) \(hhmm(start)) – \(hhmm(end))"]
    if r.seriesId != nil { parts.append(s.r("record_series")) }
    return parts.joined(separator: " · ")
}

/// As gravações na TV: um painel de vidro à esquerda (como a pesquisa) com as marcadas e as gravadas, e o espaço livre.
/// OK vê uma gravação; manter OK pára, apaga ou desmarca (o menu que abre é a confirmação, em vez do "carrega outra vez"
/// do Android, que no Siri Remote não tem tecla Menu). Por baixo, o que o tvOS não deixa: só grava com a app aberta, e
/// as gravações ficam na cache.
struct RecordingsPanel: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let onPlay: (RecordingInfo) -> Void
    @FocusState private var focus: String?
    @State private var free: Int64 = 0

    init(onPlay: @escaping (RecordingInfo) -> Void) {
        self.onPlay = onPlay
    }

    var body: some View {
        let s = model.strings
        let recordings = model.recordings
        let scheduled = recordings.schedule.recordings
        let recorded = recordings.list
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .lastTextBaseline) {
                Text(s.r("recordings")).font(Fonts.panelTitle)
                Spacer()
                Text(s.r("recordings_free", recordingSizeLabel(free))).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
            }
            .padding(.horizontal, 16)
            Text(AppModel.openAppWarning(s) + " " + AppModel.cacheWarning(s))
                .font(.system(size: 20)).foregroundStyle(Theme.textFaint)
                .padding(.horizontal, 16)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    if scheduled.isEmpty && recorded.isEmpty {
                        Text(s.r("recordings_empty")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
                            .padding(.horizontal, 16).padding(.bottom, 8)
                        // Algo com foco, para o Voltar continuar a funcionar aqui.
                        Button {} label: { RowContent(title: s.r("recordings")) { EmptyView() } }
                            .buttonStyle(FillRowStyle())
                            .focused($focus, equals: "empty")
                    }
                    if !scheduled.isEmpty {
                        header(s.r("recordings_scheduled"))
                        ForEach(scheduled, id: \.id) { r in scheduledRow(r) }
                    }
                    if !recorded.isEmpty {
                        header(s.r("recordings_recorded"))
                        ForEach(recorded, id: \.id) { info in recordedRow(info) }
                    }
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 6)
            }
            HStack(spacing: 30) {
                KeyHint(key: "OK", label: s.t("Watch", "Ver"))
                KeyHint(key: s.t("Hold OK", "Manter OK"), label: holdLabel)
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth + 60))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .accessibilityIdentifier("recordings-panel")
        .onChange(of: focus) { _, _ in panelActivity(false) }
        .task(id: recorded.count) { free = recordings.freeBytes() }
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = scheduled.first.map { "s:" + $0.id } ?? recorded.first.map { "r:" + $0.id } ?? "empty"
        }
    }

    /// O que manter OK faz na linha com foco: desmarcar, parar ou apagar.
    private var holdLabel: String {
        let s = model.strings
        guard let f = focus else { return s.r("recording_delete") }
        if f.hasPrefix("s:") {
            let id = String(f.dropFirst(2))
            return model.recordings.schedule.recordings.first { $0.id == id }?.seriesId != nil ? s.r("cancel_series") : s.r("cancel_recording")
        }
        if f.hasPrefix("r:"), model.recordings.isRunning(String(f.dropFirst(2))) { return s.r("stop_recording") }
        return s.r("recording_delete")
    }

    private func header(_ text: String) -> some View {
        Text(text.uppercased(with: model.strings.locale))
            .font(.system(size: 20, weight: .bold)).tracking(0.6)
            .foregroundStyle(Theme.textFaint)
            .padding(.horizontal, 16).padding(.top, 10)
    }

    private func scheduledRow(_ r: ScheduledRecording) -> some View {
        let s = model.strings
        let cancel = r.seriesId != nil ? s.r("cancel_series") : s.r("cancel_recording")
        return Button {} label: {
            HStack(spacing: 16) {
                Image(systemName: r.seriesId != nil ? "record.circle.fill" : "record.circle").font(.system(size: 26)).foregroundStyle(.red)
                RowContent(title: r.title, subtitle: scheduledLine(s, r)) { EmptyView() }
            }
        }
        .buttonStyle(FillRowStyle())
        .focused($focus, equals: "s:" + r.id)
        .accessibilityIdentifier("scheduled:\(r.id)")
        .contextMenu {
            Button(cancel, role: .destructive) {
                if let series = r.seriesId { model.cancelSeries(series) } else { model.cancelScheduled(r.id) }
            }
        }
    }

    private func recordedRow(_ info: RecordingInfo) -> some View {
        let s = model.strings
        let running = model.recordings.isRunning(info.id)
        return Button {
            if info.state != RecordingState.failed { onPlay(info) }
        } label: {
            HStack(spacing: 16) {
                if running { Circle().fill(Color.red).frame(width: 14, height: 14) }
                RowContent(title: info.title, subtitle: recordingLine(s, info)) {
                    // Na cor da linha (escura com foco), um pouco esbatido, como no Android.
                    Text(running ? s.r("stop_recording") : recordingSizeLabel(info.bytes)).font(Fonts.rowSubtitle).opacity(0.6)
                }
            }
        }
        .buttonStyle(FillRowStyle())
        .focused($focus, equals: "r:" + info.id)
        .accessibilityIdentifier("recording:\(info.id)")
        .contextMenu {
            if running {
                Button(s.r("stop_recording"), role: .destructive) { model.stopRecording(id: info.id) }
            } else {
                Button(s.r("recording_delete"), role: .destructive) { model.deleteRecording(info.id) }
            }
        }
    }
}
