package com.lucca.ko.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucca.ko.data.remote.nas.NasStatus
import com.lucca.ko.data.remote.nas.NasStatusMonitor

/**
 * One app-wide "the brain is offline" banner.
 *
 * Every screen used to invent its own error string when a call failed, so one NAS being off
 * looked like five unrelated problems. Tapping it retries.
 *
 * Nothing is shown for [NasStatus.Unknown] or [NasStatus.Checking]: a banner that flashes on
 * every cold start trains you to ignore it.
 */
@Composable
fun NasStatusBanner(monitor: NasStatusMonitor, modifier: Modifier = Modifier) {
    val status by monitor.status.collectAsStateWithLifecycle()
    val down = status as? NasStatus.Down

    AnimatedVisibility(visible = down != null, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { monitor.refreshNow() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "The recipe bot is offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    down?.message.orEmpty() + "  Tap to retry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
