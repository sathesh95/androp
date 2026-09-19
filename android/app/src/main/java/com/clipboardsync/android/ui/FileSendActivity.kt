package com.clipboardsync.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.filetransfer.FileTransferManager

/**
 * Handles Android Share Sheet (ACTION_SEND) to send files from Android to Mac.
 */
class FileSendActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!CryptoManager.isPaired()) {
            Toast.makeText(this, "Device not paired. Please pair with Mac first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                val fileName = getFileName(uri) ?: "shared_file"
                val mimeType = intent.type ?: "application/octet-stream"
                try {
                    val stagedFile = java.io.File(cacheDir, "outgoing_staged_${System.currentTimeMillis()}.tmp")
                    contentResolver.openInputStream(uri)?.use { inp ->
                        stagedFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    if (stagedFile.exists() && stagedFile.length() > 0) {
                        Toast.makeText(this, "Sending \"$fileName\" to Mac…", Toast.LENGTH_SHORT).show()
                        FileTransferManager.sendFile(this, stagedFile, fileName, mimeType)
                    } else {
                        Toast.makeText(this, "Unable to read file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FileSendActivity", "Error staging file", e)
                    Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "No file found to share", Toast.LENGTH_SHORT).show()
            }
        }
        finish()

    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return name
    }
}
