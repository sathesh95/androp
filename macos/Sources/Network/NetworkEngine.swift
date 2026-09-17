import Foundation
import Network

public enum ConnectionStatus: String {
    case disconnected = "Disconnected"
    case connecting = "Connecting..."
    case relayConnected = "Connected (Cloudflare Relay)"
    case lanConnected = "Connected (Direct LAN)"
}

public protocol NetworkEngineDelegate: AnyObject {
    func connectionStatusDidChange(_ status: ConnectionStatus)
    func receivedNewClipboardText(_ text: String)
}

public final class NetworkEngine: NSObject, URLSessionWebSocketDelegate {
    public static let shared = NetworkEngine()
    
    public weak var delegate: NetworkEngineDelegate?
    
    private(set) var status: ConnectionStatus = .disconnected {
        didSet {
            DispatchQueue.main.async {
                self.delegate?.connectionStatusDidChange(self.status)
            }
        }
    }
    
    private let deviceId: String = "mac-\(UUID().uuidString.prefix(8))"
    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var pingTimer: Timer?
    private var reconnectTimer: Timer?
    private var isIntentionalDisconnect = false
    
    // Thread-safe serial queue for LAN network operations
    private let lanQueue = DispatchQueue(label: "com.clipboardsync.lanQueue")
    private var nwListener: NWListener?
    private var nwBrowser: NWBrowser?
    private var activeLanConnections: [NWConnection] = []
    
    private override init() {
        super.init()
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        self.urlSession = URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue())
    }
    
    public func start() {
        isIntentionalDisconnect = false
        connectRelay()
        startLanListener()
        startLanBrowser()
    }
    
    public func stop() {
        isIntentionalDisconnect = true
        disconnectRelay()
        stopLanListener()
        stopLanBrowser()
    }
    
    public func reloadConfiguration() {
        stop()
        start()
    }
    
    // MARK: - Cloudflare WebSocket Relay
    
    private func connectRelay() {
        guard let roomId = CryptoEngine.shared.roomId else { return }
        let relayBase = CryptoEngine.shared.relayUrl
        
        if relayBase.contains("your-subdomain") {
            print("[NetworkEngine] Default placeholder relay detected. Operating in Local LAN Direct mode. (To enable cloud sync anywhere, deploy the Cloudflare Worker and set the URL in Settings).")
            status = .disconnected
            return
        }
        
        guard var components = URLComponents(string: relayBase) else { return }
        components.queryItems = [URLQueryItem(name: "room", value: roomId)]
        guard let url = components.url else { return }
        
        status = .connecting
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        
        webSocketTask = urlSession?.webSocketTask(with: url)
        webSocketTask?.resume()
        
        listenForWebSocketMessages()
        startPingTimer()
    }
    
    private func disconnectRelay() {
        pingTimer?.invalidate()
        pingTimer = nil
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        status = .disconnected
    }
    
    private func listenForWebSocketMessages() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }
            
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleIncomingJSON(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleIncomingJSON(text)
                    }
                @unknown default:
                    break
                }
                
                // Continue receiving
                self.listenForWebSocketMessages()
                
            case .failure(let error):
                print("[NetworkEngine] WebSocket receive failed: \(error.localizedDescription)")
                self.handleDisconnect()
            }
        }
    }
    
    private func handleIncomingJSON(_ jsonString: String) {
        guard let data = jsonString.data(using: .utf8) else { return }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        
        let type = json["type"] as? String ?? ""
        
        if type == "PONG" {
            if self.status != .lanConnected {
                self.status = .relayConnected
            }
            return
        }
        
        if type == "SYNC" || type == "LATEST_DATA" {
            guard let ciphertext = json["ciphertext"] as? String,
                  let iv = json["iv"] as? String,
                  let hash = json["hash"] as? String else { return }
            
            let originDeviceId = json["originDeviceId"] as? String ?? ""
            if originDeviceId == self.deviceId {
                // Ignore our own echo
                return
            }
            
            if ClipboardWatcher.shared.isHashKnown(hash) {
                return
            }
            
            do {
                let (decryptedText, decryptedHash) = try CryptoEngine.shared.decrypt(ciphertextBase64: ciphertext, ivBase64: iv)
                print("[NetworkEngine] Successfully decrypted payload from Android: '\(decryptedText.prefix(30))...'")
                ClipboardWatcher.shared.writeToPasteboard(text: decryptedText, hash: decryptedHash)
                DispatchQueue.main.async {
                    self.delegate?.receivedNewClipboardText(decryptedText)
                }
            } catch {
                print("[NetworkEngine] Decryption failed: \(error)")
            }
        }
    }
    
    public func broadcastClipboard(text: String, hash: String) {
        do {
            let (ciphertext, iv, computedHash) = try CryptoEngine.shared.encrypt(plainText: text)
            guard let roomId = CryptoEngine.shared.roomId else { return }
            
            let payload: [String: Any] = [
                "type": "SYNC",
                "roomId": roomId,
                "ciphertext": ciphertext,
                "iv": iv,
                "hash": computedHash,
                "originDeviceId": deviceId,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            
            guard let jsonData = try? JSONSerialization.data(withJSONObject: payload),
                  let jsonString = String(data: jsonData, encoding: .utf8) else { return }
            
            // 1. Send via Cloudflare WebSocket
            if webSocketTask?.state == .running {
                webSocketTask?.send(.string(jsonString)) { error in
                    if let error = error {
                        print("[NetworkEngine] Relay send error: \(error)")
                    }
                }
            }
            
            // 2. Send via Direct LAN sockets
            broadcastOverLan(jsonString: jsonString)
            
        } catch {
            print("[NetworkEngine] Encryption error: \(error)")
        }
    }
    
    private func handleDisconnect() {
        if isIntentionalDisconnect { return }
        self.status = .disconnected
        
        // Schedule auto-reconnect
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.reconnectTimer?.invalidate()
            self.reconnectTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { [weak self] _ in
                self?.connectRelay()
            }
        }
    }
    
    private func startPingTimer() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if self.webSocketTask?.state == .running {
                let pingMsg = "{\"type\":\"PING\"}"
                self.webSocketTask?.send(.string(pingMsg)) { _ in }
            }
        }
    }
    
    // MARK: - URLSessionWebSocketDelegate
    
    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("[NetworkEngine] Connected to Cloudflare Relay")
        self.status = .relayConnected
        
        // Request latest catchup buffer upon connect
        guard let roomId = CryptoEngine.shared.roomId else { return }
        let catchupMsg = "{\"type\":\"GET_LATEST\",\"roomId\":\"\(roomId)\"}"
        webSocketTask.send(.string(catchupMsg)) { _ in }
    }
    
    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        print("[NetworkEngine] Relay closed")
        handleDisconnect()
    }
    
    // MARK: - Bonjour LAN Direct P2P
    
    private func startLanListener() {
        do {
            let parameters = NWParameters.tcp
            nwListener = try NWListener(using: parameters)
            nwListener?.service = NWListener.Service(name: "ClipboardSync-\(deviceId)", type: "_clipboardsync._tcp")
            
            nwListener?.newConnectionHandler = { [weak self] newConnection in
                self?.handleNewLanConnection(newConnection)
            }
            
            nwListener?.start(queue: lanQueue)
        } catch {
            print("[NetworkEngine] Failed to start Bonjour listener: \(error)")
        }
    }
    
    private func stopLanListener() {
        lanQueue.sync {
            nwListener?.cancel()
            nwListener = nil
            for conn in activeLanConnections {
                conn.cancel()
            }
            activeLanConnections.removeAll()
        }
    }
    
    private func startLanBrowser() {
        let parameters = NWParameters.tcp
        nwBrowser = NWBrowser(for: .bonjour(type: "_clipboardsync._tcp", domain: nil), using: parameters)
        
        nwBrowser?.browseResultsChangedHandler = { [weak self] results, changes in
            guard let self = self else { return }
            for result in results {
                if case .service(let name, _, _, _) = result.endpoint {
                    if !name.contains(self.deviceId) {
                        self.connectToLanPeer(endpoint: result.endpoint)
                    }
                }
            }
        }
        
        nwBrowser?.start(queue: lanQueue)
    }
    
    private func stopLanBrowser() {
        lanQueue.sync {
            nwBrowser?.cancel()
            nwBrowser = nil
        }
    }
    
    private func connectToLanPeer(endpoint: NWEndpoint) {
        let connection = NWConnection(to: endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                print("[NetworkEngine] Connected to local LAN peer!")
                self.status = .lanConnected
                self.receiveLanData(from: connection)
            case .failed, .cancelled:
                self.lanQueue.async {
                    self.activeLanConnections.removeAll(where: { $0 === connection })
                    if self.activeLanConnections.isEmpty && self.status == .lanConnected {
                        self.status = .disconnected
                    }
                }
            default:
                break
            }
        }
        connection.start(queue: lanQueue)
        lanQueue.async {
            self.activeLanConnections.append(connection)
        }
    }
    
    private func handleNewLanConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                print("[NetworkEngine] Incoming Android LAN connection established and ready!")
                self.status = .lanConnected
            case .failed, .cancelled:
                self.lanQueue.async {
                    self.activeLanConnections.removeAll(where: { $0 === connection })
                    if self.activeLanConnections.isEmpty && self.status == .lanConnected {
                        self.status = .disconnected
                    }
                }
            default:
                break
            }
        }
        connection.start(queue: lanQueue)
        lanQueue.async {
            self.activeLanConnections.append(connection)
            self.status = .lanConnected
        }
        
        self.receiveLanData(from: connection)
    }
    
    private func receiveLanData(from connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, isComplete, error in
            guard let self = self else { return }
            if let data = content, let text = String(data: data, encoding: .utf8) {
                let lines = text.components(separatedBy: .newlines)
                for line in lines where !line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    self.handleIncomingJSON(line)
                }
            }
            if isComplete || error != nil {
                connection.cancel()
                self.lanQueue.async {
                    self.activeLanConnections.removeAll(where: { $0 === connection })
                    if self.activeLanConnections.isEmpty && self.status == .lanConnected {
                        self.status = .disconnected
                    }
                }
            } else {
                self.receiveLanData(from: connection)
            }
        }
    }
    
    private func broadcastOverLan(jsonString: String) {
        let framedString = jsonString.hasSuffix("\n") ? jsonString : jsonString + "\n"
        guard let data = framedString.data(using: .utf8) else { return }
        
        let targets = lanQueue.sync { self.activeLanConnections }
        for conn in targets {
            conn.send(content: data, completion: .contentProcessed({ _ in }))
        }
    }
}
