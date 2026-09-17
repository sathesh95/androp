import Foundation
import AppKit
import UserNotifications

public struct OTPRecord: Identifiable, Equatable {
    public let id = UUID()
    public let code: String
    public let sender: String
    public let originalText: String
    public let timestamp: Date
    public let expirationDate: Date
    
    public init(code: String, sender: String, originalText: String, timestamp: Date = Date(), expirationSeconds: TimeInterval = 300) {
        self.code = code
        self.sender = sender
        self.originalText = originalText
        self.timestamp = timestamp
        self.expirationDate = timestamp.addingTimeInterval(expirationSeconds)
    }
    
    public var isExpired: Bool {
        return Date() > expirationDate
    }
    
    public var timeRemainingFormatted: String {
        let remaining = max(0, Int(expirationDate.timeIntervalSince(Date())))
        let minutes = remaining / 60
        let seconds = remaining % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

public final class OTPManager: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    public static let shared = OTPManager()
    
    @Published public var currentOTP: OTPRecord?
    @Published public var otpHistory: [OTPRecord] = []
    
    private var expirationTimer: Timer?
    
    private var hasBundleIdentifier: Bool {
        return Bundle.main.bundleIdentifier != nil
    }
    
    private override init() {
        super.init()
        setupNotifications()
    }
    
    private func setupNotifications() {
        guard hasBundleIdentifier else {
            print("[OTPManager] Running as raw CLI/SPM binary without bundle identifier. System notifications disabled (OTP will still be copied to clipboard & shown in Menu Bar popover). To enable system banners, package with ./macos/build_app.sh.")
            return
        }
        
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                print("[OTPManager] Notification authorization error: \(error)")
            } else {
                print("[OTPManager] Notification permission granted: \(granted)")
            }
        }
    }
    
    public func handleReceivedOTP(code: String, sender: String, originalText: String, timestamp: Date = Date()) {
        let record = OTPRecord(code: code, sender: sender, originalText: originalText, timestamp: timestamp)
        
        DispatchQueue.main.async {
            self.currentOTP = record
            self.otpHistory.insert(record, at: 0)
            if self.otpHistory.count > 10 {
                self.otpHistory.removeLast()
            }
            
            // 1. Auto-copy to macOS clipboard
            ClipboardWatcher.shared.writeToPasteboard(text: code, hash: "otp-\(code)")
            
            // 2. Schedule expiration countdown timer
            self.startExpirationTimer()
            
            // 3. Post macOS system notification banner (if bundle exists)
            self.postSystemNotification(record: record)
        }
    }
    
    public func copyOtpToClipboard(_ record: OTPRecord) {
        ClipboardWatcher.shared.writeToPasteboard(text: record.code, hash: "otp-\(record.code)")
    }
    
    private func startExpirationTimer() {
        expirationTimer?.invalidate()
        expirationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if let current = self.currentOTP, current.isExpired {
                self.currentOTP = nil
                self.expirationTimer?.invalidate()
                self.expirationTimer = nil
            }
        }
    }
    
    private func postSystemNotification(record: OTPRecord) {
        guard hasBundleIdentifier else { return }
        
        let content = UNMutableNotificationContent()
        content.title = "🔑 Verification Code: \(record.code)"
        content.subtitle = "From: \(record.sender)"
        content.body = "Copied to clipboard. Press Cmd+V to paste."
        content.sound = UNNotificationSound.default
        content.userInfo = ["otp_code": record.code]
        
        let request = UNNotificationRequest(identifier: "otp-\(UUID().uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("[OTPManager] Error showing user notification: \(error)")
            }
        }
    }
    
    // MARK: - UNUserNotificationCenterDelegate
    public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
