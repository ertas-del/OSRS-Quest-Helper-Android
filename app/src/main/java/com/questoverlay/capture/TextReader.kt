package com.questoverlay.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/** Reads text from a picture with Google's ML Kit, entirely on the phone. */
class TextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val worker = Executors.newSingleThreadExecutor()

    /** Calls [done] (on a background thread) with every line found, in 0..1 coordinates. */
    fun read(bitmap: Bitmap, done: (List<OcrLine>) -> Unit) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(worker) { text ->
                    val lines = ArrayList<OcrLine>()
                    for (block in text.textBlocks) for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        lines.add(OcrLine(line.text, b.left / w, b.top / h, b.right / w, b.bottom / h))
                    }
                    done(lines)
                }
                .addOnFailureListener(worker) { done(emptyList()) }
        } catch (e: Exception) {
            done(emptyList())
        }
    }

    fun close() {
        try { recognizer.close() } catch (e: Exception) {}
        worker.shutdown()
    }
}
