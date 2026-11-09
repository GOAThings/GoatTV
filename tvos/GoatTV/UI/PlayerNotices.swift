import SwiftUI

/*
 * Os avisos nos cantos do player, iguais aos do GoatTV Android (PlayerOverlays.TuningPill e UpdateCard): a cápsula a
 * sintonizar / a voltar a ligar e o cartão de versão nova. A cápsula serve também o iPhone deitado, em ecrã inteiro
 * (mais pequena, ver [TuningPill.scale]); o cartão é só da Apple TV.
 */

/// Quanto tempo o cartão de versão nova fica no ecrã (como no Android).
let updateCardSeconds = 12

/// No canto: a sintonizar um canal, ou a voltar a ligar depois de ele cair ([reconnecting]). A ligar, o anel roda; à
/// espera da próxima tentativa ([nextTryAt]), enche-se durante [waitSeconds] com os segundos no meio.
struct TuningPill: View {
    @Environment(AppModel.self) private var model
    let channel: String
    let reconnecting: Bool
    /// A tentativa de voltar a ligar (0 = não caiu: só o nome do canal).
    let attempt: Int
    let nextTryAt: Date?
    let waitSeconds: TimeInterval
    /// 1 na TV; no iPhone a mesma cápsula, à escala do ecrã dele.
    var scale: CGFloat = 1

    var body: some View {
        let s = model.strings
        // O nome não estica a cápsula: um nome muito comprido fica cortado (no Android, 220 dp no máximo).
        let name = channel.count > 34 ? String(channel.prefix(33)) + "…" : channel
        CapsuleRow(title: reconnecting ? s.r("reconnecting") : s.r("tuning"), scale: scale) {
            CountdownRing(nextTryAt: nextTryAt, waitSeconds: waitSeconds, lineWidth: 4.5 * scale, fontSize: 22 * scale)
        } detail: {
            if attempt > 0 || !name.isEmpty {
                Text(attempt > 0 ? s.r("reconnect_attempt", name, "\(attempt)") : name).lineLimit(1)
            }
        }
        .fixedSize()
        .capsuleGlass(scale: scale)
    }
}

/// A linha da cápsula do canto: o anel (60 pt) e duas linhas, a de cima e o pormenor por baixo, esbatido. É a da
/// cápsula de voltar a ligar e a da gravação (RecordingsTv), para serem iguais.
struct CapsuleRow<Ring: View, Detail: View>: View {
    let title: String
    var scale: CGFloat = 1
    @ViewBuilder let ring: () -> Ring
    @ViewBuilder let detail: () -> Detail

    var body: some View {
        HStack(spacing: 20 * scale) {
            ring().frame(width: 60 * scale, height: 60 * scale)
            VStack(alignment: .leading, spacing: 2 * scale) {
                Text(title).font(.system(size: 26 * scale, weight: .semibold))
                detail()
                    .font(.system(size: 21 * scale).monospacedDigit())
                    .foregroundStyle(Theme.textDim)
            }
        }
    }
}

/// O que a cápsula do canto mede: o anel com as margens à volta (o canto é metade disto).
let capsuleHeight: CGFloat = 60 + 2 * 14

extension View {
    /// As margens e o vidro da cápsula do canto (a de voltar a ligar; a da gravação é igual).
    func capsuleGlass(scale: CGFloat = 1) -> some View {
        padding(.leading, 14 * scale)
            .padding(.trailing, 32 * scale)
            .padding(.vertical, 14 * scale)
            .glass(radius: capsuleHeight / 2 * scale)
    }
}

/// À espera da próxima tentativa ([nextTryAt], ao fim de [waitSeconds]): o anel enche-se com os segundos no meio. A
/// tentar (nil ou já passou): o anel roda.
struct CountdownRing: View {
    let nextTryAt: Date?
    let waitSeconds: TimeInterval
    var lineWidth: CGFloat = 4.5
    var fontSize: CGFloat = 22
    /// O traço do anel (os segundos no meio ficam sempre brancos).
    var color: Color = .white

    var body: some View {
        TimelineView(.animation) { timeline in
            let left = nextTryAt.map { $0.timeIntervalSince(timeline.date) } ?? 0
            ZStack {
                if left > 0, waitSeconds > 0 {
                    ProgressRing(fraction: 1 - left / waitSeconds, lineWidth: lineWidth, color: AnyShapeStyle(color))
                    Text("\(Int(left.rounded(.up)))")
                        .font(.system(size: fontSize, weight: .bold).monospacedDigit())
                        .foregroundStyle(.white)
                } else {
                    SpinnerRing(date: timeline.date, lineWidth: lineWidth, color: color)
                }
            }
        }
    }
}

/// Anel que se enche até [fraction] (0…1), a começar em cima.
struct ProgressRing: View {
    let fraction: Double
    var lineWidth: CGFloat = 4.5
    var color: AnyShapeStyle = AnyShapeStyle(Color.white)

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: min(1, max(0, fraction)))
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(lineWidth / 2)
    }
}

/// Anel a rodar (uma volta a cada 0,9 s): a ligar, sem se saber quanto falta.
struct SpinnerRing: View {
    let date: Date
    var lineWidth: CGFloat = 4.5
    var color: Color = .white

    var body: some View {
        let turn = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9 * 360
        ZStack {
            Circle().stroke(Color.white.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: 100.0 / 360)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(turn - 90))
        }
        .padding(lineWidth / 2)
    }
}

/// No canto do vídeo: há uma versão nova. Mostra até três novidades das notas da versão e desaparece sozinho
/// ([hidesIn] segundos). Na Apple TV não se instala por dentro da app: o rodapé diz onde (Definições → Atualizar).
struct UpdateCard: View {
    @Environment(AppModel.self) private var model
    let notice: AppModel.UpdateNotice
    let hidesIn: Int

    var body: some View {
        let s = model.strings
        let accent = Theme.accent(model.prefs.accent)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 20) {
                AppIconMark()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.r("update_ready_label").uppercased(with: s.locale))
                        .font(.system(size: 19, weight: .bold))
                        .tracking(2.4)
                        .foregroundStyle(Theme.textDim)
                    Text(s.r("update_whats_new", notice.version))
                        .font(.system(size: 32, weight: .semibold).monospacedDigit())
                        .lineLimit(1)
                }
            }
            if !notice.highlights.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(notice.highlights.enumerated()), id: \.offset) { _, line in
                        HStack(alignment: .top, spacing: 20) {
                            Circle().fill(accent).frame(width: 10, height: 10).padding(.top, 11)
                            Text(line)
                                .font(.system(size: 24))
                                .foregroundStyle(Theme.text.opacity(0.85))
                                .lineLimit(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(.top, 20)
            }
            Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1).padding(.top, 24)
            HStack(spacing: 20) {
                // No Android este anel enche-se a manter OK; aqui conta o tempo até o cartão sair.
                ZStack {
                    ProgressRing(fraction: Double(hidesIn) / Double(updateCardSeconds), lineWidth: 3.5)
                        .animation(.linear(duration: 1), value: hidesIn)
                    Image(systemName: "arrow.down").font(.system(size: 18, weight: .bold))
                }
                .frame(width: 48, height: 48)
                Text(s.r("update_install_settings"))
                    .font(.system(size: 24, weight: .semibold))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 12)
                Text(s.r("update_hides_in", "\(hidesIn)"))
                    .font(.system(size: 22).monospacedDigit())
                    .foregroundStyle(Theme.textDim)
                    .fixedSize()
            }
            .padding(.top, 20)
        }
        .padding(.horizontal, 36)
        .padding(.vertical, 32)
        .frame(width: 680, alignment: .leading)
        .glass(radius: 34)
    }
}

/// O ícone da app: o logo GOAT TV (o mesmo da abertura, já parado) ampliado no centro, sobre o fundo dele.
struct AppIconMark: View {
    var body: some View {
        Canvas { context, size in
            // O logo ocupa 72% da largura do desenho e as letras só uma parte dele: desenha-se maior e corta-se.
            let big = size.width * 2.8
            context.translateBy(x: (size.width - big) / 2, y: (size.height - big) / 2)
            GoatLogoView.draw(&context, size: CGSize(width: big, height: big), t: 10, intro: true)
        }
    }
}
