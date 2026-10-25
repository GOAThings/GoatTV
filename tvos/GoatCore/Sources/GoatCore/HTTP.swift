import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Alguns servidores IPTV bloqueiam User-Agents desconhecidos: usa-se o do VLC, como no Android.
public let iptvUserAgent = "VLC/3.0.20 LibVLC/3.0.20"

public enum HTTP {
    public static func makeSession(timeout: TimeInterval = 30) -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = 300
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        return URLSession(configuration: config)
    }

    /// Faz o pedido e devolve o corpo e a resposta. Erros de rede saem como [SourceError.offline].
    public static func fetch(_ session: URLSession, _ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response): (Data, URLResponse)
        do {
            #if canImport(FoundationNetworking)
            (data, response) = try await withCheckedThrowingContinuation { cont in
                session.dataTask(with: request) { data, response, error in
                    if let error {
                        cont.resume(throwing: error)
                    } else if let response {
                        cont.resume(returning: (data ?? Data(), response))
                    } else {
                        cont.resume(throwing: URLError(.badServerResponse))
                    }
                }.resume()
            }
            #else
            (data, response) = try await session.data(for: request)
            #endif
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            throw SourceError.offline
        }
        guard let http = response as? HTTPURLResponse else { throw SourceError.badResponse }
        return (data, http)
    }
}
