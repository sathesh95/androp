import AppKit
import Foundation

public protocol ClipboardWatcherDelegate: AnyObject {
    func clipboardDidChange(newText: String, hash: String)
}

public final class ClipboardWatcher {
    public static let shared = ClipboardWatcher()
    
    public weak var delegate: ClipboardWatcherDelegate?
    
    private let pasteboard = NSPasteboard.general
    private var lastChangeCount: Int = 0
    private var timer: Timer?
    
    // Track remote-injected hashes to suppress echo broadcasts without blocking local re-copies
    private var remoteInjectedHashes: Set<String> = []
    private let hashQueue = DispatchQueue(label: "com.clipboardsync.hashQueue")
    
    // Filter out password manager copy types
    private let concealedPasteboardTypes: [NSPasteboard.PasteboardType] = [
        NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"),
        NSPasteboard.PasteboardType("com.agilebits.onepassword"),
        NSPasteboard.PasteboardType("de.blink_mind.concealed")
    ]
    
    public var ignorePasswords: Bool = true
    
    // Lazy / promise pasteboard retry tracking
    private var retryChangeCount: Int = 0
    private var retryAttempts: Int = 0
    private let maxRetryAttempts: Int = 3
    
    private init() {
        self.lastChangeCount = pasteboard.changeCount
    }
    
    public func startListening() {
        stopListening()
        self.lastChangeCount = pasteboard.changeCount
        
        // Lightweight polling: 250ms interval with zero-allocation changeCount check
        self.timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.checkForChanges()
        }
        RunLoop.main.add(self.timer!, forMode: .common)
    }
    
    public func stopListening() {
        timer?.invalidate()
        timer = nil
    }
    
    public func markRemoteInjectedHash(_ hash: String) {
        hashQueue.sync {
            _ = remoteInjectedHashes.insert(hash)
        }
    }
    
    public func consumeRemoteInjectedHashIfPresent(_ hash: String) -> Bool {
        return hashQueue.sync {
            if remoteInjectedHashes.contains(hash) {
                remoteInjectedHashes.remove(hash)
                return true
            }
            return false
        }
    }
    
    public func writeToPasteboard(text: String, hash: String) {
        markRemoteInjectedHash(hash)
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.pasteboard.clearContents()
            self.pasteboard.setString(text, forType: .string)
            self.lastChangeCount = self.pasteboard.changeCount
        }
    }
    
    private func checkForChanges() {
        let currentCount = pasteboard.changeCount
        guard currentCount != lastChangeCount else { return }
        
        // Check if item is marked as concealed / password
        if ignorePasswords {
            if let types = pasteboard.types {
                for concealed in concealedPasteboardTypes {
                    if types.contains(concealed) {
                        lastChangeCount = currentCount
                        return // Skip syncing passwords
                    }
                }
            }
        }
        
        // Read text content with robust fallback across representations
        guard let copiedString = extractCurrentPasteboardString(), !copiedString.isEmpty else {
            // If this is a new changeCount and text is not immediately available (lazy rendering), retry up to maxRetryAttempts
            if retryChangeCount == currentCount {
                retryAttempts += 1
                if retryAttempts >= maxRetryAttempts {
                    // Non-text copy (e.g. image/file) or empty, advance lastChangeCount to stop retrying
                    lastChangeCount = currentCount
                }
            } else {
                retryChangeCount = currentCount
                retryAttempts = 1
            }
            return
        }
        
        // Successfully read text content
        lastChangeCount = currentCount
        retryChangeCount = 0
        retryAttempts = 0
        
        let hash = CryptoEngine.shared.computeSHA256(string: copiedString)
        if consumeRemoteInjectedHashIfPresent(hash) {
            // This pasteboard change was created by our own remote write from Android, ignore echo
            return
        }
        
        delegate?.clipboardDidChange(newText: copiedString, hash: hash)
    }
    
    private func extractCurrentPasteboardString() -> String? {
        // 1. Standard string (public.utf8-plain-text)
        if let str = pasteboard.string(forType: .string), !str.isEmpty {
            return str
        }
        
        // 2. NSString readObjects (handles promise providers and conversions)
        if let strings = pasteboard.readObjects(forClasses: [NSString.self], options: nil) as? [String],
           let first = strings.first, !first.isEmpty {
            return first
        }
        
        // 3. NSURL readObjects (handles address bar and link copies)
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL],
           let firstUrl = urls.first?.absoluteString, !firstUrl.isEmpty {
            return firstUrl
        }
        
        // 4. Iterate all individual pasteboard items
        if let items = pasteboard.pasteboardItems {
            for item in items {
                if let str = item.string(forType: .string), !str.isEmpty {
                    return str
                }
                if let str = item.string(forType: NSPasteboard.PasteboardType("public.utf8-plain-text")), !str.isEmpty {
                    return str
                }
                if let str = item.string(forType: NSPasteboard.PasteboardType("public.url")), !str.isEmpty {
                    return str
                }
                if let str = item.string(forType: NSPasteboard.PasteboardType("NSStringPboardType")), !str.isEmpty {
                    return str
                }
            }
        }
        
        return nil
    }
}
