import TVServices
import UIKit

/// A fila do GoatTV no ecrã principal da Apple TV: os favoritos e os recentes da lista em uso, que a app deixa em
/// topshelf.json no App Group ao sair. Cada cartão abre o canal (goattv://play?playlist=…&channel=…).
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        Task { completionHandler(await Self.content()) }
    }

    private struct Shelf: Decodable {
        struct Section: Decodable {
            let title: String
            let items: [Entry]
        }
        struct Entry: Decodable {
            let key: String
            let name: String
            let logo: String?
            let now: String?
        }
        let playlist: String
        let sections: [Section]
    }

    private static func content() async -> TVTopShelfContent? {
        guard let dir = AppGroup.container()?.url else {
            NSLog("GoatTV TopShelf: sem App Group")
            return nil
        }
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("topshelf.json")),
              let shelf = try? JSONDecoder().decode(Shelf.self, from: data) else {
            NSLog("GoatTV TopShelf: sem topshelf.json em %@", dir.path)
            report("no-data", in: dir)
            return nil
        }
        NSLog("GoatTV TopShelf: %d secções", shelf.sections.count)
        report("ok", in: dir)
        let cards = dir.appendingPathComponent("topshelf-cards", isDirectory: true)
        try? FileManager.default.createDirectory(at: cards, withIntermediateDirectories: true)

        var sections: [TVTopShelfItemCollection<TVTopShelfSectionedItem>] = []
        for section in shelf.sections where !section.items.isEmpty {
            var items: [TVTopShelfSectionedItem] = []
            for entry in section.items {
                let item = TVTopShelfSectionedItem(identifier: "\(section.title)|\(entry.key)")
                item.title = entry.now.map { "\(entry.name) · \($0)" } ?? entry.name
                item.imageShape = .hdtv
                if let card = await card(entry, in: cards) {
                    item.setImageURL(card, for: [.screenScale1x, .screenScale2x])
                }
                var link = URLComponents()
                link.scheme = "goattv"
                link.host = "play"
                link.queryItems = [URLQueryItem(name: "playlist", value: shelf.playlist), URLQueryItem(name: "channel", value: entry.key)]
                if let url = link.url {
                    item.displayAction = TVTopShelfAction(url: url)
                    item.playAction = TVTopShelfAction(url: url)
                }
                items.append(item)
            }
            let collection = TVTopShelfItemCollection(items: items)
            collection.title = section.title
            sections.append(collection)
        }
        return sections.isEmpty ? nil : TVTopShelfSectionedContent(sections: sections)
    }

    /// Deixa na pasta partilhada quando correu e como (a app mostra-o em Definições → Conta → Top Shelf).
    private static func report(_ status: String, in dir: URL) {
        let json = ["status": status, "time": ISO8601DateFormatter().string(from: Date())]
        if let data = try? JSONSerialization.data(withJSONObject: json) {
            try? data.write(to: dir.appendingPathComponent(AppGroup.statusFile), options: .atomic)
        }
    }

    /// O cartão 16:9 do canal: o logótipo ao centro sobre o fundo escuro do GoatTV (os logótipos são quase sempre
    /// transparentes e de proporções várias); sem logótipo, o nome.
    private static func card(_ entry: Shelf.Entry, in dir: URL) async -> URL? {
        var logo: UIImage?
        if let text = entry.logo, let url = URL(string: text) {
            var request = URLRequest(url: url)
            request.timeoutInterval = 8
            if let (data, _) = try? await URLSession.shared.data(for: request) { logo = UIImage(data: data) }
        }
        let size = CGSize(width: 808, height: 454)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let colors = [UIColor(red: 0.08, green: 0.10, blue: 0.17, alpha: 1).cgColor,
                          UIColor(red: 0.02, green: 0.03, blue: 0.06, alpha: 1).cgColor]
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1]) {
                context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            if let logo, logo.size.width > 0, logo.size.height > 0 {
                let box = CGRect(origin: .zero, size: size).insetBy(dx: 160, dy: 100)
                let scale = min(box.width / logo.size.width, box.height / logo.size.height)
                let w = logo.size.width * scale, h = logo.size.height * scale
                logo.draw(in: CGRect(x: box.midX - w / 2, y: box.midY - h / 2, width: w, height: h))
            } else {
                let style = NSMutableParagraphStyle()
                style.alignment = .center
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: 64, weight: .bold), .foregroundColor: UIColor.white, .paragraphStyle: style,
                ]
                let name = entry.name as NSString
                let width = size.width - 100
                let bounds = name.boundingRect(with: CGSize(width: width, height: size.height), options: .usesLineFragmentOrigin,
                                               attributes: attributes, context: nil)
                name.draw(with: CGRect(x: 50, y: (size.height - bounds.height) / 2, width: width, height: bounds.height),
                          options: .usesLineFragmentOrigin, attributes: attributes, context: nil)
            }
        }
        guard let png = image.pngData() else { return nil }
        let file = dir.appendingPathComponent((entry.key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "channel") + ".png")
        do {
            try png.write(to: file, options: .atomic)
            return file
        } catch {
            return nil
        }
    }
}
