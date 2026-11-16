import GoatCore
import SwiftUI

/// O que a TV e o iPhone partilham no Sports Hub: nomes, horas, cores e o círculo das equipas.
enum SportsUI {
    static let background = hex(0x07090E)
    static let panel = hex(0x121722)
    static let raised = hex(0x1A202C)
    static let dim = hex(0x9AA4B5)
    static let soft = hex(0xC9D1DD)
    static let liveRed = hex(0xEF4444)
    static let liveText = hex(0xFCA5A5)
    static let nextText = hex(0x7DD3FC)

    /// O nome do desporto (os textos do Android: `sport_football`…).
    static func sportName(_ sport: String, _ s: Strings) -> String {
        switch sport {
        case "football": s.r("sport_football")
        case "basketball": s.r("sport_basketball")
        case "amfootball": s.r("sport_amfootball")
        case "hockey": s.r("sport_hockey")
        case "baseball": s.r("sport_baseball")
        case "tennis": s.r("sport_tennis")
        case "motor": s.r("sport_motor")
        case "fight": s.r("sport_fight")
        case "rugby": s.r("sport_rugby")
        case "cricket": s.r("sport_cricket")
        case "handball": s.r("sport_handball")
        case "volleyball": s.r("sport_volleyball")
        case "golf": s.r("sport_golf")
        default: s.r("sport_other")
        }
    }

    /// "Hoje 22:15" ou "Amanhã 15:00".
    static func when(_ start: Int64, now: Int64, _ s: Strings) -> String {
        let cal = Calendar.current
        let a = Date(timeIntervalSince1970: TimeInterval(now) / 1000)
        let b = Date(timeIntervalSince1970: TimeInterval(start) / 1000)
        let time = hhmm(b)
        return cal.isDate(a, inSameDayAs: b) || b < a ? s.r("sports_today", time) : s.r("sports_tomorrow", time)
    }

    /// O fundo do jogo em destaque, pela cor do desporto.
    static func gradient(_ sport: String) -> LinearGradient {
        let colors: [UInt32] = switch sport {
        case "football": [0x0F3D24, 0x14532D, 0x0B1220]
        case "basketball": [0x5A240C, 0x7C2D12, 0x0B1220]
        case "tennis": [0x3F4A0C, 0x4D5E10, 0x0B1220]
        case "motor": [0x4A0D0D, 0x7F1D1D, 0x0B1220]
        case "hockey", "amfootball", "baseball": [0x0C2A4A, 0x1E3A8A, 0x0B1220]
        case "fight": [0x3B0A2A, 0x701A4A, 0x0B1220]
        default: [0x1E293B, 0x334155, 0x0B1220]
        }
        return LinearGradient(colors: colors.map(hex), startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private static func hex(_ rgb: UInt32) -> Color {
        Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/// O círculo com as iniciais da equipa (o guia não traz logótipos das equipas).
struct TeamBadge: View {
    let name: String
    let size: CGFloat

    var body: some View {
        let argb = SportsEngine.color(name)
        Circle()
            .fill(Color(red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255, blue: Double(argb & 0xFF) / 255))
            .frame(width: size, height: size)
            .overlay(Text(SportsEngine.initials(name)).font(.system(size: size * 0.3, weight: .heavy)).foregroundStyle(.white))
    }
}
