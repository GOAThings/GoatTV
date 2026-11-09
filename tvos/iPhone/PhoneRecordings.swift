import GoatCore
import SwiftUI

/// As gravações no telemóvel (como a página do Android, com o desenho do iPhone): as marcadas e as gravadas, o espaço
/// livre e o aviso de que só se grava com a app aberta. Tocar vê; deslizar desmarca, pára ou apaga.
struct PhoneRecordings: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.played) private var played
    @State private var free: Int64 = 0

    var body: some View {
        let s = model.strings
        let recordings = model.recordings
        let scheduled = recordings.schedule.recordings
        let recorded = recordings.list
        NavigationStack {
            List {
                Section {
                    Label(AppModel.openAppWarning(s), systemImage: "info.circle")
                        .font(.footnote).foregroundStyle(Theme.textDim)
                    Label(s.r("recordings_free", phoneSizeLabel(free)), systemImage: "internaldrive")
                        .font(.footnote).foregroundStyle(Theme.textDim)
                }
                if scheduled.isEmpty && recorded.isEmpty {
                    Text(s.r("recordings_empty")).foregroundStyle(Theme.textFaint)
                }
                if !scheduled.isEmpty {
                    Section(s.r("recordings_scheduled")) {
                        ForEach(scheduled, id: \.id) { r in
                            HStack(spacing: 12) {
                                Image(systemName: r.seriesId != nil ? "record.circle.fill" : "record.circle").foregroundStyle(.red)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(r.title).lineLimit(1)
                                    Text(phoneScheduledLine(s, r)).font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                                }
                            }
                            .swipeActions {
                                Button(r.seriesId != nil ? s.r("cancel_series") : s.r("cancel_recording"), role: .destructive) {
                                    if let series = r.seriesId { model.cancelSeries(series) } else { model.cancelScheduled(r.id) }
                                }
                            }
                        }
                    }
                }
                if !recorded.isEmpty {
                    Section(s.r("recordings_recorded")) {
                        ForEach(recorded, id: \.id) { info in
                            let running = recordings.isRunning(info.id)
                            Button {
                                guard info.state != RecordingState.failed else { return }
                                model.playRecording(info)
                                played()
                                dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    if running { Circle().fill(Color.red).frame(width: 10, height: 10) }
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(info.title).foregroundStyle(Theme.text).lineLimit(1)
                                        Text(phoneRecordingLine(s, info)).font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    if running {
                                        Button(s.r("stop_recording")) { model.stopRecording(id: info.id) }
                                            .buttonStyle(.borderless).foregroundStyle(.red)
                                    } else {
                                        Text(phoneSizeLabel(info.bytes)).font(.footnote).foregroundStyle(Theme.textFaint)
                                    }
                                }
                            }
                            .swipeActions {
                                if running {
                                    Button(s.r("stop_recording"), role: .destructive) { model.stopRecording(id: info.id) }
                                } else {
                                    Button(s.r("recording_delete"), role: .destructive) { model.deleteRecording(info.id) }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(s.r("recordings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(s.r("done")) { dismiss() }
                }
            }
            .task(id: recorded.count) { free = recordings.freeBytes() }
        }
    }
}

/// O botão de gravar (e parar) por baixo do vídeo, e a cápsula REC por cima dele.
struct PhoneRecBadge: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let rec = model.recordings.active.first {
            HStack(spacing: 6) {
                Circle().fill(Color.red).frame(width: 8, height: 8)
                Text(model.strings.r("rec_badge")).font(.caption.weight(.bold))
                Text(rec.title).font(.caption).foregroundStyle(Theme.textDim).lineLimit(1)
            }
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(.black.opacity(0.55), in: Capsule())
            .padding(10)
            .frame(maxWidth: 260, alignment: .leading)
            .allowsHitTesting(false)
        }
    }
}

func phoneSizeLabel(_ bytes: Int64) -> String {
    bytes >= 1_000_000_000 ? String(format: "%.1f GB", Double(bytes) / 1e9) : "\(max(0, bytes / 1_000_000)) MB"
}

private func phoneTime(_ ms: Int64) -> String { hhmm(Date(timeIntervalSince1970: Double(ms) / 1000)) }

func phoneRecordingLine(_ s: Strings, _ info: RecordingInfo) -> String {
    let minutes = max(0, info.durationMs / 60_000)
    var parts = [info.channelName, "\(phoneDayLabel(s, info.fileStartMs)) \(phoneTime(info.fileStartMs))", s.r("minutes", "\(minutes)")]
    if info.recording {
        parts.append(s.r("rec_badge"))
    } else if info.state == RecordingState.failed {
        parts.append(s.r("recording_state_failed"))
    } else if info.state == RecordingState.stopped {
        parts.append(s.r("recording_state_stopped"))
    }
    return parts.joined(separator: " · ")
}

func phoneScheduledLine(_ s: Strings, _ r: ScheduledRecording) -> String {
    var parts = [r.channelName, "\(phoneDayLabel(s, r.startMs)) \(phoneTime(r.startMs)) – \(phoneTime(r.endMs))"]
    if r.seriesId != nil { parts.append(s.r("record_series")) }
    return parts.joined(separator: " · ")
}
