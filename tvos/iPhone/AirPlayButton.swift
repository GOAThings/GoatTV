import AVKit
import SwiftUI

/// O botão do AirPlay (o do sistema): escolhe para onde vai o som (colunas, auscultadores, uma Apple TV).
///
/// O GoatCore desenha a imagem numa AVSampleBufferDisplayLayer e toca o som num AVSampleBufferAudioRenderer: o som
/// segue a saída escolhida na sessão de áudio (o AirPlay de som funciona, também nas rádios e com o ecrã bloqueado), e a
/// imagem fica no iPhone, acertada com o som pelo relógio comum. Mandar a imagem para uma Apple TV precisava do
/// AVPlayer (que não lê estes streams); para ver o vídeo na TV há a Duplicação do ecrã do centro de controlo.
/// Por isso a lista não põe as Apple TV à frente das colunas.
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        picker.tintColor = .white
        picker.activeTintColor = UIColor(red: 0x38 / 255, green: 0xBD / 255, blue: 0xF8 / 255, alpha: 1)
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
