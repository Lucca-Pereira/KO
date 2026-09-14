package com.lucca.ko.ui.recipes.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lucca.ko.data.db.ProposalStatus
import com.lucca.ko.data.db.RecipeChatMessage
import com.lucca.ko.data.remote.nas.RecipeDtoNas
import com.lucca.ko.data.repo.ChatTurn
import com.lucca.ko.data.repo.RecipeChatRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.domain.recipe.RecipeDiff
import com.lucca.ko.ui.koFactory
import com.lucca.ko.ui.nav.RecipeChatRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecipeChatUiState(
    val recipeTitle: String = "",
    val messages: List<RecipeChatMessage> = emptyList(),
    /** The answer arriving right now, before it is persisted. */
    val streaming: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
    val canUndo: Boolean = false,
    val suggestions: List<String> = emptyList(),
)

class RecipeChatViewModel(
    private val chat: RecipeChatRepository,
    private val recipes: RecipeRepository,
    private val recipeId: Long,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    private data class TransientState(
        val streaming: String? = null,
        val sending: Boolean = false,
        val error: String? = null,
        val canUndo: Boolean = false,
    )

    val state = combine(
        recipes.observeRecipe(recipeId),
        chat.observeMessages(recipeId),
        transient,
    ) { details, messages, t ->
        RecipeChatUiState(
            recipeTitle = details?.recipe?.title.orEmpty(),
            messages = messages,
            streaming = t.streaming,
            sending = t.sending,
            error = t.error,
            canUndo = t.canUndo,
            // Openers, shown only on an empty conversation — a blank chat box with a slow model
            // behind it is an invitation to close the screen.
            suggestions = if (messages.isEmpty()) STARTERS else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeChatUiState())

    private val _pendingProposal = MutableStateFlow<ProposalPreview?>(null)
    val pendingProposal = _pendingProposal.asStateFlow()

    data class ProposalPreview(
        val messageId: Long,
        val summary: String,
        val diff: RecipeDiff.Result,
        val proposed: RecipeDtoNas,
    )

    init {
        refreshUndo()
    }

    fun send(message: String) {
        if (message.isBlank() || transient.value.sending) return
        transient.update { it.copy(sending = true, streaming = "", error = null) }
        viewModelScope.launch {
            chat.send(recipeId, message).collect { turn ->
                when (turn) {
                    is ChatTurn.Streaming -> transient.update { it.copy(streaming = turn.text) }
                    is ChatTurn.Finished ->
                        transient.update { it.copy(sending = false, streaming = null) }
                    is ChatTurn.Failed ->
                        transient.update {
                            it.copy(sending = false, streaming = null, error = turn.message)
                        }
                }
            }
        }
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    /** Builds the diff for a proposal so it can be reviewed before it is applied. */
    fun openProposal(message: RecipeChatMessage) {
        val json = message.proposalJson ?: return
        val proposed = chat.decodeProposal(json) ?: return
        viewModelScope.launch {
            val current = recipes.observeRecipe(recipeId).first() ?: return@launch
            _pendingProposal.value = ProposalPreview(
                messageId = message.id,
                summary = message.proposalSummary.orEmpty(),
                proposed = proposed,
                diff = RecipeDiff.compare(
                    beforeTitle = current.recipe.title,
                    afterTitle = proposed.title,
                    beforeServings = current.recipe.servings,
                    afterServings = proposed.servings,
                    beforeIngredients = current.orderedIngredients.map {
                        RecipeDiff.IngredientLine(it.rawName, it.measure.orEmpty())
                    },
                    afterIngredients = proposed.ingredients.map {
                        RecipeDiff.IngredientLine(it.name, it.amount)
                    },
                    beforeSteps = current.orderedSteps.map { it.text },
                    afterSteps = proposed.steps.map { it.text },
                ),
            )
        }
    }

    fun closeProposal() {
        _pendingProposal.value = null
    }

    fun accept(messageId: Long) = viewModelScope.launch {
        chat.acceptProposal(messageId)
        _pendingProposal.value = null
        refreshUndo()
    }

    fun reject(messageId: Long) = viewModelScope.launch {
        chat.rejectProposal(messageId)
        _pendingProposal.value = null
    }

    fun undo() = viewModelScope.launch {
        chat.undoLastChange(recipeId)
        refreshUndo()
    }

    fun clearChat() = viewModelScope.launch { chat.clearChat(recipeId) }

    private fun refreshUndo() = viewModelScope.launch {
        transient.update { it.copy(canUndo = chat.hasUndo(recipeId)) }
    }

    companion object {
        private val STARTERS = listOf(
            "What can I use instead of…?",
            "Make it vegetarian",
            "Double it",
            "I don't have an oven",
            "How do I know when it's done?",
        )

        val Factory = koFactory { container ->
            RecipeChatViewModel(
                chat = container.recipeChatRepository,
                recipes = container.recipeRepository,
                recipeId = createSavedStateHandle().recipeId(),
            )
        }

        private fun SavedStateHandle.recipeId(): Long =
            runCatching { toRoute<RecipeChatRoute>().recipeId }.getOrDefault(0L)
    }
}

/** Whether a message is offering an edit that has not been decided on yet. */
val RecipeChatMessage.hasPendingProposal: Boolean
    get() = proposalJson != null && proposalStatus == ProposalStatus.PENDING
