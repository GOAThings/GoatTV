import GoatCore
import Photos
import UIKit

/// "Guardar imagem" no leitor do iPhone: a imagem do canal que está no ecrã, sem os botões nem as legendas da app, num
/// JPEG com o nome do canal e a hora (o mesmo nome do Android, ver SavedPictureName), nas Fotografias. Pede só a
/// permissão de juntar fotografias (NSPhotoLibraryAddUsageDescription), nunca a de as ler. No fim, um aviso curto.
/// O Apple TV não tem fototeca nem ficheiros da pessoa: lá não há este botão.
@MainActor
enum PhonePicture {
    private static let quality: CGFloat = 0.92

    static func save(_ model: AppModel, channel: Channel) {
        let saved = model.strings.r("picture_saved")
        let failed = model.strings.r("picture_save_failed")
        let name = SavedPictureName.of(channel: channel.name) + ".jpg"
        let quality = Self.quality
        // A imagem refaz-se fora da thread principal (ver VideoSnapshot); o aviso volta a ela.
        model.player.snapshot { image in
            guard let image, let data = UIImage(cgImage: image).jpegData(compressionQuality: quality) else {
                Task { @MainActor in model.showToast(failed) }
                return
            }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else {
                    Task { @MainActor in model.showToast(failed) }
                    return
                }
                PHPhotoLibrary.shared().performChanges({
                    let options = PHAssetResourceCreationOptions()
                    options.originalFilename = name
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
                }, completionHandler: { done, _ in
                    Task { @MainActor in model.showToast(done ? saved : failed) }
                })
            }
        }
    }
}
