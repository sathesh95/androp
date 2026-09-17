import AppKit

let app = NSApplication.shared
// Run as an accessory daemon (no Dock icon, menu bar only)
app.setActivationPolicy(.accessory)

let menuBarManager = MenuBarManager.shared
menuBarManager.setup()

app.run()
