package com.readfilestech.read_files_tech.ui.about

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.readfilestech.read_files_tech.BuildConfig
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes

/** The releases page, opened in the browser: the app checks nothing online itself (docs D-014, as the 2.15.3). */
private const val RELEASES_URL = "https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest"
private const val WEBSITE_URL = "https://www.files-tech.com"
private const val WEBSITE_HOST = "files-tech.com"
private const val AUTHOR = "Patrice Haltaya"
private const val COPYRIGHT_YEAR = "2026"
private const val ICON_SIZE = 80

/** A feature of the app as this screen lists it: what it is called, and what it does. */
private class FeatureLine(val icon: ImageVector, val title: Int, val description: Int)

/** A legal text of About: the document it opens, and a line under its title for the two that need one. */
private class LegalLine(val icon: ImageVector, val document: LegalDocument, val summary: Int? = null)

private val LEGAL = listOf(
    LegalLine(Icons.Outlined.PrivacyTip, LegalDocument.PRIVACY),
    LegalLine(Icons.Outlined.Gavel, LegalDocument.TERMS),
    LegalLine(Icons.Outlined.Copyright, LegalDocument.NOTICES, R.string.legal_notices_summary),
    LegalLine(Icons.Outlined.Copyright, LegalDocument.LICENSE, R.string.legal_license_summary),
)

/** What the app does, read against the port — not the 2.15.3's list, which named ML Kit and the PDF signature. */
private val FEATURES = listOf(
    FeatureLine(Icons.Outlined.Visibility, R.string.about_feature_viewer, R.string.about_feature_viewer_desc),
    FeatureLine(Icons.Outlined.Edit, R.string.feature_editor, R.string.about_feature_editor_desc),
    FeatureLine(Icons.Outlined.Folder, R.string.explorer_title, R.string.about_feature_explorer_desc),
    FeatureLine(Icons.Outlined.Build, R.string.about_feature_tools, R.string.about_feature_tools_desc),
    FeatureLine(Icons.Outlined.Home, R.string.about_feature_home, R.string.about_feature_home_desc),
    FeatureLine(Icons.Outlined.Shield, R.string.feature_vault, R.string.about_feature_vault_desc),
    FeatureLine(Icons.Outlined.Transform, R.string.feature_convert, R.string.about_feature_convert_desc),
    FeatureLine(Icons.Outlined.TextFields, R.string.feature_ocr, R.string.about_feature_ocr_desc),
    FeatureLine(Icons.Outlined.DocumentScanner, R.string.feature_scanner, R.string.about_feature_scanner_desc),
    FeatureLine(Icons.Outlined.Compress, R.string.feature_compress, R.string.about_feature_compress_desc),
    FeatureLine(Icons.Outlined.CleaningServices, R.string.feature_exif, R.string.about_feature_exif_desc),
    FeatureLine(Icons.Outlined.DriveFileRenameOutline, R.string.about_feature_rename, R.string.about_feature_rename_desc),
    FeatureLine(Icons.AutoMirrored.Outlined.MenuBook, R.string.about_feature_reader, R.string.about_feature_reader_desc),
    FeatureLine(Icons.Outlined.DashboardCustomize, R.string.about_feature_tiles, R.string.about_feature_tiles_desc),
    FeatureLine(Icons.Outlined.CloudUpload, R.string.about_feature_cloud, R.string.about_feature_cloud_desc),
    FeatureLine(Icons.Outlined.TravelExplore, R.string.about_feature_search, R.string.about_feature_search_desc),
    FeatureLine(Icons.Outlined.ContentCopy, R.string.feature_duplicates, R.string.about_feature_duplicates_desc),
)

/**
 * About (`AboutScreen` of the 2.15.3): the app and its version, "Check for updates" — the releases page in the
 * browser —, what it promises and does, its author, the settings, quick help, support and the legal texts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigate: (String) -> Unit, onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(viewModel)
            Section(stringResource(R.string.about_privacy))
            PrivacyCard()
            Section(stringResource(R.string.about_features))
            Card(Modifier.fillMaxWidth()) {
                FEATURES.forEach { Line(it.icon, stringResource(it.title), stringResource(it.description)) }
            }
            Section(stringResource(R.string.about_author))
            Card(Modifier.fillMaxWidth()) { Line(Icons.Outlined.Person, AUTHOR, stringResource(R.string.about_author_role)) }
            Section(stringResource(R.string.feature_settings))
            Card(Modifier.fillMaxWidth()) {
                Line(
                    Icons.Outlined.Settings,
                    stringResource(R.string.about_settings),
                    stringResource(R.string.about_settings_summary),
                    trailing = Icons.AutoMirrored.Filled.ArrowForward,
                ) { onNavigate(Routes.feature(Feature.SETTINGS)) }
            }
            Section(stringResource(R.string.about_help))
            HelpCard(R.string.about_help_open, listOf(R.string.about_help_open_1, R.string.about_help_open_2, R.string.about_help_open_3))
            HelpCard(R.string.about_help_access, listOf(R.string.about_help_access_1, R.string.about_help_access_2))
            HelpCard(R.string.about_help_updates, listOf(R.string.about_help_updates_1, R.string.about_help_updates_2))
            Section(stringResource(R.string.about_support))
            SupportCard(viewModel)
            Section(stringResource(R.string.about_legal))
            Card(Modifier.fillMaxWidth()) {
                LEGAL.forEachIndexed { index, line ->
                    if (index > 0) HorizontalDivider()
                    Line(
                        line.icon,
                        stringResource(line.document.title),
                        line.summary?.let { stringResource(it) },
                        Icons.AutoMirrored.Filled.ArrowForward,
                    ) { onNavigate(Routes.legal(line.document)) }
                }
            }
            Text(
                "© $COPYRIGHT_YEAR $AUTHOR — Files Tech",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(viewModel: AboutViewModel) {
    val context = LocalContext.current
    val icon = remember(context) {
        val size = (ICON_SIZE * context.resources.displayMetrics.density).toInt()
        context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap(size, size).asImageBitmap()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE.dp).clip(RoundedCornerShape(20.dp)))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = { viewModel.open(context, RELEASES_URL) }) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.about_check_updates), Modifier.padding(start = 8.dp))
        }
        Text(stringResource(R.string.about_check_updates_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/** What the app promises, each verifiable: the CI fails on a network permission or on anything of Google in the APK. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.string.about_badge_no_ads,
                    R.string.about_badge_no_trackers,
                    R.string.about_badge_no_internet,
                    R.string.about_badge_no_collection,
                    R.string.about_badge_no_sharing,
                    R.string.about_badge_no_google,
                ).forEach { badge ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                        Text(
                            stringResource(badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Contact, website, a bug report with the version filled in (`LegalSupportSections` of the core). With no mail
 * app, the address is copied and said, as the core did.
 */
@Composable
private fun SupportCard(viewModel: AboutViewModel) {
    val context = LocalContext.current
    // The subjects name the app and its version, in any language: what the developer sorts the mail by.
    val supportSubject = "Read Files Tech ${BuildConfig.VERSION_NAME} — support"
    val bugSubject = "Read Files Tech ${BuildConfig.VERSION_NAME} — bug"
    val bugBody = stringResource(R.string.about_mail_bug_body, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
    Card(Modifier.fillMaxWidth()) {
        Line(Icons.Outlined.Email, stringResource(R.string.about_contact), AboutViewModel.CONTACT, Icons.AutoMirrored.Outlined.OpenInNew) {
            viewModel.mail(context, supportSubject)
        }
        HorizontalDivider()
        Line(Icons.Outlined.Public, stringResource(R.string.about_website), WEBSITE_HOST, Icons.AutoMirrored.Outlined.OpenInNew) {
            viewModel.open(context, WEBSITE_URL)
        }
        HorizontalDivider()
        Line(Icons.Outlined.BugReport, stringResource(R.string.about_report_bug), stringResource(R.string.about_report_bug_summary)) {
            viewModel.mail(context, bugSubject, bugBody)
        }
    }
}

@Composable
private fun HelpCard(title: Int, steps: List<Int>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp).padding(top = 12.dp).semantics { heading() },
    )
}

/** A line of a card: [icon], [title], [summary]; tappable when [onClick] is given, [trailing] then saying where it leads. */
@Composable
private fun Line(icon: ImageVector, title: String, summary: String? = null, trailing: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val tap = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        tap.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) Icon(trailing, contentDescription = null)
    }
}
