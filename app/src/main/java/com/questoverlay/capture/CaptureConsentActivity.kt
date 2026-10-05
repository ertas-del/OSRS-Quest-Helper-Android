package com.questoverlay.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.questoverlay.OverlayService

/**
 * Shows Android's "share your screen" prompt and passes the answer to the overlay. Android asks
 * every time Auto-check starts; on Android 14+ you can pick "A single app" and choose the game,
 * so nothing else on the phone is captured.
 */
class CaptureConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        } catch (e: Exception) {
            report(RESULT_CANCELED, null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) report(resultCode, data)
    }

    private fun report(resultCode: Int, data: Intent?) {
        val i = Intent(this, OverlayService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            i.action = OverlayService.ACTION_CAPTURE_GRANTED
            i.putExtra(OverlayService.EXTRA_CAPTURE_CODE, resultCode)
            i.putExtra(OverlayService.EXTRA_CAPTURE_DATA, data)
        } else {
            i.action = OverlayService.ACTION_CAPTURE_DENIED
        }
        try {
            startService(i)
        } catch (e: Exception) {
            // The overlay closed in the meantime.
        }
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
