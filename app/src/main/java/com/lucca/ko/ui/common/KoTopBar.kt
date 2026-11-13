package com.lucca.ko.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opens the main menu (the navigation drawer) from any screen, without every screen function
 * needing an `onOpenMenu` parameter threaded down from [com.lucca.ko.ui.nav.KoRoot].
 *
 * Provided once, around the `NavHost`.
 */
val LocalOpenMenu = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The one top bar shape every screen in the app uses.
 *
 * A screen that passes no [navigationIcon] is a top-level section and gets the menu button; a
 * screen reached from another one passes its own back arrow. Settings lives in the main menu, so
 * the bar carries only the screen's own [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val openMenu = LocalOpenMenu.current
    TopAppBar(
        title = {
            if (subtitle.isNullOrBlank()) {
                Text(title, maxLines = 1)
            } else {
                Column {
                    Text(title, maxLines = 1)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = navigationIcon ?: {
            IconButton(onClick = openMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
        },
        actions = { actions() },
    )
}
