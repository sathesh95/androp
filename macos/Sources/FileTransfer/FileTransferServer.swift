import Foundation
import Network
import CryptoKit

/// A one-shot LAN TCP server that serves a single encrypted file and then closes.
///
/// Protocol:
///   Client → Server : "{token}\n"  (ASCII, newline-terminated)
///   Server → Client : [4-byte magic][4-byte chunk_count]
///                     [4-byte chunk_data_len][12-byte nonce][ciphertext+tag] × N
///   Connection closes after last chunk is sent.
public final class FileTransferServer {

    private let queue = DispatchQueue(label: "com.clipboardsync.fileserver", qos: .userInitiated)
    private var listener: NWListener?
    private var activeConnection: NWConnection?

    /// Port the listener is bound to — available after `start()` returns.
    public var localPort: UInt16? {
        guard let p = listener?.port else { return nil }
        return p.rawValue
    }

    public init() {}

    // MARK: - Start / Stop

    public func start(
        fileURL: URL,
        key: SymmetricKey,
        token: String,
        onProgress: ((Double) -> Void)? = nil,
        onComplete: @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        listener = try NWListener(using: params)

        listener?.newConnectionHandler = { [weak self] conn in
            guard let self else { return }
            // Only accept one connection per transfer
            if self.activeConnection != nil {
                conn.cancel()
                return
            }
            self.activeConnection = conn
            self.handleConnection(conn, fileURL: fileURL, key: key, token: token,
                                  onProgress: onProgress,
                                  onComplete: onComplete,
                                  onError: onError)
        }

        listener?.stateUpdateHandler = { state in
            if case .failed(let err) = state { onError(err) }
        }

        listener?.start(queue: queue)

        // Wait briefly for the listener to bind and get a port
        Thread.sleep(forTimeInterval: 0.1)
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        activeConnection?.cancel()
        activeConnection = nil
    }

    // MARK: - Connection Handling

    private func handleConnection(
        _ conn: NWConnection,
        fileURL: URL,
        key: SymmetricKey,
        token: String,
        onProgress: ((Double) -> Void)?,
        onComplete: @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) {
        conn.start(queue: queue)

        // Read the client's token line (up to 256 bytes)
        conn.receive(minimumIncompleteLength: 1, maximumLength: 256) { [weak self] data, _, _, error in
            guard let self else { return }

            if let error {
                conn.cancel(); onError(error); return
            }

            guard let data,
                  let line = String(data: data, encoding: .utf8)?
                      .trimmingCharacters(in: .whitespacesAndNewlines),
                  line == token else {
                conn.cancel()
                onError(FileTransferError.invalidToken)
                return
            }

            // Token valid — stream the file
            self.queue.async {
                self.streamFile(conn: conn, fileURL: fileURL, key: key,
                                onProgress: onProgress, onComplete: onComplete, onError: onError)
            }
        }
    }

    private func streamFile(
        conn: NWConnection,
        fileURL: URL,
        key: SymmetricKey,
        onProgress: ((Double) -> Void)?,
        onComplete: @escaping () -> Void,
        onError: @escaping (Error) -> Void
    ) {
        do {
            let inHandle = try FileHandle(forReadingFrom: fileURL)
            defer { try? inHandle.close() }

            let fileSize    = FileTransferCrypto.fileBytes(at: fileURL)
            let totalChunks = max(1, Int(ceil(Double(fileSize) / Double(FileTransferCrypto.chunkSize))))

            // Send wire header: magic + chunk count
            var header = FileTransferCrypto.uint32BE(FileTransferCrypto.magic)
            header.append(FileTransferCrypto.uint32BE(UInt32(totalChunks)))
            sendSync(header, on: conn)

            var done = 0
            while true {
                let plain = FileTransferCrypto.readChunk(from: inHandle, maxBytes: FileTransferCrypto.chunkSize)
                guard !plain.isEmpty else { break }

                let box    = try AES.GCM.seal(plain, using: key)
                let nonce  = box.nonce.withUnsafeBytes { Data($0) }
                let cipher = box.ciphertext + box.tag

                var chunk = FileTransferCrypto.uint32BE(UInt32(nonce.count + cipher.count))
                chunk.append(nonce)
                chunk.append(cipher)
                sendSync(chunk, on: conn)

                done += 1
                onProgress?(Double(done) / Double(totalChunks))
            }

            // Small delay so the last send flushes before we close
            queue.asyncAfter(deadline: .now() + 0.3) {
                conn.cancel()
                onComplete()
                self.stop()
            }
        } catch {
            conn.cancel()
            onError(error)
            stop()
        }
    }

    // MARK: - Helpers

    /// Synchronous send — blocks until the data is queued (not acknowledged).
    private func sendSync(_ data: Data, on conn: NWConnection) {
        let sema = DispatchSemaphore(value: 0)
        conn.send(content: data, completion: .contentProcessed({ _ in sema.signal() }))
        sema.wait()
    }
}
