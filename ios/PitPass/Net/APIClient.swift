import Foundation

/// How the client reaches the network — a protocol so tests can script
/// responses without a server.
protocol HTTPTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPTransport {}

enum APIError: Error, LocalizedError, Equatable {
    /// 401: no valid session or token. The app treats it as "signed out".
    case unauthorized
    /// 403: signed in but not allowed (removed from the roster, or a viewer
    /// touching an admin endpoint).
    case forbidden
    case http(status: Int, message: String?)
    case transport(String)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .unauthorized: "Not signed in"
        case .forbidden: "Not allowed"
        case let .http(status, message): message ?? "Request failed (\(status))"
        case let .transport(text): text
        case let .decoding(text): "Unexpected response: \(text)"
        }
    }
}

/// Plain HTTP against the Pit Pass API. Every request carries the device
/// token as a bearer when one exists; nothing is cached at this layer (no
/// URL cache, no cookies) — caching is DataLoader + OfflineStore's job, on
/// purpose, so what's on disk is exactly what the app decided to keep.
struct APIClient: Sendable {
    enum GetResult: Sendable {
        case ok(Data, etag: String?)
        case notModified
    }

    let baseURL: URL
    let token: @Sendable () -> String?
    let transport: any HTTPTransport

    init(baseURL: URL, token: @escaping @Sendable () -> String?, transport: (any HTTPTransport)? = nil) {
        self.baseURL = baseURL
        self.token = token
        self.transport = transport ?? APIClient.makeSession()
    }

    private static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        // Fail fast rather than hang on paddock Wi-Fi; the cache answers meanwhile.
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }

    /// Conditional GET. Pass the stored ETag and the server answers 304 when
    /// nothing changed (ApiEtagConfig stamps weak content-hash ETags on JSON).
    func get(_ path: String, ifNoneMatch etag: String? = nil) async throws(APIError) -> GetResult {
        var request = makeRequest("GET", path)
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await perform(request)
        if response.statusCode == 304 { return .notModified }
        try check(response, data)
        return .ok(data, etag: response.value(forHTTPHeaderField: "ETag"))
    }

    func getJSON<T: Decodable>(_ path: String) async throws(APIError) -> T {
        guard case let .ok(data, _) = try await get(path) else {
            throw APIError.decoding("unexpected 304")
        }
        return try decode(data)
    }

    func postJSON<T: Decodable>(_ path: String, body: some Encodable & Sendable) async throws(APIError) -> T {
        var request = makeRequest("POST", path)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        do {
            request.httpBody = try JSONEncoder().encode(body)
        } catch {
            throw APIError.decoding(error.localizedDescription)
        }
        let (data, response) = try await perform(request)
        try check(response, data)
        return try decode(data)
    }

    @discardableResult
    func send(_ method: String, _ path: String) async throws(APIError) -> Data {
        let (data, response) = try await perform(makeRequest(method, path))
        try check(response, data)
        return data
    }

    /// Heartbeat: how long a HEAD takes, or throws. 502/503/504 count as down
    /// (that's what a proxy answers for a dead backend).
    func head(_ path: String, timeout: TimeInterval) async throws(APIError) -> TimeInterval {
        var request = makeRequest("HEAD", path)
        request.timeoutInterval = timeout
        let started = Date()
        let (_, response) = try await perform(request)
        if [502, 503, 504].contains(response.statusCode) {
            throw APIError.http(status: response.statusCode, message: "Backend unavailable")
        }
        return Date().timeIntervalSince(started)
    }

    // MARK: - plumbing

    private func makeRequest(_ method: String, _ path: String) -> URLRequest {
        var request = URLRequest(url: URL(string: path, relativeTo: baseURL)!.absoluteURL)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = token() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func perform(_ request: URLRequest) async throws(APIError) -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await transport.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw APIError.transport("Not an HTTP response")
            }
            return (data, http)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.transport(error.localizedDescription)
        }
    }

    private func check(_ response: HTTPURLResponse, _ data: Data) throws(APIError) {
        switch response.statusCode {
        case 200..<300: return
        case 401: throw APIError.unauthorized
        case 403: throw APIError.forbidden
        default:
            // Spring's error body carries `message` (server.error.include-message: always).
            let message = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.message
            throw APIError.http(status: response.statusCode, message: message)
        }
    }

    private struct ErrorBody: Decodable {
        let message: String?
    }

    func decode<T: Decodable>(_ data: Data) throws(APIError) -> T {
        do {
            return try JSONDecoder.api.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(error.localizedDescription)
        }
    }
}

extension JSONDecoder {
    /// The API writes ISO-8601 timestamps with fractional seconds and an offset.
    static let api: JSONDecoder = {
        let decoder = JSONDecoder()
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = withFraction.date(from: text) ?? plain.date(from: text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath,
                                                    debugDescription: "Bad date: \(text)"))
        }
        return decoder
    }()
}
