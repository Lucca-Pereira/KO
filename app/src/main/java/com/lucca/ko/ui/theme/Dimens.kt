package com.lucca.ko.ui.theme

import androidx.compose.ui.unit.dp

/**
 * A named spacing scale, so a new screen reaches for `Dimens.m` instead of inventing its own
 * `16.dp`. Existing screens keep their own literal paddings — this isn't a retrofit of the whole
 * app — but everything added from here on (the agent chat, the shared top bar) uses it.
 */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
}
