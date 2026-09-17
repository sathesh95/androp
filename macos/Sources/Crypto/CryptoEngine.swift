import Foundation
import CryptoKit
import Security

public final class CryptoEngine {
    public static let shared = CryptoEngine()
    
    private let keychainService = "com.clipboardsync.macos"
    private let keyAccount = "encryption_key"
    private let roomAccount = "room_id"
    private let relayAccount = "relay_url"
    
    private(set) var symmetricKey: SymmetricKey?
    private(set) var roomId: String?
    private(set) var relayUrl: String = "wss://clipboard-sync-relay.your-subdomain.workers.dev/ws"
    
    private init() {
        loadCredentials()
        if symmetricKey == nil || roomId == nil {
            generateAndSaveNewPairingCredentials()
        }
    }
    
    // MARK: - Credentials Management
    
    public func generateAndSaveNewPairingCredentials() {
        let newKey = SymmetricKey(size: .bits256)
        let newRoomId = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        
        self.symmetricKey = newKey
        self.roomId = newRoomId
        
        let keyBase64 = newKey.withUnsafeBytes { Data($0).base64EncodedString() }
        saveToKeychain(account: keyAccount, data: keyBase64)
        saveToKeychain(account: roomAccount, data: newRoomId)
    }
    
    public func setRelayUrl(_ url: String) {
        self.relayUrl = url
        saveToKeychain(account: relayAccount, data: url)
    }
    
    public func getPairingPayload() -> String {
        guard let key = symmetricKey, let room = roomId else { return "" }
        let keyBase64 = key.withUnsafeBytes { Data($0).base64EncodedString() }
        let encodedRelay = relayUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? relayUrl
        return "clipboardsync://pair?room=\(room)&key=\(keyBase64)&relay=\(encodedRelay)"
    }
    
    public var keyBase64String: String {
        guard let key = symmetricKey else { return "" }
        return key.withUnsafeBytes { Data($0).base64EncodedString() }
    }
    
    // MARK: - Encryption & Decryption
    
    public func encrypt(plainText: String) throws -> (ciphertextBase64: String, ivBase64: String, hash: String) {
        guard let key = symmetricKey else {
            throw NSError(domain: "CryptoEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Symmetric key not found"])
        }
        guard let plainData = plainText.data(using: .utf8) else {
            throw NSError(domain: "CryptoEngine", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid string encoding"])
        }
        
        let hash = computeSHA256(data: plainData)
        let sealedBox = try AES.GCM.seal(plainData, using: key)
        
        let ivBase64 = sealedBox.nonce.withUnsafeBytes { Data($0).base64EncodedString() }
        // Combine tag + ciphertext
        let combined = sealedBox.ciphertext + sealedBox.tag
        let ciphertextBase64 = combined.base64EncodedString()
        
        return (ciphertextBase64, ivBase64, hash)
    }
    
    public func decrypt(ciphertextBase64: String, ivBase64: String) throws -> (plainText: String, hash: String) {
        guard let key = symmetricKey else {
            throw NSError(domain: "CryptoEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Symmetric key not found"])
        }
        guard let ciphertextData = Data(base64Encoded: ciphertextBase64),
              let ivData = Data(base64Encoded: ivBase64) else {
            throw NSError(domain: "CryptoEngine", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid base64 payload"])
        }
        
        let nonce = try AES.GCM.Nonce(data: ivData)
        
        // Split ciphertext and tag (tag is last 16 bytes)
        guard ciphertextData.count > 16 else {
            throw NSError(domain: "CryptoEngine", code: 4, userInfo: [NSLocalizedDescriptionKey: "Ciphertext payload too short"])
        }
        let tagIndex = ciphertextData.count - 16
        let actualCiphertext = ciphertextData.subdata(in: 0..<tagIndex)
        let tag = ciphertextData.subdata(in: tagIndex..<ciphertextData.count)
        
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: actualCiphertext, tag: tag)
        let decryptedData = try AES.GCM.open(sealedBox, using: key)
        
        guard let text = String(data: decryptedData, encoding: .utf8) else {
            throw NSError(domain: "CryptoEngine", code: 5, userInfo: [NSLocalizedDescriptionKey: "Decrypted bytes are not valid UTF-8"])
        }
        
        let hash = computeSHA256(data: decryptedData)
        return (text, hash)
    }
    
    public func computeSHA256(data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.compactMap { String(format: "%02x", $0) }.joined()
    }
    
    public func computeSHA256(string: String) -> String {
        guard let data = string.data(using: .utf8) else { return "" }
        return computeSHA256(data: data)
    }
    
    // MARK: - Keychain Helpers
    
    private func saveToKeychain(account: String, data: String) {
        guard let encoded = data.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account
        ]
        
        SecItemDelete(query as CFDictionary)
        
        var newQuery = query
        newQuery[kSecValueData as String] = encoded
        newQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        
        SecItemAdd(newQuery as CFDictionary, nil)
    }
    
    private func loadFromKeychain(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        
        if status == errSecSuccess, let data = result as? Data, let str = String(data: data, encoding: .utf8) {
            return str
        }
        return nil
    }
    
    private func loadCredentials() {
        if let keyStr = loadFromKeychain(account: keyAccount),
           let keyData = Data(base64Encoded: keyStr) {
            self.symmetricKey = SymmetricKey(data: keyData)
        }
        if let room = loadFromKeychain(account: roomAccount) {
            self.roomId = room
        }
        if let relay = loadFromKeychain(account: relayAccount) {
            self.relayUrl = relay
        }
    }
}
