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

    /// Faz o pedido e devolve o corpo e a resposta. Erros de rede saem como [SourceError.offline]; um corpo maior do
    /// que [maxBytes] sai como [SourceError.badResponse], sem o guardar todo em memória.
    public static func fetch(_ session: URLSession, _ request: URLRequest, maxBytes: Int? = nil) async throws -> (Data, HTTPURLResponse) {
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
            if let maxBytes {
                (data, response) = try await LimitedDownload(maxBytes: maxBytes).run(session, request)
            } else {
                (data, response) = try await session.data(for: request)
            }
            #endif
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as SourceError {
            throw error
        } catch {
            throw SourceError.offline
        }
        if let maxBytes, data.count > maxBytes { throw SourceError.badResponse }
        guard let http = response as? HTTPURLResponse else { throw SourceError.badResponse }
        return (data, http)
    }
}

#if !canImport(FoundationNetworking)
/// Descarrega um corpo para memória e para assim que passa do limite (o `data(for:)` só o devolve depois de ler tudo).
/// Tem uma sessão só para o pedido, com a mesma configuração: é com o delegate da sessão que o corpo chega aos bocados.
private final class LimitedDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let maxBytes: Int
    // Só se mexe nisto na fila do delegate, que é em série; a continuação é posta antes de a tarefa começar.
    private var body = Data()
    private var tooLarge = false
    private var continuation: CheckedContinuation<(Data, URLResponse), Error>?

    init(maxBytes: Int) { self.maxBytes = maxBytes }

    func run(_ base: URLSession, _ request: URLRequest) async throws -> (Data, URLResponse) {
        let session = URLSession(configuration: base.configuration, delegate: self, delegateQueue: nil)
        let task = session.dataTask(with: request)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Data, URLResponse), Error>) in
                self.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !tooLarge else { return }
        if body.count + data.count > maxBytes {
            tooLarge = true
            body = Data()
            dataTask.cancel()
        } else {
            body.append(data)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        session.finishTasksAndInvalidate()
        let result: Result<(Data, URLResponse), Error>
        if tooLarge {
            result = .failure(SourceError.badResponse)
        } else if let error {
            result = .failure(error)
        } else if let response = task.response {
            result = .success((body, response))
        } else {
            result = .failure(URLError(.badServerResponse))
        }
        continuation?.resume(with: result)
        continuation = nil
    }
}
#endif
