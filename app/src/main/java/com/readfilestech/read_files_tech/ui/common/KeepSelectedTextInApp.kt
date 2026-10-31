package com.readfilestech.read_files_tech.ui.common

import android.app.Activity
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * The selection toolbar does not hand the selected text to another app.
 *
 * That toolbar opens over every selection — a field, or a `SelectionContainer` like the text, Markdown, JSON and
 * reader viewers, in which the vault opens its files too. Two doors led out of the app through it, from an app that
 * holds no Internet permission.
 *
 * 1. **Android's text classifier.** On a phone with Google's apps, a long press on a word offers **"Translate"**,
 *    first in the toolbar: one tap hands the selected text to the Google app, which looks it up online. Compose,
 *    and the HTML viewer's WebView, ask the classifier of the screen's context — this activity — through its
 *    [TextClassificationManager]; a dialog or a bottom sheet wraps that context and reads the same manager.
 *    [TextClassifier.NO_OP], set here, covers every field and viewer of every window of the activity; the
 *    application's own manager keeps the system classifier. Notes Tech measured it and closed it in 3.1.1.
 * 2. **Other apps' text actions** (`ACTION_PROCESS_TEXT`). Compose adds to the toolbar every VISIBLE app that
 *    declares one — any app may, under any name. Package visibility does not keep them out: before Android 11 it
 *    does not exist, and from 11 on it is granted per WHOLE app — the apps the manifest's `<queries>` name become
 *    visible with all their text actions. [ComposeFoundationFlags.isSmartSelectionEnabled] set to `false` takes
 *    those actions, and smart selection, out of every Compose toolbar of the process, on every version. The
 *    WebView has its own filter (`SelectionInAppWebView`).
 *
 * What is lost: smart selection (a long press selecting a whole address), the classifier's actions (call, open a
 * map, translate) and reading aloud from the toolbar — still reachable through its own accessibility shortcut.
 * Cut, Copy, Paste and Select all remain: the toolbar keeps editing only, as Notes Tech's note body does.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Activity.keepSelectedTextInApp() {
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
    ComposeFoundationFlags.isSmartSelectionEnabled = false
}
