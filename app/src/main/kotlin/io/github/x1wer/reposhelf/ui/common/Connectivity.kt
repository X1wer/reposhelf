// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Van-e internetkapcsolat (C. 24.: offline állapotban a helyi funkciók működnek, a csak online műveleteknél tájékoztatás).
 * A rendszer alapértelmezett hálózatát figyeli; jogosultságot nem kér (az `ACCESS_NETWORK_STATE` normál engedély).
 */
class ConnectivityMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(current())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    private fun current(): Boolean {
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Állandó sáv offline állapotban: mi működik, és mi nem (a felolvasó is jelzi a változást). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.offline_banner),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}
