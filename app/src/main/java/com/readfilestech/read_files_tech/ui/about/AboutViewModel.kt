package com.readfilestech.read_files_tech.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownLinks
import com.readfilestech.read_files_tech.ui.common.FileLauncher
import com.readfilestech.read_files_tech.ui.common.LaunchOutcome
import com.readfilestech.read_files_tech.ui.common.Links
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The links of About and of the legal texts, handed to other apps; what could not be opened is said. */
@HiltViewModel
class AboutViewModel @Inject constructor(private val messages: UserMessages) : ViewModel() {
    /** [url] in the browser (the releases page, the website). */
    fun open(context: Context, url: String) {
        FileLauncher.messageFor(Links.open(context, url))?.let(messages::post)
    }

    /** A link of a legal text: opened if it leads to the web or to an e-mail address, as the Markdown viewer's are. */
    fun openLink(context: Context, destination: String) {
        val target = MarkdownLinks.opened(destination)
        if (target == null) messages.error(uiText(R.string.markdown_link_refused)) else open(context, target)
    }

    /**
     * A new e-mail to [CONTACT] in the user's mail app. With none, the address is put on the clipboard and said, as
     * the 2.15.3 did (`LegalSupportSections`): the user can still write from elsewhere.
     */
    fun mail(context: Context, subject: String, body: String? = null) {
        if (Links.mail(context, CONTACT, subject, body) == LaunchOutcome.STARTED) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, CONTACT))
        messages.info(uiText(R.string.about_no_mail_app, CONTACT))
    }

    companion object {
        const val CONTACT = "contact@files-tech.com"
    }
}
