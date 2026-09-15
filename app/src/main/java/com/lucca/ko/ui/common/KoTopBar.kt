package com.lucca.ko.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How every screen reaches Settings in one tap, without every screen function needing an
 * `onOpenSettings` parameter threaded down from [com.lucca.ko.ui.nav.KoRoot].
 *
 * Provided once, near the `NavHost`; [KoTopBar] reads it by default so a screen only needs to
 * opt out (pass `showSettings = false`) rather than opt in.
 */
val LocalOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The one top bar shape every screen in the app uses.
 *
 * Settings used to be a gear icon that some screens remembered to add and others didn't — this
 * makes it structural instead: every [KoTopBar] carries one unless a screen deliberately opts
 * out, so Settings is never more than a single tap away from wherever you are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    showSettings: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    val openSettings = LocalOpenSettings.current
    TopAppBar(
        title = {
            if (subtitle.isNullOrBlank()) {
                Text(title, maxLines = 1)
            } else {
                androidx.compose.foundation.layout.Column {
                    Text(title, maxLines = 1)
                    Text(
                        subtitle,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            actions()
            if (showSettings) {
                IconButton(onClick = openSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        },
    )
}
