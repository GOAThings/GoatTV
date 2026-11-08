import GoatCore
import SwiftUI
import UIKit

/*
 * Os avisos do telemóvel, iguais aos do GoatTV Android (ui/mobile/MobileNotices.kt): a pílula "A reconectar…" no topo
 * do vídeo e a folha de versão nova. Deitado, em ecrã inteiro, o vídeo usa a cápsula da TV (TuningPill).
 */

/// Ao alto, no topo do vídeo junto à câmara: a pílula preta "A reconectar…" com o anel da próxima tentativa (como uma
/// Live Activity).
struct ReconnectIsland: View {
    @Environment(AppModel.self) private var model
    let channel: String
    let nextTryAt: Date?
    let waitSeconds: TimeInterval

    var body: some View {
        HStack(spacing: 0) {
            Text(channel)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(model.strings.r("reconnecting"))
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.warning)
                .lineLimit(1)
                .fixedSize()
                .padding(.leading, 10)
            CountdownRing(nextTryAt: nextTryAt, waitSeconds: waitSeconds, lineWidth: 2.5, fontSize: 11, color: Theme.warning)
                .frame(width: 26, height: 26)
                .padding(.leading, 6)
        }
        .padding(.leading, 14)
        .padding(.trailing, 4)
        .frame(height: 34)
        .frame(maxWidth: 300)
        .background(Color.black, in: Capsule())
    }
}

/// A IPA do iPhone já descarregada, para a folha de partilha.
struct DownloadedIPA: Identifiable {
    let url: URL
    var id: URL { url }
}

enum PhoneUpdate {
    /// Descarrega a IPA da release, para a passar (pela folha de partilha) à app que a assina e instala. Sem
    /// conseguir, abre a página da release no navegador e devolve nil.
    @MainActor
    static func downloadIPA() async -> DownloadedIPA? {
        do {
            let (file, response) = try await URLSession.shared.download(from: AppUpdate.iPhoneIPA)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("GoatTV-iPhone.ipa")
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: file, to: target)
            return DownloadedIPA(url: target)
        } catch {
            await UIApplication.shared.open(AppUpdate.releasePage)
            return nil
        }
    }
}

/// Versão nova no telemóvel: uma folha que sobe de baixo com as novidades (as mesmas do cartão da TV), "Atualizar" (o
/// mesmo das Definições: descarrega a IPA e abre a folha de partilha, [onDownloaded]) e "Mais tarde". Toca-se fora,
/// ou puxa-se para baixo, para a fechar.
struct PhoneUpdateSheet: View {
    @Environment(AppModel.self) private var model
    let onDownloaded: (DownloadedIPA) -> Void
    @State private var downloading = false
    @State private var drag: CGFloat = 0

    init(onDownloaded: @escaping (DownloadedIPA) -> Void) {
        self.onDownloaded = onDownloaded
    }

    private func dismiss() {
        guard !downloading else { return }
        model.dismissUpdateNotice()
    }

    var body: some View {
        let notice = model.updateNotice
        ZStack(alignment: .bottom) {
            if notice != nil {
                Color.black.opacity(0.5)
                    .ignoresSafeArea()
                    .onTapGesture(perform: dismiss)
                    .transition(.opacity)
            }
            if let notice {
                content(notice)
                    .offset(y: drag)
                    .gesture(DragGesture()
                        .onChanged { drag = max(0, $0.translation.height) }
                        .onEnded { v in
                            if v.translation.height > 80 { dismiss() }
                            withAnimation(.easeOut(duration: 0.2)) { drag = 0 }
                        })
                    .transition(.move(edge: .bottom))
            }
        }
        .animation(.easeOut(duration: 0.25), value: notice)
    }

    private func content(_ notice: AppModel.UpdateNotice) -> some View {
        let s = model.strings
        let accent = Theme.accent(model.prefs.accent)
        return VStack(alignment: .leading, spacing: 0) {
            Capsule().fill(Color.white.opacity(0.25)).frame(width: 38, height: 5).frame(maxWidth: .infinity)
            HStack(spacing: 14) {
                AppIconMark()
                    .frame(width: 54, height: 54)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.r("update_ready_label").uppercased(with: s.locale))
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(Theme.textDim)
                    Text(s.r("update_whats_new", notice.version))
                        .font(.system(size: 21, weight: .heavy).monospacedDigit())
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
            .padding(.top, 18)
            if !notice.highlights.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(notice.highlights.enumerated()), id: \.offset) { _, line in
                        HStack(alignment: .top, spacing: 12) {
                            Circle().fill(accent).frame(width: 6, height: 6).padding(.top, 7)
                            Text(line)
                                .font(.system(size: 14))
                                .foregroundStyle(Theme.text.opacity(0.85))
                                .lineLimit(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(.top, 12)
            }
            Button {
                guard !downloading else { return }
                downloading = true
                Task {
                    let file = await PhoneUpdate.downloadIPA()
                    downloading = false
                    model.dismissUpdateNotice()
                    if let file { onDownloaded(file) }
                }
            } label: {
                Text(downloading ? s.t("Downloading…", "A descarregar…") : s.r("update_now"))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.black)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(downloading)
            .padding(.top, 18)
            if !downloading {
                Button(action: dismiss) {
                    Text(s.r("update_later"))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.textDim)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 22)
        .padding(.top, 10)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28, style: .continuous)
                .fill(Theme.hex(0x151821))
                .ignoresSafeArea(edges: .bottom)
        )
    }
}
