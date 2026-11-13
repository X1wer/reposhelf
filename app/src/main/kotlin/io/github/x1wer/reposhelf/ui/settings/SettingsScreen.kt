// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.background.Notifier
import io.github.x1wer.reposhelf.core.data.settings.BackgroundRun
import io.github.x1wer.reposhelf.core.update.CheckFrequency
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import io.github.x1wer.reposhelf.ui.common.NotificationPermissionGate
import io.github.x1wer.reposhelf.ui.common.formatDateTime
import io.github.x1wer.reposhelf.ui.common.startSafely
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<GlobalSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())
    val lastRun: StateFlow<BackgroundRun?> = container.settings.lastBackgroundRun.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notificationsAllowed = MutableStateFlow(true)
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

    fun refreshNotificationState() {
        _notificationsAllowed.value = container.notifier.canPost(Notifier.CH_UPDATES)
    }

    fun update(transform: (GlobalSettings) -> GlobalSettings) = viewModelScope.launch { container.settings.update(transform) }

    companion object {
        val Factory = viewModelFactory { initializer { SettingsViewModel((this[APPLICATION_KEY] as RepoShelfApplication).container) } }
    }
}

/** Beállítások (C. 12.2–12.6., 13.): gyakoriság, hálózat, automatikus letöltés és telepítés, kiadások, értesítések. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()
    val notificationsAllowed by viewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = (context.applicationContext as RepoShelfApplication).container
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationState() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_defaults_note), style = MaterialTheme.typography.bodySmall)

            Section(stringResource(R.string.settings_section_checks))
            Text(stringResource(R.string.settings_frequency), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = settings.checkFrequency == frequency,
                        onClick = { viewModel.update { it.copy(checkFrequency = frequency) } },
                        label = { Text(stringResource(frequency.labelRes())) },
                    )
                }
            }
            SwitchRow(stringResource(R.string.settings_check_unmetered), settings.checkOnlyUnmetered) { v -> viewModel.update { it.copy(checkOnlyUnmetered = v) } }
            Text(stringResource(R.string.settings_timing_note), style = MaterialTheme.typography.bodySmall)
            val run = lastRun
            Text(
                run?.let { stringResource(R.string.settings_last_run, formatDateTime(it.at), it.checked, it.updates) } ?: stringResource(R.string.settings_last_run_never),
                style = MaterialTheme.typography.bodySmall,
            )
            if (run != null && run.notChecked > 0) {
                Text(pluralStringResource(R.plurals.settings_last_run_limited, run.notChecked, run.notChecked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // Csak magyarázat és a rendszer saját képernyője: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS-t nem kérünk (D-064).
            OutlinedButton(onClick = { startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text(stringResource(R.string.settings_battery))
            }

            Section(stringResource(R.string.settings_section_install))
            SwitchRow(stringResource(R.string.settings_auto_download), settings.autoDownload) { v -> viewModel.update { it.copy(autoDownload = v) } }
            SwitchRow(stringResource(R.string.settings_auto_download_metered), settings.autoDownloadOnMetered, enabled = settings.autoDownload || settings.autoInstall) { v ->
                viewModel.update { it.copy(autoDownloadOnMetered = v) }
            }
            SwitchRow(stringResource(R.string.settings_auto_install), settings.autoInstall) { v -> viewModel.update { it.copy(autoInstall = v) } }
            Text(stringResource(R.string.settings_auto_install_note), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.settings_update_ownership), settings.requestUpdateOwnership) { v -> viewModel.update { it.copy(requestUpdateOwnership = v) } }

            Section(stringResource(R.string.settings_section_releases))
            SwitchRow(stringResource(R.string.settings_prereleases), settings.includePrereleases) { v -> viewModel.update { it.copy(includePrereleases = v) } }
            Text(stringResource(R.string.settings_delay), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlobalSettings.RELEASE_DELAY_CHOICES.forEach { days ->
                    FilterChip(
                        selected = settings.releaseDelayDays == days,
                        onClick = { viewModel.update { it.copy(releaseDelayDays = days) } },
                        label = { Text(delayLabel(days)) },
                    )
                }
            }

            Section(stringResource(R.string.settings_section_notifications))
            NotificationPermissionGate { request ->
                SwitchRow(stringResource(R.string.settings_notify_updates), settings.notifyUpdates) { v ->
                    viewModel.update { it.copy(notifyUpdates = v) }
                    if (v) request()
                }
            }
            if (!notificationsAllowed) Text(stringResource(R.string.notif_disabled_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { startSafely(context, container.notifier.settingsIntent()) }) { Text(stringResource(R.string.action_notification_settings)) }

            Section(stringResource(R.string.appearance_section))
            AppearanceSection(container)

            Section(stringResource(R.string.transfer_section))
            TransferSection(container)

            Section(stringResource(R.string.self_section))
            SelfUpdateSection(container, settings.selfUpdateCheck) { v -> viewModel.update { it.copy(selfUpdateCheck = v) } }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // A sor egésze kapcsoló (TalkBack egyetlen elemként olvassa fel), ezért a Switch maga nem kattintható külön.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

fun CheckFrequency.labelRes(): Int = when (this) {
    CheckFrequency.EVERY_6_HOURS -> R.string.freq_6h
    CheckFrequency.EVERY_12_HOURS -> R.string.freq_12h
    CheckFrequency.DAILY -> R.string.freq_daily
    CheckFrequency.WEEKLY -> R.string.freq_weekly
    CheckFrequency.MANUAL -> R.string.freq_manual
}

@Composable
fun delayLabel(days: Int): String =
    if (days == 0) stringResource(R.string.delay_none) else pluralStringResource(R.plurals.delay_days, days, days)
