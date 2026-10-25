import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Ligar a TV à conta: código e QR para aprovar no telemóvel (o mesmo processo do GoatTV Android).
struct ActivationView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let s = model.strings
        let a = model.activation
        HStack(spacing: 120) {
            VStack(alignment: .leading, spacing: 34) {
                BrandMark(scale: 0.55)
                Text(s.activateTitle).font(.title2.bold())
                Text(s.activateStep1(a.url ?? "thegoat.systems")).font(.title3).foregroundStyle(Theme.textDim)
                Text(s.activateStep2).font(.title3).foregroundStyle(Theme.textDim)
                Text(a.code ?? "······")
                    .font(.system(size: 110, weight: .bold, design: .monospaced))
                    .kerning(10)
                    .foregroundStyle(Theme.accent)
                if let error = a.error {
                    Text(error).font(.headline).foregroundStyle(Theme.error)
                } else {
                    HStack(spacing: 16) {
                        ProgressView()
                        Text(s.activateWaiting).foregroundStyle(Theme.textFaint)
                    }
                }
                if let expires = a.expires {
                    // "Expira em 9:42. Aparece outro sozinho." (como no Android).
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let left = max(0, Int(expires.timeIntervalSince(context.date)))
                        Text(s.r("activate_expires_text", "\(left / 60)").replacingOccurrences(of: "%2$02d", with: String(format: "%02d", left % 60)))
                            .font(.callout).foregroundStyle(Theme.textFaint)
                    }
                }
                Text(s.activatePlaylistsHint).font(.callout).foregroundStyle(Theme.textFaint)
            }
            .frame(maxWidth: 900, alignment: .leading)

            VStack(spacing: 24) {
                if let qr = a.qrText.flatMap(QRCode.image) {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 420, height: 420)
                        .padding(24)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 28))
                } else {
                    RoundedRectangle(cornerRadius: 28).fill(Color.white.opacity(0.08)).frame(width: 468, height: 468)
                }
                Text(s.activateScan).foregroundStyle(Theme.textFaint)
            }
        }
        .padding(100)
    }
}

enum QRCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
