package com.lucca.ko.ui.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lucca.ko.data.db.AgentMessage
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.data.remote.claude.KitchenTools
import com.lucca.ko.data.repo.AgentRepository
import com.lucca.ko.data.repo.AgentTurn
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.domain.recipe.RecipeDiff
import com.lucca.ko.ui.koFactory
import com.lucca.ko.ui.nav.AgentChatRoute
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentChatUiState(
    val scopeTitle: String = "Ask the agent",
    val messages: List<AgentMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
    val canUndo: Boolean = false,
)

class AgentChatViewModel(
    private val agent: AgentRepository,
    private val recipes: RecipeRepository,
    private val mealPlan: MealPlanRepository,
    private val revisions: RevisionRepository,
    private val recipeId: Long,
    private val date: String,
    private val slot: String,
) : ViewModel() {

    private val sessionKey = when {
        recipeId > 0 -> "recipe:$recipeId"
        date.isNotBlank() -> "plan:$date:$slot"
        else -> "general"
    }

    private data class Transient(val sending: Boolean = false, val error: String? = null, val canUndo: Boolean = false)

    private val transient = MutableStateFlow(Transient())

    init {
        if (recipeId > 0) refreshUndo()
    }

    val state = combine(
        agent.observeMessages(sessionKey),
        if (recipeId > 0) recipes.observeRecipe(recipeId) else flowOf(null),
        transient,
    ) { messages, scopedRecipe, t ->
        AgentChatUiState(
            scopeTitle = when {
                recipeId > 0 -> scopedRecipe?.recipe?.title?.ifBlank { null } ?: "Ask about this recipe"
                date.isNotBlank() -> dayLabel(date, slot)
                else -> "Ask the agent"
            },
            messages = messages,
            sending = t.sending,
            error = t.error,
            canUndo = t.canUndo,
            suggestions = if (messages.isEmpty()) starters() else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentChatUiState())

    private val _pendingProposal = MutableStateFlow<ProposalPreview?>(null)
    val pendingProposal = _pendingProposal.asStateFlow()

    data class ProposalPreview(
        val messageId: Long,
        val summary: String,
        val diff: RecipeDiff.Result?,
        val toolName: String,
        val recipeTitle: String?,
    )

    fun send(text: String) {
        if (text.isBlank() || transient.value.sending) return
        transient.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val prompt = buildSystemPrompt()
            val turn = agent.send(sessionKey, prompt, text)
            transient.update {
                it.copy(
                    sending = false,
                    error = (turn as? AgentTurn.Failed)?.message?.takeIf { m -> m.isNotBlank() },
                )
            }
        }
    }

    fun openProposal(message: AgentMessage) {
        val tool = message.proposalTool ?: return
        val args = message.proposalJson ?: return
        viewModelScope.launch {
            val isRecipe = tool == KitchenTools.SAVE_RECIPE
            val diff = if (isRecipe) runCatching { agent.diffForSaveRecipe(args) }.getOrNull() else null
            val title = if (isRecipe) {
                runCatching {
                    Json.parseToJsonElement(args).jsonObject["title"]?.jsonPrimitive?.content
                }.getOrNull()
            } else {
                null
            }
            _pendingProposal.value = ProposalPreview(
                messageId = message.id,
                summary = message.proposalSummary.orEmpty(),
                diff = diff,
                toolName = tool,
                recipeTitle = title,
            )
        }
    }

    fun closeProposal() {
        _pendingProposal.value = null
    }

    fun accept(messageId: Long) = viewModelScope.launch {
        val proposal = _pendingProposal.value
        agent.resolveProposal(messageId, accepted = true)
        _pendingProposal.value = null
        // Planning a slot used to be one tap (pick a dish, land it on the day); saving the
        // recipe and adding it to that day are two separate tool calls now, so the app does the
        // second half itself rather than making the user ask for it explicitly.
        if (proposal?.messageId == messageId &&
            proposal.toolName == KitchenTools.SAVE_RECIPE &&
            date.isNotBlank() &&
            proposal.recipeTitle != null
        ) {
            addSavedRecipeToPlan(proposal.recipeTitle)
        }
        if (proposal?.toolName == KitchenTools.SAVE_RECIPE && recipeId > 0) refreshUndo()
    }

    fun reject(messageId: Long) = viewModelScope.launch {
        agent.resolveProposal(messageId, accepted = false)
        _pendingProposal.value = null
    }

    fun undo() = viewModelScope.launch {
        if (recipeId > 0) revisions.undoLastChange(recipeId, recipes)
        refreshUndo()
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun clearChat() = viewModelScope.launch { agent.clearChat(sessionKey) }

    private fun refreshUndo() = viewModelScope.launch {
        if (recipeId > 0) transient.update { it.copy(canUndo = revisions.hasUndo(recipeId)) }
    }

    private suspend fun addSavedRecipeToPlan(title: String) {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
        val parsedSlot = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINNER)
        val matches = recipes.searchLibrary(title).first()
        val id = matches.firstOrNull { it.recipe.title.equals(title, ignoreCase = true) }?.recipe?.id
            ?: matches.firstOrNull()?.recipe?.id
            ?: return
        mealPlan.addToPlan(id, parsedDate, parsedSlot)
    }

    private suspend fun buildSystemPrompt(): String = buildString {
        append(
            "You are the recipe agent inside KO Kitchen, a personal kitchen app. You help find " +
                "dishes to cook, write recipes, and manage the user's meal plan and shopping " +
                "list. Keep replies short and conversational, this is a phone chat. Call at " +
                "most one tool per reply and wait for its result before deciding what to do " +
                "next. Never assume what's in the pantry — call get_pantry if it matters to " +
                "what they're asking. Today is ${LocalDate.now()}.",
        )
        when {
            recipeId > 0 -> {
                val details = recipes.observeRecipe(recipeId).first()
                if (details != null) {
                    append(" The user has this recipe open right now (its id is $recipeId): ")
                    append(details.toPromptSummary())
                    append(
                        ". To propose a change to it, call save_recipe with recipeId=$recipeId " +
                            "and the complete updated recipe, not just the changed part.",
                    )
                }
            }

            date.isNotBlank() -> append(
                " They opened this chat to plan ${slot.lowercase()} on $date. Suggest a dish, " +
                    "and once they pick one, call save_recipe for it — the app adds it to that " +
                    "day itself once they confirm the save, so you don't need a separate step " +
                    "for that.",
            )
        }
    }

    private fun starters(): List<String> = when {
        recipeId > 0 -> RECIPE_STARTERS
        date.isNotBlank() -> PLAN_STARTERS
        else -> GENERAL_STARTERS
    }

    companion object {
        private val RECIPE_STARTERS = listOf(
            "What can I use instead of…?",
            "Make it vegetarian",
            "Double it",
            "I don't have an oven",
        )
        private val PLAN_STARTERS = listOf(
            "Something quick with what I have",
            "Surprise me",
            "Nothing too heavy",
        )
        private val GENERAL_STARTERS = listOf(
            "What can I make with what I have?",
            "Give me a dinner idea for tonight",
            "I need to use up something before it goes off",
        )

        val Factory = koFactory { container ->
            val route = createSavedStateHandle().route()
            AgentChatViewModel(
                agent = container.agentRepository,
                recipes = container.recipeRepository,
                mealPlan = container.mealPlanRepository,
                revisions = container.revisionRepository,
                recipeId = route?.recipeId ?: 0L,
                date = route?.date.orEmpty(),
                slot = route?.slot.orEmpty(),
            )
        }

        private fun SavedStateHandle.route(): AgentChatRoute? =
            runCatching { toRoute<AgentChatRoute>() }.getOrNull()
    }
}

/** "Tuesday 16 Sep · Dinner", for the top bar when scoped to a plan slot. */
private fun dayLabel(date: String, slot: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "Ask the agent"
    val weekday = parsed.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val month = parsed.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val slotLabel = slot.lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday ${parsed.dayOfMonth} $month · $slotLabel"
}

private fun RecipeWithDetails.toPromptSummary(): String = buildString {
    append(recipe.title)
    append(", serves ${recipe.servings}")
    if (orderedIngredients.isNotEmpty()) {
        append(". Ingredients: ")
        append(
            orderedIngredients.joinToString("; ") {
                listOfNotNull(it.measure, it.rawName).filter { s -> s.isNotBlank() }.joinToString(" ")
            },
        )
    }
    if (orderedSteps.isNotEmpty()) {
        append(". Steps: ")
        append(orderedSteps.joinToString(" ") { it.text })
    }
}
