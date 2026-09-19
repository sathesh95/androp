import Foundation
import CryptoKit

// MARK: - Error

public enum FileTransferError: LocalizedError {
    case keyNotAvailable
    case invalidPayload
    case encryptionFailed
    case decryptionFailed(String)
    case ioError(String)
    case uploadFailed(Int)
    case downloadFailed(Int)
    case transferExpired
    case invalidToken

    public var errorDescription: String? {
        switch self {
        case .keyNotAvailable:      return "Encryption key not available"
        case .invalidPayload:       return "Invalid or corrupted transfer payload"
        case .encryptionFailed:     return "File encryption failed"
        case .decryptionFailed(let m): return "File decryption failed: \(m)"
        case .ioError(let m):       return "I/O error: \(m)"
        case .uploadFailed(let c):  return "Upload failed (HTTP \(c))"
        case .downloadFailed(let c): return "Download failed (HTTP \(c))"
        case .transferExpired:      return "Transfer link has expired"
        case .invalidToken:         return "Invalid transfer token"
        }
    }
}

// MARK: - FileTransferCrypto

public final class FileTransferCrypto {

    // 1 MB chunks — memory-efficient for files up to 500 MB
    public static let chunkSize = 1 * 1024 * 1024

    // Wire format magic "FTF!" — lets receiver verify it has the right payload
    static let magic: UInt32 = 0x46544621

    // MARK: Per-transfer Key Helpers

    /// Generate a fresh random AES-256 key scoped to one file transfer.
    public static func generateFileKey() -> SymmetricKey {
        SymmetricKey(size: .bits256)
    }

    /// Wrap the per-transfer `fileKey` inside an AES-GCM envelope keyed by the room `roomKey`.
    /// Returns (ciphertextBase64, ivBase64) for embedding in the FILE_OFFER signal.
    public static func encryptFileKey(
        _ fileKey: SymmetricKey,
        with roomKey: SymmetricKey
    ) throws -> (ciphertextBase64: String, ivBase64: String) {
        let keyData = fileKey.withUnsafeBytes { Data($0) }
        let box = try AES.GCM.seal(keyData, using: roomKey)
        let iv  = box.nonce.withUnsafeBytes { Data($0).base64EncodedString() }
        let ct  = (box.ciphertext + box.tag).base64EncodedString()
        return (ct, iv)
    }

    /// Unwrap and return a `SymmetricKey` from the FILE_OFFER signal fields.
    public static func decryptFileKey(
        ciphertextBase64: String,
        ivBase64: String,
        with roomKey: SymmetricKey
    ) throws -> SymmetricKey {
        guard let ct = Data(base64Encoded: ciphertextBase64),
              let iv = Data(base64Encoded: ivBase64),
              ct.count > 16 else { throw FileTransferError.invalidPayload }

        let nonce  = try AES.GCM.Nonce(data: iv)
        let tagIdx = ct.count - 16
        let box    = try AES.GCM.SealedBox(nonce: nonce,
                                            ciphertext: ct[..<tagIdx],
                                            tag: ct[tagIdx...])
        let keyData = try AES.GCM.open(box, using: roomKey)
        return SymmetricKey(data: keyData)
    }

    // MARK: File Encryption → Temp File (for R2 upload)

    /// Encrypt `sourceURL` chunk-by-chunk, writing to a temporary `.ftf` file.
    /// Returns the URL of the temp file (caller must delete it when done).
    ///
    /// Wire format:
    /// ```
    /// [4 bytes magic][4 bytes chunk_count]
    /// [4 bytes chunk_data_len][12 bytes nonce][chunk_data_len-12 bytes ciphertext+tag] × N
    /// ```
    public static func encryptToTempFile(
        sourceURL: URL,
        key: SymmetricKey,
        progress: ((Double) -> Void)? = nil
    ) throws -> URL {
        let inHandle  = try FileHandle(forReadingFrom: sourceURL)
        defer { try? inHandle.close() }

        let fileSize    = fileBytes(at: sourceURL)
        let totalChunks = max(1, Int(ceil(Double(fileSize) / Double(chunkSize))))
        let tempURL     = FileManager.default.temporaryDirectory
                              .appendingPathComponent(UUID().uuidString + ".ftf")

        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        let outHandle = try FileHandle(forWritingTo: tempURL)
        defer { try? outHandle.close() }

        // Header
        outHandle.write(uint32BE(magic))
        outHandle.write(uint32BE(UInt32(totalChunks)))

        var chunksDone = 0
        while true {
            let plain = readChunk(from: inHandle, maxBytes: chunkSize)
            guard !plain.isEmpty else { break }

            let box    = try AES.GCM.seal(plain, using: key)
            let nonce  = box.nonce.withUnsafeBytes { Data($0) }
            let cipher = box.ciphertext + box.tag

            outHandle.write(uint32BE(UInt32(nonce.count + cipher.count)))
            outHandle.write(nonce)
            outHandle.write(cipher)

            chunksDone += 1
            progress?(Double(chunksDone) / Double(totalChunks))
        }

        return tempURL
    }

    // MARK: File Decryption ← Temp File (from R2 download)

    /// Decrypt an `.ftf` file at `encryptedURL` to `destinationURL`.
    public static func decryptFromFile(
        encryptedURL: URL,
        key: SymmetricKey,
        destinationURL: URL,
        progress: ((Double) -> Void)? = nil
    ) throws {
        let inHandle = try FileHandle(forReadingFrom: encryptedURL)
        defer { try? inHandle.close() }

        // Validate header
        let header = inHandle.readData(ofLength: 8)
        guard header.count == 8 else { throw FileTransferError.invalidPayload }

        let gotMagic = uint32FromBE(header[0..<4])
        guard gotMagic == magic else { throw FileTransferError.invalidPayload }
        let totalChunks = Int(uint32FromBE(header[4..<8]))

        FileManager.default.createFile(atPath: destinationURL.path, contents: nil)
        let outHandle = try FileHandle(forWritingTo: destinationURL)
        defer { try? outHandle.close() }

        for i in 0..<totalChunks {
            let sizeBuf = inHandle.readData(ofLength: 4)
            guard sizeBuf.count == 4 else { throw FileTransferError.invalidPayload }
            let chunkLen = Int(uint32FromBE(sizeBuf[0..<4]))

            guard chunkLen > 28 else { throw FileTransferError.invalidPayload }
            let chunkData = inHandle.readData(ofLength: chunkLen)
            guard chunkData.count == chunkLen else { throw FileTransferError.invalidPayload }

            let nonce  = try AES.GCM.Nonce(data: chunkData[0..<12])
            let tagIdx = chunkData.count - 16
            let box    = try AES.GCM.SealedBox(nonce: nonce,
                                                ciphertext: chunkData[12..<tagIdx],
                                                tag: chunkData[tagIdx...])
            let plain  = try AES.GCM.open(box, using: key)
            outHandle.write(plain)

            progress?(Double(i + 1) / Double(totalChunks))
        }
    }

    // MARK: LAN Streaming Helpers

    /// Read one chunk from a FileHandle, or return empty Data at EOF.
    public static func readChunk(from handle: FileHandle, maxBytes: Int) -> Data {
        if #available(macOS 10.15.4, *) {
            return (try? handle.read(upToCount: maxBytes)) ?? Data()
        } else {
            return handle.readData(ofLength: maxBytes)
        }
    }

    // MARK: Utility

    static func fileBytes(at url: URL) -> Int {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
    }

    static func uint32BE(_ v: UInt32) -> Data {
        var be = v.bigEndian
        return Data(bytes: &be, count: 4)
    }

    static func uint32FromBE(_ slice: Data) -> UInt32 {
        slice.withUnsafeBytes { UInt32(bigEndian: $0.load(as: UInt32.self)) }
    }
}
