import SwiftUI

/*
 * Os avisos nos cantos do player, iguais aos do GoatTV Android (PlayerOverlays.TuningPill e UpdateCard): a cápsula a
 * sintonizar / a voltar a ligar e o cartão de versão nova. Só na Apple TV (o iPhone terá o seu desenho).
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

    var body: some View {
        let s = model.strings
        // O nome não estica a cápsula: um nome muito comprido fica cortado (no Android, 220 dp no máximo).
        let name = channel.count > 34 ? String(channel.prefix(33)) + "…" : channel
        HStack(spacing: 20) {
            TimelineView(.animation) { timeline in
                let left = nextTryAt.map { $0.timeIntervalSince(timeline.date) } ?? 0
                ZStack {
                    if left > 0, waitSeconds > 0 {
                        ProgressRing(fraction: 1 - left / waitSeconds)
                        Text("\(Int(left.rounded(.up)))")
                            .font(.system(size: 22, weight: .bold).monospacedDigit())
                    } else {
                        SpinnerRing(date: timeline.date)
                    }
                }
            }
            .frame(width: 60, height: 60)
            VStack(alignment: .leading, spacing: 2) {
                Text(reconnecting ? s.r("reconnecting") : s.r("tuning"))
                    .font(.system(size: 26, weight: .semibold))
                if attempt > 0 || !name.isEmpty {
                    Text(attempt > 0 ? s.r("reconnect_attempt", name, "\(attempt)") : name)
                        .font(.system(size: 21).monospacedDigit())
                        .foregroundStyle(Theme.textDim)
                        .lineLimit(1)
                }
            }
            .fixedSize()
        }
        .padding(.leading, 14)
        .padding(.trailing, 32)
        .padding(.vertical, 14)
        .glass(radius: 44)
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

    var body: some View {
        let turn = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9 * 360
        ZStack {
            Circle().stroke(Color.white.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: 100.0 / 360)
                .stroke(Color.white, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
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
