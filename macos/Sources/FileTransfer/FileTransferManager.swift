import Foundation
import AppKit
import CryptoKit
import UniformTypeIdentifiers

// MARK: - Data Models

public struct FileTransferOffer {
    public let transferId: String
    public let fileName: String
    public let fileSize: Int64
    public let mimeType: String
    let fileKey: SymmetricKey
    public let originDeviceId: String
}

public enum TransferState {
    case idle
    case awaitingAccept      // Sender: waiting for receiver to accept
    case transferring        // Either side: bytes in flight
    case complete
    case failed(String)
}

// MARK: - FileTransferManager

/// Orchestrates the full file transfer lifecycle (signaling → LAN/Internet → decrypt → save).
/// Communicates with NetworkEngine for WebSocket signaling.
public final class FileTransferManager: ObservableObject {
    public static let shared = FileTransferManager()

    @Published public var state: TransferState = .idle
    @Published public var progress: Double = 0.0
    @Published public var statusMessage: String = ""
    @Published public var incomingOffer: FileTransferOffer?

    // Outgoing state
    private var pendingTransferId: String?
    private var pendingToken: String?
    private var pendingFileURL: URL?
    private var pendingFileKey: SymmetricKey?

    // Incoming state
    private var incomingKey: SymmetricKey?
    private var incomingTransferId: String?

    // LAN server for outgoing LAN transfers
    private var lanServer: FileTransferServer?

    private init() {}

    // MARK: - Public API: Send

    /// Open an NSOpenPanel and send the picked file.
    public func promptAndSendFile() {
        let panel = NSOpenPanel()
        panel.title = "Choose a file to send"
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.allowedContentTypes = [.item]

        guard panel.runModal() == .OK, let url = panel.url else { return }
        sendFile(url)
    }

    /// Send a specific file URL.
    public func sendFile(_ url: URL) {
        guard let roomKey = CryptoEngine.shared.symmetricKey,
              let roomId  = CryptoEngine.shared.roomId else {
            setStatus(.failed("Not paired – please scan QR code first"))
            return
        }

        let transferId = UUID().uuidString
        let token      = generateToken()
        let fileKey    = FileTransferCrypto.generateFileKey()

        pendingTransferId = transferId
        pendingToken      = token
        pendingFileURL    = url
        pendingFileKey    = fileKey

        guard let (encKeyB64, keyIvB64) = try? FileTransferCrypto.encryptFileKey(fileKey, with: roomKey) else {
            setStatus(.failed("Key encryption failed")); return
        }

        let attrs    = (try? FileManager.default.attributesOfItem(atPath: url.path))
        let fileSize = attrs?[.size] as? Int64 ?? 0
        let fileName = url.lastPathComponent
        let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                       ?? "application/octet-stream"

        let offer: [String: Any] = [
            "type":             "FILE_OFFER",
            "roomId":           roomId,
            "transferId":       transferId,
            "fileName":         fileName,
            "fileSize":         fileSize,
            "mimeType":         mimeType,
            "encryptedFileKey": encKeyB64,
            "fileKeyIv":        keyIvB64,
            "originDeviceId":   NetworkEngine.shared.publicDeviceId,
            "timestamp":        Int64(Date().timeIntervalSince1970 * 1000)
        ]

        NetworkEngine.shared.broadcastSignal(offer)
        setStatus(.awaitingAccept)
        setMsg("Waiting for \"\(fileName)\" to be accepted…")
    }

    // MARK: - Public API: Receive Accept/Reject

    public func acceptOffer() {
        guard let transferId = incomingTransferId,
              let roomId     = CryptoEngine.shared.roomId else { return }

        let accept: [String: Any] = [
            "type":           "FILE_ACCEPT",
            "roomId":         roomId,
            "transferId":     transferId,
            "originDeviceId": NetworkEngine.shared.publicDeviceId
        ]
        NetworkEngine.shared.broadcastSignal(accept)

        DispatchQueue.main.async {
            self.incomingOffer = nil
            self.setMsg("Waiting for transfer to start…")
            self.setStatus(.transferring)
        }
    }

    public func rejectOffer() {
        guard let transferId = incomingTransferId,
              let roomId     = CryptoEngine.shared.roomId else { return }

        let reject: [String: Any] = [
            "type":           "FILE_REJECT",
            "roomId":         roomId,
            "transferId":     transferId,
            "originDeviceId": NetworkEngine.shared.publicDeviceId
        ]
        NetworkEngine.shared.broadcastSignal(reject)
        resetIncoming()
    }

    // MARK: - Signal Routing (called by NetworkEngine)

    public func handleSignal(_ json: [String: Any]) {
        guard let type = json["type"] as? String else { return }

        switch type {
        case "FILE_OFFER":         handleIncomingOffer(json)
        case "FILE_ACCEPT":        handleSenderGotAccept(json)
        case "FILE_REJECT":        handleSenderGotReject()
        case "FILE_LAN_READY":     handleReceiverStartLan(json)
        case "FILE_INTERNET_READY":handleReceiverStartInternet(json)
        case "FILE_PROGRESS":      handleProgressUpdate(json)
        case "FILE_COMPLETE":      handleSenderComplete()
        case "FILE_ERROR":         handleRemoteError(json)
        default: break
        }
    }

    // MARK: - Incoming: FILE_OFFER

    private func handleIncomingOffer(_ json: [String: Any]) {
        guard let roomKey      = CryptoEngine.shared.symmetricKey,
              let transferId   = json["transferId"]       as? String,
              let fileName     = json["fileName"]         as? String,
              let fileSize     = json["fileSize"]         as? Int64,
              let mimeType     = json["mimeType"]         as? String,
              let encKeyB64    = json["encryptedFileKey"] as? String,
              let keyIvB64     = json["fileKeyIv"]        as? String,
              let originDevice = json["originDeviceId"]   as? String else { return }

        // Ignore echo of our own offers
        guard originDevice != NetworkEngine.shared.publicDeviceId else { return }

        guard let fileKey = try? FileTransferCrypto.decryptFileKey(
            ciphertextBase64: encKeyB64, ivBase64: keyIvB64, with: roomKey
        ) else { return }

        incomingKey      = fileKey
        incomingTransferId = transferId

        let offer = FileTransferOffer(
            transferId:    transferId,
            fileName:      fileName,
            fileSize:      fileSize,
            mimeType:      mimeType,
            fileKey:       fileKey,
            originDeviceId: originDevice
        )

        DispatchQueue.main.async { self.incomingOffer = offer }
    }

    // MARK: - Sender: FILE_ACCEPT received

    private func handleSenderGotAccept(_ json: [String: Any]) {
        guard let tid = json["transferId"] as? String,
              tid == pendingTransferId else { return }
        guard let fileURL = pendingFileURL,
              let key     = pendingFileKey,
              let token   = pendingToken,
              let tid     = pendingTransferId else { return }

        setMsg("Preparing transfer…")

        // Prefer LAN if available
        if NetworkEngine.shared.status == .lanConnected {
            startLanServer(fileURL: fileURL, key: key, token: token, transferId: tid)
        } else {
            uploadToR2(fileURL: fileURL, key: key, token: token, transferId: tid)
        }
    }

    private func handleSenderGotReject() {
        resetOutgoing()
        setMsg("Transfer was declined by the receiver.")
        setStatus(.idle)
    }

    // MARK: - Receiver: FILE_LAN_READY

    private func handleReceiverStartLan(_ json: [String: Any]) {
        guard let key   = incomingKey,
              let ip    = json["ip"]    as? String,
              let port  = json["port"]  as? Int,
              let token = json["token"] as? String,
              let fname = incomingOffer?.fileName ?? (json["fileName"] as? String) else { return }

        let destinationURL = downloadsDirectory().appendingPathComponent(safeFileName(fname))
        setMsg("Downloading via LAN…")
        setStatus(.transferring)

        DispatchQueue.global(qos: .userInitiated).async {
            self.downloadFromLan(
                ip: ip, port: port, token: token,
                key: key, destination: destinationURL
            )
        }
    }

    // MARK: - Receiver: FILE_INTERNET_READY

    private func handleReceiverStartInternet(_ json: [String: Any]) {
        guard let key        = incomingKey,
              let transferId = json["transferId"] as? String,
              let token      = json["token"]      as? String,
              let fname      = incomingOffer?.fileName ?? (json["fileName"] as? String) else { return }

        let relayBase  = httpBaseURL(from: CryptoEngine.shared.relayUrl)
        let downloadURL = "\(relayBase)/file/\(transferId)?token=\(token)"
        let destination = downloadsDirectory().appendingPathComponent(safeFileName(fname))

        setMsg("Downloading from cloud…")
        setStatus(.transferring)

        DispatchQueue.global(qos: .userInitiated).async {
            self.downloadFromR2(urlString: downloadURL, key: key,
                                transferId: transferId, token: token,
                                destination: destination)
        }
    }

    // MARK: - Progress / Complete / Error signals

    private func handleProgressUpdate(_ json: [String: Any]) {
        guard let received = json["bytesReceived"] as? Int64,
              let total    = incomingOffer.flatMap({ $0.fileSize == 0 ? nil : $0.fileSize }) ?? (json["fileSize"] as? Int64) else { return }
        let p = Double(received) / Double(total)
        DispatchQueue.main.async { self.progress = min(p, 1.0) }
    }

    private func handleSenderComplete() {
        lanServer?.stop()
        lanServer = nil
        resetOutgoing()
        DispatchQueue.main.async {
            self.setMsg("File delivered successfully! ✓")
            self.setStatus(.complete)
            self.progress = 1.0
        }
    }

    private func handleRemoteError(_ json: [String: Any]) {
        let reason = json["reason"] as? String ?? "Unknown error"
        setStatus(.failed(reason))
        resetOutgoing()
        resetIncoming()
    }

    // MARK: - LAN Server (outgoing)

    private func startLanServer(fileURL: URL, key: SymmetricKey, token: String, transferId: String) {
        guard let roomId = CryptoEngine.shared.roomId else { return }

        let server = FileTransferServer()
        lanServer = server

        do {
            try server.start(
                fileURL:    fileURL,
                key:        key,
                token:      token,
                onProgress: { p in DispatchQueue.main.async { self.progress = p } },
                onComplete: {
                    // FILE_COMPLETE is sent by the receiver; we just wait
                    DispatchQueue.main.async { self.setMsg("Sending…") }
                },
                onError: { [weak self] err in
                    guard let self else { return }
                    // Fallback to R2
                    print("[FileTransfer] LAN server error, falling back to R2: \(err)")
                    self.uploadToR2(fileURL: fileURL, key: key, token: token, transferId: transferId)
                }
            )

            guard let port = server.localPort else { return }
            let localIP = getLocalIPAddress() ?? "127.0.0.1"

            let ready: [String: Any] = [
                "type":           "FILE_LAN_READY",
                "roomId":         roomId,
                "transferId":     transferId,
                "token":          token,
                "ip":             localIP,
                "port":           Int(port),
                "fileName":       fileURL.lastPathComponent,
                "originDeviceId": NetworkEngine.shared.publicDeviceId
            ]
            NetworkEngine.shared.broadcastSignal(ready)
            setMsg("Sending via LAN…")

        } catch {
            uploadToR2(fileURL: fileURL, key: key, token: token, transferId: transferId)
        }
    }

    // MARK: - R2 Upload (outgoing)

    private func uploadToR2(fileURL: URL, key: SymmetricKey, token: String, transferId: String) {
        guard let roomId = CryptoEngine.shared.roomId else { return }
        setMsg("Encrypting file…")

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let tempURL = try FileTransferCrypto.encryptToTempFile(
                    sourceURL: fileURL, key: key,
                    progress: { p in DispatchQueue.main.async { self.progress = p * 0.5 } }
                )
                defer { try? FileManager.default.removeItem(at: tempURL) }

                DispatchQueue.main.async { self.setMsg("Uploading to cloud…") }

                let relayBase = self.httpBaseURL(from: CryptoEngine.shared.relayUrl)
                guard let uploadURL = URL(string: "\(relayBase)/file/\(transferId)?token=\(token)") else { return }

                var request = URLRequest(url: uploadURL)
                request.httpMethod = "PUT"
                request.setValue("application/octet-stream",  forHTTPHeaderField: "Content-Type")
                request.setValue(fileURL.lastPathComponent,   forHTTPHeaderField: "X-File-Name")

                let encSize = (try? FileManager.default.attributesOfItem(atPath: tempURL.path)[.size] as? Int64) ?? 0
                request.setValue(String(encSize), forHTTPHeaderField: "X-File-Size")

                let sema = DispatchSemaphore(value: 0)
                var uploadError: Error?
                var statusCode = 0

                let task = URLSession.shared.uploadTask(with: request, fromFile: tempURL) { _, resp, err in
                    uploadError = err
                    statusCode  = (resp as? HTTPURLResponse)?.statusCode ?? 0
                    sema.signal()
                }
                task.resume()
                sema.wait()

                if let err = uploadError { throw err }
                guard statusCode == 200 else { throw FileTransferError.uploadFailed(statusCode) }

                // Signal receiver
                let ready: [String: Any] = [
                    "type":           "FILE_INTERNET_READY",
                    "roomId":         roomId,
                    "transferId":     transferId,
                    "token":          token,
                    "fileName":       fileURL.lastPathComponent,
                    "originDeviceId": NetworkEngine.shared.publicDeviceId
                ]
                NetworkEngine.shared.broadcastSignal(ready)
                DispatchQueue.main.async {
                    self.progress = 0.5
                    self.setMsg("Waiting for receiver to download…")
                }

            } catch {
                DispatchQueue.main.async {
                    self.setStatus(.failed(error.localizedDescription))
                    self.resetOutgoing()
                }
            }
        }
    }

    // MARK: - LAN Download (incoming)

    private func downloadFromLan(
        ip: String,
        port: Int,
        token: String,
        key: SymmetricKey,
        destination: URL
    ) {
        guard let roomId = CryptoEngine.shared.roomId else { return }

        // Use a plain POSIX socket via CFStream for simplicity
        var readStream:  Unmanaged<CFReadStream>?
        var writeStream: Unmanaged<CFWriteStream>?
        CFStreamCreatePairWithSocketToHost(nil, ip as CFString, UInt32(port), &readStream, &writeStream)

        guard let inStream  = readStream?.takeRetainedValue() as? InputStream,
              let outStream = writeStream?.takeRetainedValue() as? OutputStream else {
            signalError(roomId: roomId, reason: "LAN connection failed")
            return
        }

        inStream.open()
        outStream.open()
        defer { inStream.close(); outStream.close() }

        // Send token + newline
        let tokenLine = (token + "\n").data(using: .utf8)!
        _ = tokenLine.withUnsafeBytes { outStream.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: tokenLine.count) }

        // Read the full encrypted stream into a temp file
        let tempURL = FileManager.default.temporaryDirectory
                          .appendingPathComponent(UUID().uuidString + ".ftf")
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        guard let outFile = FileHandle(forWritingAtPath: tempURL.path) else {
            signalError(roomId: roomId, reason: "Temp file creation failed")
            return
        }

        var buf = [UInt8](repeating: 0, count: 65536)
        while inStream.hasBytesAvailable {
            let n = inStream.read(&buf, maxLength: buf.count)
            if n <= 0 { break }
            outFile.write(Data(buf[0..<n]))
        }
        try? outFile.close()

        // Decrypt temp → destination
        do {
            try FileTransferCrypto.decryptFromFile(
                encryptedURL: tempURL, key: key, destinationURL: destination,
                progress: { p in DispatchQueue.main.async { self.progress = p } }
            )
            try? FileManager.default.removeItem(at: tempURL)

            DispatchQueue.main.async {
                self.setMsg("File saved to Downloads ✓")
                self.setStatus(.complete)
                self.progress = 1.0
                NSWorkspace.shared.activateFileViewerSelecting([destination])
            }

            let complete: [String: Any] = [
                "type": "FILE_COMPLETE", "roomId": roomId,
                "transferId": incomingTransferId ?? "",
                "originDeviceId": NetworkEngine.shared.publicDeviceId
            ]
            NetworkEngine.shared.broadcastSignal(complete)
            resetIncoming()
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            try? FileManager.default.removeItem(at: destination)
            signalError(roomId: roomId, reason: error.localizedDescription)
        }
    }

    // MARK: - R2 Download (incoming)

    private func downloadFromR2(
        urlString: String,
        key: SymmetricKey,
        transferId: String,
        token: String,
        destination: URL
    ) {
        guard let roomId = CryptoEngine.shared.roomId,
              let url    = URL(string: urlString) else { return }

        let tempURL = FileManager.default.temporaryDirectory
                          .appendingPathComponent(UUID().uuidString + ".ftf")

        let sema = DispatchSemaphore(value: 0)
        var dlError: Error?
        var statusCode = 0

        let task = URLSession.shared.downloadTask(with: url) { tmpLoc, resp, err in
            statusCode = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if let err { dlError = err; sema.signal(); return }
            guard let tmpLoc else { dlError = FileTransferError.downloadFailed(0); sema.signal(); return }
            do { try FileManager.default.moveItem(at: tmpLoc, to: tempURL) } catch { dlError = error }
            sema.signal()
        }
        task.resume()

        // Report progress via URLSession progress KVO (approximate)
        var obs: NSKeyValueObservation?
        obs = task.observe(\.countOfBytesReceived) { t, _ in
            let total = t.countOfBytesExpectedToReceive
            if total > 0 { DispatchQueue.main.async { self.progress = Double(t.countOfBytesReceived) / Double(total) * 0.8 } }
        }
        sema.wait()
        obs?.invalidate()

        if let err = dlError {
            signalError(roomId: roomId, reason: err.localizedDescription)
            return
        }
        guard statusCode == 200 else {
            signalError(roomId: roomId, reason: "Download HTTP \(statusCode)")
            return
        }

        // Decrypt
        do {
            try FileTransferCrypto.decryptFromFile(
                encryptedURL: tempURL, key: key, destinationURL: destination,
                progress: { p in DispatchQueue.main.async { self.progress = 0.8 + p * 0.2 } }
            )
            try? FileManager.default.removeItem(at: tempURL)

            // Delete from R2
            let relayBase = httpBaseURL(from: CryptoEngine.shared.relayUrl)
            if let delURL = URL(string: "\(relayBase)/file/\(transferId)?token=\(token)") {
                var req = URLRequest(url: delURL); req.httpMethod = "DELETE"
                URLSession.shared.dataTask(with: req).resume()
            }

            DispatchQueue.main.async {
                self.setMsg("File saved to Downloads ✓")
                self.setStatus(.complete)
                self.progress = 1.0
                NSWorkspace.shared.activateFileViewerSelecting([destination])
            }

            let complete: [String: Any] = [
                "type": "FILE_COMPLETE", "roomId": roomId,
                "transferId": transferId,
                "originDeviceId": NetworkEngine.shared.publicDeviceId
            ]
            NetworkEngine.shared.broadcastSignal(complete)
            resetIncoming()
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            try? FileManager.default.removeItem(at: destination)
            signalError(roomId: roomId, reason: error.localizedDescription)
        }
    }

    // MARK: - Helpers

    private func signalError(roomId: String, reason: String) {
        setStatus(.failed(reason))
        let err: [String: Any] = [
            "type": "FILE_ERROR", "roomId": roomId,
            "transferId": incomingTransferId ?? pendingTransferId ?? "",
            "reason": reason,
            "originDeviceId": NetworkEngine.shared.publicDeviceId
        ]
        NetworkEngine.shared.broadcastSignal(err)
        resetIncoming(); resetOutgoing()
    }

    private func resetOutgoing() {
        pendingTransferId = nil; pendingToken = nil
        pendingFileURL = nil;    pendingFileKey = nil
    }

    private func resetIncoming() {
        incomingKey = nil; incomingTransferId = nil
        DispatchQueue.main.async { self.incomingOffer = nil }
    }

    private func setStatus(_ s: TransferState) {
        DispatchQueue.main.async { self.state = s }
    }

    private func setMsg(_ m: String) {
        DispatchQueue.main.async { self.statusMessage = m }
    }

    private func generateToken() -> String {
        (0..<32).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    private func downloadsDirectory() -> URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
    }

    private func safeFileName(_ name: String) -> String {
        let illegal = CharacterSet(charactersIn: "/\\:*?\"<>|")
        return name.components(separatedBy: illegal).joined(separator: "_")
    }

    private func httpBaseURL(from wsURL: String) -> String {
        var u = wsURL
        if u.hasPrefix("wss://") { u = "https://" + u.dropFirst(6) }
        else if u.hasPrefix("ws://") { u = "http://" + u.dropFirst(5) }
        if u.hasSuffix("/ws") { u = String(u.dropLast(3)) }
        return u
    }

    private func getLocalIPAddress() -> String? {
        var addr: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifaddr) == 0 {
            var ptr = ifaddr
            while let current = ptr {
                let sa = current.pointee.ifa_addr
                if sa?.pointee.sa_family == UInt8(AF_INET) {
                    let name = String(cString: current.pointee.ifa_name)
                    if name.hasPrefix("en") {
                        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                        if getnameinfo(sa, socklen_t(sa!.pointee.sa_len),
                                       &hostname, socklen_t(hostname.count),
                                       nil, 0, NI_NUMERICHOST) == 0 {
                            addr = String(cString: hostname)
                        }
                    }
                }
                ptr = current.pointee.ifa_next
            }
            freeifaddrs(ifaddr)
        }
        return addr
    }
}
