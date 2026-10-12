package com.readfilestech.read_files_tech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.navigation.Feature

/**
 * A destination whose screen comes with a later phase of the port (scanner, OCR, vault, the tools): it
 * says so. Reached from the quick-settings tiles, which must keep working from the first build (docs
 * D-012), and from the home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(feature: Feature?, onBack: () -> Unit) {
    val title = feature?.title ?: R.string.app_name
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pending_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pending_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
