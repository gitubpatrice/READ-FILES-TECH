package com.readfilestech.read_files_tech.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * Links handed to the app that answers them — a browser, a mail app — which makes its own connection: Read Files
 * Tech connects to nothing itself (docs D-014).
 */
object Links {
    /**
     * [url], a web or e-mail link, opened as a browser opens a link: only by an activity that declares
     * BROWSABLE. No app (ActivityNotFoundException), or a SecurityException from the one that would answer, is
     * said by the outcome, never a reason to end this app.
     */
    fun open(context: Context, url: String): LaunchOutcome =
        start(context, Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))

    /** A new e-mail to [to], its [subject] and [body] filled in, in the mail app the user picks: nothing is sent by this app. */
    fun mail(context: Context, to: String, subject: String, body: String? = null): LaunchOutcome {
        // The address in the URI too: some mail apps read it there only, and opened a draft to no one (Claude review).
        val mail = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", to, null))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body != null) mail.putExtra(Intent.EXTRA_TEXT, body)
        return start(context, mail)
    }

    // TooGenericExceptionCaught: a link of a document is untrusted — the answering app's SecurityException, or
    // anything its URI makes startActivity throw, is said, never a reason to end this app (as the Markdown viewer
    // caught before it came here). No suspending call.
    @Suppress("TooGenericExceptionCaught")
    private fun start(context: Context, intent: Intent): LaunchOutcome = try {
        context.startActivity(intent)
        LaunchOutcome.STARTED
    } catch (expected: ActivityNotFoundException) {
        LaunchOutcome.NO_APP
    } catch (expected: RuntimeException) {
        LaunchOutcome.FAILED
    }
}
