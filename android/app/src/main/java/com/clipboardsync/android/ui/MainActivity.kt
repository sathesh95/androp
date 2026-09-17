package com.clipboardsync.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clipboardsync.android.R
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.databinding.ActivityMainBinding
import com.clipboardsync.android.network.ConnectionStatus
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.service.ClipboardAccessibilityService
import com.clipboardsync.android.service.SyncForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val scanQrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Paired successfully with Mac!", Toast.LENGTH_SHORT).show()
            updateUI()
            SyncForegroundService.startService(this)
            NetworkManager.restart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        setupStatusListener()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupButtons() {
        binding.btnScanQR.setOnClickListener {
            val intent = Intent(this, QRScannerActivity::class.java)
            scanQrLauncher.launch(intent)
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        binding.btnIgnoreBattery.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        binding.btnUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unpair Device")
                .setMessage("Are you sure you want to remove pairing credentials? You will need to scan the Mac QR code again.")
                .setPositiveButton("Unpair") { _, _ ->
                    CryptoManager.clearCredentials()
                    NetworkManager.stop()
                    updateUI()
                    Toast.makeText(this, "Unpaired", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupStatusListener() {
        NetworkManager.statusListener = { status ->
            runOnUiThread {
                updateConnectionStatus(status)
            }
        }
    }

    private fun updateUI() {
        val isPaired = CryptoManager.isPaired()
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isBatteryIgnored = isBatteryOptimizationIgnored()

        if (!isPaired) {
            binding.tvStatusText.text = "Not Paired"
            binding.tvStatusSubtext.text = "Scan your Mac screen once to link devices securely."
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
            binding.btnScanQR.visibility = View.VISIBLE
            binding.btnUnpair.visibility = View.GONE
        } else {
            binding.btnScanQR.text = "📷 Re-Scan Mac QR Code"
            binding.btnUnpair.visibility = View.VISIBLE
            updateConnectionStatus(NetworkManager.currentStatus)
        }

        binding.btnEnableAccessibility.text = if (isAccessibilityEnabled) "Enabled ✓" else "Enable"
        binding.btnEnableAccessibility.isEnabled = !isAccessibilityEnabled

        binding.btnIgnoreBattery.text = if (isBatteryIgnored) "Whitelisted ✓" else "Whitelist"
        binding.btnIgnoreBattery.isEnabled = !isBatteryIgnored
    }

    private fun updateConnectionStatus(status: ConnectionStatus) {
        if (!CryptoManager.isPaired()) return

        binding.tvStatusText.text = status.displayText
        when (status) {
            ConnectionStatus.CONNECTED_LAN -> {
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                binding.tvStatusSubtext.text = "Ultra-fast direct Wi-Fi sync active."
            }
            ConnectionStatus.CONNECTED_RELAY -> {
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.teal_200)
                binding.tvStatusSubtext.text = "End-to-End Encrypted Cloudflare relay active."
            }
            ConnectionStatus.CONNECTING -> {
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_orange)
                binding.tvStatusSubtext.text = "Connecting to sync network..."
            }
            ConnectionStatus.DISCONNECTED -> {
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
                binding.tvStatusSubtext.text = "Waiting for network connectivity."
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "${packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(expectedService) || ClipboardAccessibilityService.instance != null
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else {
            true
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }
    }
}
