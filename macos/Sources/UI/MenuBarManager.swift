import AppKit
import SwiftUI

public final class MenuBarManager: NSObject, ClipboardWatcherDelegate, NetworkEngineDelegate {
    public static let shared = MenuBarManager()
    
    private var statusItem: NSStatusItem?
    private var popover = NSPopover()
    
    public override init() {
        super.init()
    }
    
    public func setup() {
        // Setup Menu Bar Item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem?.button {
            button.image = NSImage(systemSymbolName: "arrow.triangle.2.circlepath.doc.on.clipboard", accessibilityDescription: "Clipboard Sync")
            button.target = self
            button.action = #selector(togglePopover(_:))
        }
        
        // Setup Popover
        popover.contentSize = NSSize(width: 320, height: 420)
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(rootView: PairingView())
        
        // Wire up delegates
        ClipboardWatcher.shared.delegate = self
        NetworkEngine.shared.delegate = self
        
        // Start services
        ClipboardWatcher.shared.startListening()
        NetworkEngine.shared.start()
        
        print("[MenuBarManager] Clipboard Sync daemon started successfully.")
    }
    
    @objc private func togglePopover(_ sender: AnyObject?) {
        guard let button = statusItem?.button else { return }
        if popover.isShown {
            popover.performClose(sender)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            NSApplication.shared.activate(ignoringOtherApps: true)
        }
    }
    
    // MARK: - ClipboardWatcherDelegate
    
    public func clipboardDidChange(newText: String, hash: String) {
        print("[ClipboardWatcher] Detected new local copy. Broadcasting to paired devices...")
        NetworkEngine.shared.broadcastClipboard(text: newText, hash: hash)
        
        // Animate menu bar icon briefly
        animateIconSync()
    }
    
    // MARK: - NetworkEngineDelegate
    
    public func connectionStatusDidChange(_ status: ConnectionStatus) {
        print("[NetworkEngine] Status changed: \(status.rawValue)")
    }
    
    public func receivedNewClipboardText(_ text: String) {
        print("[NetworkEngine] Synced new remote clipboard content.")
        animateIconSync()
    }
    
    private func animateIconSync() {
        DispatchQueue.main.async { [weak self] in
            guard let button = self?.statusItem?.button else { return }
            button.image = NSImage(systemSymbolName: "checkmark.circle.fill", accessibilityDescription: "Synced")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                button.image = NSImage(systemSymbolName: "arrow.triangle.2.circlepath.doc.on.clipboard", accessibilityDescription: "Clipboard Sync")
            }
        }
    }
}
