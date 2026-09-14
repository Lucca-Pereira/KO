package com.lucca.ko.ui.recipes.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.ChatRole
import com.lucca.ko.data.db.ProposalStatus
import com.lucca.ko.data.db.RecipeChatMessage
import com.lucca.ko.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeChatScreen(
    onBack: () -> Unit,
    vm: RecipeChatViewModel = viewModel(factory = RecipeChatViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val proposal by vm.pendingProposal.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    // Follow the answer as it streams rather than making the user chase it.
    LaunchedEffect(state.messages.size, state.streaming) {
        val lastIndex = state.messages.size + if (state.streaming != null) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    proposal?.let { preview ->
        ProposalDialog(
            summary = preview.summary,
            diff = preview.diff,
            onAccept = { vm.accept(preview.messageId) },
            onReject = { vm.reject(preview.messageId) },
            onDismiss = vm::closeProposal,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ask about this recipe")
                        if (state.recipeTitle.isNotBlank()) {
                            Text(
                                state.recipeTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.canUndo) {
                        IconButton(onClick = vm::undo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo the last change")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f)) {
                if (state.messages.isEmpty() && state.streaming == null) {
                    EmptyState(
                        title = "Ask anything about this recipe",
                        subtitle = "Swaps, scaling, technique, what to do without an oven. " +
                            "If you ask for a change, you'll get to review it before it's applied.",
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onReviewProposal = { vm.openProposal(message) },
                        )
                    }
                    state.streaming?.let { partial ->
                        item(key = "streaming") {
                            Bubble(fromUser = false) {
                                if (partial.isBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Text(
                                            "Thinking…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontStyle = FontStyle.Italic,
                                        )
                                    }
                                } else {
                                    Text(partial, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            if (state.suggestions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.suggestions.forEach { starter ->
                        AssistChip(onClick = { input = starter }, label = { Text(starter) })
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask something…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && !state.sending,
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: RecipeChatMessage, onReviewProposal: () -> Unit) {
    val fromUser = message.role == ChatRole.USER
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Bubble(fromUser = fromUser) {
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            message.hasPendingProposal ->
                ProposalChip(
                    summary = message.proposalSummary.orEmpty(),
                    onReview = onReviewProposal,
                    modifier = Modifier.fillMaxWidth(0.9f),
                )

            message.proposalStatus == ProposalStatus.ACCEPTED ->
                Text(
                    "✓ Applied: ${message.proposalSummary.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColour(accepted = true),
                )

            message.proposalStatus == ProposalStatus.REJECTED ->
                Text(
                    "Discarded: ${message.proposalSummary.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColour(accepted = false),
                )
        }
    }
}

@Composable
private fun Bubble(fromUser: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/** Kept for the empty-state affordance in the top bar overflow. */
@Composable
internal fun ClearChatButton(onClear: () -> Unit) {
    TextButton(onClick = onClear) { Text("Clear conversation") }
}
