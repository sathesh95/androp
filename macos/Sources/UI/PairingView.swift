import SwiftUI
import CoreImage.CIFilterBuiltins

public struct PairingView: View {
    @ObservedObject private var otpManager = OTPManager.shared
    @ObservedObject private var fileTransfer = FileTransferManager.shared
    @State private var connectionStatus: ConnectionStatus = NetworkEngine.shared.status
    @State private var pairingPayload: String = CryptoEngine.shared.getPairingPayload()
    @State private var relayUrl: String = CryptoEngine.shared.relayUrl
    @State private var launchAtLogin: Bool = LaunchAtLogin.isEnabled
    @State private var ignorePasswords: Bool = ClipboardWatcher.shared.ignorePasswords
    @State private var showSettings: Bool = false
    @State private var lastSyncedText: String = ""
    @State private var copyNotice: Bool = false
    @State private var otpCopiedNotice: Bool = false


    public init() {}

    public var body: some View {
        VStack(spacing: 14) {
            // Header with Status Indicator
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 10, height: 10)
                Text(connectionStatus.rawValue)
                    .font(.system(size: 13, weight: .medium))
                Spacer()
                Button(action: { showSettings.toggle() }) {
                    Image(systemName: "gearshape")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 4)

            // Active OTP Section (if active)
            if let otp = otpManager.currentOTP, !otp.isExpired {
                otpCard(otp: otp)
            }

            Divider()

            if showSettings {
                settingsSection
            } else {
                qrCodeSection
            }

            Divider()

            // ── Incoming File Offer Banner ──────────────────────────────
            if let offer = fileTransfer.incomingOffer {
                incomingOfferBanner(offer: offer)
            }

            // ── File Transfer Progress ──────────────────────────────────
            if case .transferring = fileTransfer.state {
                fileProgressView
            }
            if case .awaitingAccept = fileTransfer.state {
                Text(fileTransfer.statusMessage)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            if case .complete = fileTransfer.state {
                HStack(spacing: 4) {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                    Text(fileTransfer.statusMessage).font(.system(size: 11))
                }
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                        fileTransfer.state = .idle
                    }
                }
            }
            if case .failed(let msg) = fileTransfer.state {
                HStack(spacing: 4) {
                    Image(systemName: "xmark.circle.fill").foregroundColor(.red)
                    Text(msg).font(.system(size: 11)).foregroundColor(.red).lineLimit(2)
                }
            }

            // ── Quick Toggles ───────────────────────────────────────────
            VStack(alignment: .leading, spacing: 10) {
                Toggle("Launch at Login (Auto-Start)", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { newValue in
                        LaunchAtLogin.isEnabled = newValue
                    }
                
                Toggle("Ignore Passwords from Password Managers", isOn: $ignorePasswords)
                    .onChange(of: ignorePasswords) { newValue in
                        ClipboardWatcher.shared.ignorePasswords = newValue
                    }
            }
            .font(.system(size: 12))

            if !lastSyncedText.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Last Synced Content:")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(.secondary)
                    Text(lastSyncedText)
                        .font(.system(size: 11, design: .monospaced))
                        .lineLimit(2)
                        .padding(6)
                        .background(Color(NSColor.controlBackgroundColor))
                        .cornerRadius(6)
                }
            }

            // Footer
            HStack {
                // ── Send File button ───────────────────────────────────
                Button {
                    FileTransferManager.shared.promptAndSendFile()
                } label: {
                    Label("Send File", systemImage: "arrow.up.doc")
                        .font(.system(size: 11))
                }
                .buttonStyle(.borderedProminent)
                .tint(.accentColor)
                .disabled(connectionStatus == .disconnected)

                Spacer()
                Button("Quit") {
                    NSApplication.shared.terminate(nil)
                }
                .font(.system(size: 11))
                .buttonStyle(.borderless)
            }
        }
        .padding(16)
        .frame(width: 320)
        .onAppear {
            self.connectionStatus = NetworkEngine.shared.status
            self.pairingPayload = CryptoEngine.shared.getPairingPayload()
        }

    }

    private var qrCodeSection: some View {
        VStack(spacing: 12) {
            Text("1-Time Pairing Setup")
                .font(.headline)

            Text("Scan this QR code with the Android app once. Encryption keys are saved securely and never leave your devices.")
                .font(.system(size: 11))
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)

            if let qrImage = generateQRCode(from: pairingPayload) {
                Image(nsImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .padding(8)
                    .background(Color.white)
                    .cornerRadius(12)
                    .shadow(radius: 2)
            }

            Button(action: {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(pairingPayload, forType: .string)
                copyNotice = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    copyNotice = false
                }
            }) {
                HStack {
                    Image(systemName: copyNotice ? "checkmark" : "doc.on.doc")
                    Text(copyNotice ? "Copied Pairing Link!" : "Copy Pairing Link")
                }
                .font(.system(size: 11))
            }
            .buttonStyle(.bordered)
        }
    }

    private var settingsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Configuration")
                    .font(.headline)
                Spacer()
                Button("Done") {
                    showSettings = false
                }
                .font(.system(size: 12))
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Room ID:")
                    .font(.system(size: 11, weight: .semibold))
                Text(CryptoEngine.shared.roomId ?? "N/A")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.secondary)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Cloudflare Relay URL:")
                    .font(.system(size: 11, weight: .semibold))
                TextField("wss://...", text: $relayUrl)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 11))
                Button("Save & Reconnect") {
                    CryptoEngine.shared.setRelayUrl(relayUrl)
                    pairingPayload = CryptoEngine.shared.getPairingPayload()
                    NetworkEngine.shared.broadcastConfigUpdate(newRelayUrl: relayUrl)
                    NetworkEngine.shared.reloadConfiguration()
                }
                .font(.system(size: 11))
                .buttonStyle(.borderedProminent)
            }

            Divider()

            Button("Reset & Generate New Pairing Key", role: .destructive) {
                CryptoEngine.shared.generateAndSaveNewPairingCredentials()
                pairingPayload = CryptoEngine.shared.getPairingPayload()
                NetworkEngine.shared.reloadConfiguration()
            }
            .font(.system(size: 11))
            .foregroundColor(.red)
        }
    }

    private var statusColor: Color {
        switch connectionStatus {
        case .lanConnected:
            return .green
        case .relayConnected:
            return .blue
        case .connecting:
            return .orange
        case .disconnected:
            return .red
        }
    }

    private func generateQRCode(from string: String) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        guard let data = string.data(using: .utf8) else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")

        guard let outputImage = filter.outputImage else { return nil }
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))

        if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
            return NSImage(cgImage: cgImage, size: NSSize(width: 180, height: 180))
        }
        return nil
    }

    private func otpCard(otp: OTPRecord) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "key.fill")
                    .foregroundColor(.yellow)
                    .font(.system(size: 12))
                Text("OTP RECEIVED")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.secondary)
                Spacer()
                Text("⏳ \(otp.timeRemainingFormatted)")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(.secondary)
            }

            HStack {
                Text(otp.code)
                    .font(.system(size: 22, weight: .bold, design: .monospaced))
                    .foregroundColor(.primary)
                Spacer()
                Button(action: {
                    otpManager.copyOtpToClipboard(otp)
                    otpCopiedNotice = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        otpCopiedNotice = false
                    }
                }) {
                    HStack(spacing: 4) {
                        Image(systemName: otpCopiedNotice ? "checkmark" : "doc.on.doc")
                        Text(otpCopiedNotice ? "Copied" : "Copy")
                    }
                    .font(.system(size: 11, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
            }

            if !otp.sender.isEmpty {
                Text("From: \(otp.sender)")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(10)
        .background(Color.yellow.opacity(0.12))
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.yellow.opacity(0.35), lineWidth: 1)
        )
    }

    // MARK: - File Transfer Helper Views

    @ViewBuilder
    private func incomingOfferBanner(offer: FileTransferOffer) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "arrow.down.doc.fill")
                    .foregroundColor(.blue)
                Text("Incoming File")
                    .font(.system(size: 11, weight: .bold))
                Spacer()
            }

            Text(offer.fileName)
                .font(.system(size: 12, weight: .semibold))
                .lineLimit(1)

            Text(ByteCountFormatter.string(fromByteCount: offer.fileSize, countStyle: .file))
                .font(.system(size: 10))
                .foregroundColor(.secondary)

            HStack(spacing: 8) {
                Button("Decline") {
                    FileTransferManager.shared.rejectOffer()
                }
                .buttonStyle(.bordered)
                .font(.system(size: 11))

                Button("Accept") {
                    FileTransferManager.shared.acceptOffer()
                }
                .buttonStyle(.borderedProminent)
                .font(.system(size: 11, weight: .semibold))
            }
        }
        .padding(10)
        .background(Color.blue.opacity(0.08))
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.blue.opacity(0.3), lineWidth: 1)
        )
    }

    private var fileProgressView: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 10))
                    .foregroundColor(.accentColor)
                Text(fileTransfer.statusMessage)
                    .font(.system(size: 11))
                    .lineLimit(1)
                Spacer()
                Text("\(Int(fileTransfer.progress * 100))%")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
            }
            ProgressView(value: fileTransfer.progress)
                .progressViewStyle(.linear)
        }
        .padding(8)
        .background(Color(NSColor.controlBackgroundColor))
        .cornerRadius(8)
    }
}
