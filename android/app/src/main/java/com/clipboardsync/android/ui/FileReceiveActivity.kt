package com.clipboardsync.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.clipboardsync.android.R
import com.clipboardsync.android.filetransfer.FileNotificationHelper
import com.clipboardsync.android.filetransfer.FileTransferManager
import java.io.File

/**
 * Full-screen transparent activity shown when a file offer arrives.
 * Behaves like AirDrop: dark overlay with file info + Accept / Decline buttons.
 *
 * Transitions to a progress view once the user accepts.
 */
class FileReceiveActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvSender: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnAccept: Button
    private lateinit var btnDecline: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var groupButtons: View
    private lateinit var groupProgress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over lock screen and show while screen is on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_file_receive)

        FileNotificationHelper.dismissNotification(this)

        tvTitle     = findViewById(R.id.tv_title)
        tvFileName  = findViewById(R.id.tv_file_name)
        tvFileSize  = findViewById(R.id.tv_file_size)
        tvSender    = findViewById(R.id.tv_sender)
        tvStatus    = findViewById(R.id.tv_status)
        btnAccept   = findViewById(R.id.btn_accept)
        btnDecline  = findViewById(R.id.btn_decline)
        progressBar = findViewById(R.id.progress_bar)
        tvPercent   = findViewById(R.id.tv_percent)
        groupButtons = findViewById(R.id.group_buttons)
        groupProgress = findViewById(R.id.group_progress)

        val transferId   = intent.getStringExtra("transferId") ?: ""
        val fileName     = intent.getStringExtra("fileName")  ?: "file"
        val fileSize     = intent.getLongExtra("fileSize", 0L)
        val originDevice = intent.getStringExtra("originDevice") ?: "Mac"

        tvFileName.text = fileName
        tvFileSize.text = Formatter.formatFileSize(this, fileSize)
        tvSender.text   = "From: $originDevice"

        // Register progress callbacks BEFORE accepting
        FileTransferManager.onProgress = { p ->
            runOnUiThread {
                progressBar.progress = (p * 100).toInt()
                tvPercent.text       = "${(p * 100).toInt()}%"
            }
        }

        FileTransferManager.onStatusChange = { msg ->
            runOnUiThread { tvStatus.text = msg }
        }

        FileTransferManager.onTransferComplete = { file ->
            runOnUiThread {
                tvTitle.text  = "Transfer Complete"
                tvStatus.text = "Saved to Downloads"
                progressBar.progress = 100
                tvPercent.text = "100%"

                // Show "Open File" button
                btnAccept.text = "Open File"
                btnAccept.visibility = View.VISIBLE
                btnDecline.text = "Close"
                btnDecline.visibility = View.VISIBLE
                groupButtons.visibility = View.VISIBLE

                btnAccept.setOnClickListener { openFile(file) }
                btnDecline.setOnClickListener { finish() }
            }
        }

        FileTransferManager.onTransferError = { err ->
            runOnUiThread {
                tvTitle.text  = "Transfer Failed"
                tvStatus.text = err
                groupProgress.visibility = View.VISIBLE
                groupButtons.visibility  = View.GONE
                // Auto-dismiss after 4 seconds
                tvStatus.postDelayed({ finish() }, 4000)
            }
        }

        btnAccept.setOnClickListener {
            groupButtons.visibility  = View.GONE
            groupProgress.visibility = View.VISIBLE
            tvStatus.text = "Accepting…"
            FileTransferManager.acceptOffer(this)
        }

        btnDecline.setOnClickListener {
            FileTransferManager.rejectOffer(this)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear callbacks to avoid leaks
        FileTransferManager.onProgress      = null
        FileTransferManager.onStatusChange  = null
        FileTransferManager.onTransferComplete = null
        FileTransferManager.onTransferError = null
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this,
                "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            tvStatus.text = "Cannot open file: ${e.message}"
        }
    }
}
