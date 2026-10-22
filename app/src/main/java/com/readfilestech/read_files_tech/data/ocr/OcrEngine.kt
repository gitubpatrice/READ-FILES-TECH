package com.readfilestech.read_files_tech.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit failed on a picture it was given (its model, its pipeline): never a fault of the file, which decoded. */
class RecognitionFailedException(cause: Throwable?) : Exception("text recognition failed", cause)

/**
 * ML Kit's on-device LATIN text recognition (docs D-031), the 2.15.3's
 * `TextRecognizer(script: TextRecognitionScript.latin)` (`ocr_screen.dart:22`): its model is bundled in the
 * APK, nothing is downloaded and nothing is sent — the app has no network permission, and ML Kit is started
 * here on a [NoGmsContext], which refuses the binding its usage reports go through (Claude review R25).
 *
 * One per screen, [close]d with it. Its calls come from one thread (the view model's).
 */
class OcrEngine(context: Context) : AutoCloseable {
    private val client = run {
        // Its own provider is removed from the manifest: ML Kit starts here, the first time, on this context.
        MlKitContext.initializeIfNeeded(NoGmsContext(context.applicationContext))
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** ML Kit's task for the previous picture, which a cancellation does not stop. */
    private var previous: CompletableDeferred<Unit>? = null

    /**
     * Returns once ML Kit holds no picture any more: a task cancelled on our side runs on in ML Kit, its
     * picture with it — the next is decoded only after, so that two pictures of up to 64 MB never meet.
     */
    suspend fun idle() {
        previous?.await()
    }

    /**
     * The text of the upright [picture], as ML Kit gives it (`Text.getText`, the 2.15.3's `result.text`):
     * its blocks in its order, a line break between lines; empty when it finds none. ML Kit works on its
     * own thread. Its task cannot be stopped: cancelled, this returns at once and its result is dropped.
     *
     * @throws RecognitionFailedException ML Kit failed.
     */
    suspend fun recognize(picture: Bitmap): String {
        idle()
        val running = client.process(InputImage.fromBitmap(picture, 0))
        val done = CompletableDeferred<Unit>()
        previous = done
        // No suspension between the task and its listener: a cancellation cannot leave [done] incomplete.
        return suspendCancellableCoroutine { continuation ->
            running.addOnCompleteListener(DIRECT) { task ->
                done.complete(Unit)
                // Ignored by a continuation already cancelled: the result of a screen that went.
                if (task.isSuccessful) {
                    continuation.resume(task.result.text)
                } else {
                    continuation.resumeWithException(RecognitionFailedException(task.exception))
                }
            }
        }
    }

    /** A task still running fails: its continuation, cancelled with the screen, drops it. */
    override fun close() = client.close()

    private companion object {
        /** On ML Kit's own thread: the result is handed over, nothing more is done there. */
        val DIRECT = Executor { it.run() }
    }
}
