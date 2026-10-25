package com.readfilestech.read_files_tech.ui.about

import androidx.annotation.StringRes
import com.readfilestech.read_files_tech.R

/**
 * The legal texts About opens. The privacy policy and the terms are Markdown of `res/raw`, in the app's language
 * by Android's own choice of resources — English when it has none; French prevails (their heading says so). The
 * notices and the licence are the repository's NOTICE and LICENSE, copied into the assets by the build: English
 * only, as licences are.
 */
enum class LegalDocument(@param:StringRes val title: Int) {
    PRIVACY(R.string.legal_privacy),
    TERMS(R.string.legal_terms),
    NOTICES(R.string.legal_notices),
    LICENSE(R.string.legal_license),
}
