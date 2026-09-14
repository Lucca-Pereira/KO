package com.lucca.ko.data.repo

import com.lucca.ko.data.db.ChatRole
import com.lucca.ko.data.db.ProposalStatus
import com.lucca.ko.data.db.RecipeChatMessage
import com.lucca.ko.data.db.RecipeRevision
import com.lucca.ko.data.db.dao.ChatDao
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.data.remote.nas.ChatEvent
import com.lucca.ko.data.remote.nas.ChatMessageDto
import com.lucca.ko.data.remote.nas.ChatRequestDto
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasStatusMonitor
import com.lucca.ko.data.remote.nas.PantryEntryDto
import com.lucca.ko.data.remote.nas.RecipeDtoNas
import com.lucca.ko.data.remote.nas.RecipeIngredientDtoNas
import com.lucca.ko.data.remote.nas.RecipeSnapshotDto
import com.lucca.ko.data.remote.nas.RecipeStepDtoNas
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** How the streaming answer is progressing, for the screen to render. */
sealed interface ChatTurn {
    data class Streaming(val text: String) : ChatTurn

    data object Finished : ChatTurn

    data class Failed(val message: String) : ChatTurn
}

/**
 * The conversation about one recipe.
 *
 * The message log lives on the phone; the server is sent the current recipe plus a window of
 * recent turns on every request, because the phone owns the recipe and it may have changed
 * between messages.
 */
class RecipeChatRepository(
    private val chatDao: ChatDao,
    private val recipeDao: RecipeDao,
    private val pantryDao: PantryDao,
    private val recipes: RecipeRepository,
    private val nas: NasClient,
    private val nasStatus: NasStatusMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeMessages(dishId: Long): Flow<List<RecipeChatMessage>> = chatDao.observeMessages(dishId)

    fun observeRevisions(dishId: Long): Flow<List<RecipeRevision>> = chatDao.observeRevisions(dishId)

    suspend fun clearChat(dishId: Long) = chatDao.clearChat(dishId)

    /**
     * Sends one turn and streams the answer back.
     *
     * The user's message is persisted immediately, before the request goes out: if the brain is
     * down, what you typed should still be there rather than vanishing with the error.
     */
    fun send(dishId: Long, message: String): Flow<ChatTurn> = kotlinx.coroutines.flow.flow {
        val details = recipeDao.recipeWithDetailsOnce(dishId)
        if (details == null) {
            emit(ChatTurn.Failed("That recipe no longer exists."))
            return@flow
        }

        chatDao.insertMessage(
            RecipeChatMessage(dishId = dishId, role = ChatRole.USER, content = message.trim()),
        )

        val history = chatDao.messagesFor(dishId)
            .dropLast(1) // the message just inserted is sent separately
            .takeLast(HISTORY_WINDOW)
            .map {
                ChatMessageDto(
                    role = if (it.role == ChatRole.USER) "user" else "assistant",
                    content = it.content,
                )
            }

        val request = ChatRequestDto(
            recipe = details.toSnapshot(),
            history = history,
            message = message.trim(),
            pantry = pantryDao.getAll().map {
                PantryEntryDto(name = it.name, searchName = it.searchName, status = it.status.name)
            },
        )

        val answer = StringBuilder()
        var proposal: ChatEvent.Proposal? = null
        var failure: String? = null

        nas.chat(request).collect { event ->
            when (event) {
                is ChatEvent.Token -> {
                    answer.append(event.text)
                    emit(ChatTurn.Streaming(answer.toString()))
                }
                is ChatEvent.Proposal -> proposal = event
                is ChatEvent.Done -> Unit
                is ChatEvent.Failed -> failure = event.message
            }
        }

        if (failure != null) {
            nasStatus.reportUnreachable(failure!!)
            // The partial answer is kept when there is one: half an answer beats none, and
            // throwing it away also throws away the context of whatever went wrong.
            if (answer.isNotBlank()) persistAnswer(dishId, answer.toString(), proposal)
            emit(ChatTurn.Failed(failure!!))
            return@flow
        }

        nasStatus.reportReachable()
        persistAnswer(dishId, answer.toString(), proposal)
        emit(ChatTurn.Finished)
    }

    private suspend fun persistAnswer(
        dishId: Long,
        answer: String,
        proposal: ChatEvent.Proposal?,
    ) {
        chatDao.insertMessage(
            RecipeChatMessage(
                dishId = dishId,
                role = ChatRole.ASSISTANT,
                content = answer.trim(),
                proposalJson = proposal?.let { json.encodeToString(RecipeDtoNas.serializer(), it.recipe) },
                proposalSummary = proposal?.summary,
                proposalStatus = proposal?.let { ProposalStatus.PENDING },
            ),
        )
    }

    fun decodeProposal(messageJson: String): RecipeDtoNas? =
        runCatching { json.decodeFromString(RecipeDtoNas.serializer(), messageJson) }.getOrNull()

    /**
     * Applies a proposal, after snapshotting the recipe as it was.
     *
     * The snapshot is the whole point: a 7B applying "make it dairy-free" will sometimes swap the
     * cream and leave the butter, and finding that out after the fact should cost one tap, not a
     * retyped recipe.
     */
    suspend fun acceptProposal(messageId: Long) {
        val message = chatDao.messageById(messageId) ?: return
        val proposalJson = message.proposalJson ?: return
        val proposed = decodeProposal(proposalJson) ?: return
        val current = recipeDao.recipeWithDetailsOnce(message.dishId) ?: return

        snapshot(message.dishId, current, reason = "chat edit")

        val draft = current.toDraft().copy(
            title = proposed.title.ifBlank { current.recipe.title },
            servingsText = proposed.servings.toString(),
            prepText = proposed.prepMinutes?.toString().orEmpty(),
            cookText = proposed.cookMinutes?.toString().orEmpty(),
            notes = proposed.notes ?: current.recipe.notes.orEmpty(),
            ingredients = proposed.ingredients.mapIndexed { index, i ->
                IngredientDraft(
                    key = -(index + 1L),
                    name = i.name,
                    amount = i.amount,
                    optional = i.optional,
                    section = i.section,
                )
            },
            steps = proposed.steps.mapIndexed { index, s ->
                StepDraft(
                    key = -(index + 1L),
                    text = s.text,
                    minutesText = s.minutes?.toString().orEmpty(),
                )
            },
            tags = (current.tags.map { it.name } + proposed.tags).distinctBy { it.lowercase() },
            // The macros described the old ingredients; keeping them would be a lie the UI
            // would happily display. Cleared, so the nutrition card asks to re-estimate.
            kcalPerServing = null,
            proteinG = null,
            carbsG = null,
            fatG = null,
            macroSource = null,
            macroNote = null,
        )
        recipes.saveDraft(draft)

        chatDao.updateMessage(message.copy(proposalStatus = ProposalStatus.ACCEPTED))
        chatDao.trimRevisions(message.dishId, KEEP_REVISIONS)
    }

    suspend fun rejectProposal(messageId: Long) {
        val message = chatDao.messageById(messageId) ?: return
        chatDao.updateMessage(message.copy(proposalStatus = ProposalStatus.REJECTED))
    }

    /** Records the recipe as it is now, so a later change can be undone. */
    suspend fun snapshot(dishId: Long, details: RecipeWithDetails, reason: String) {
        chatDao.insertRevision(
            RecipeRevision(
                dishId = dishId,
                reason = reason,
                snapshot = json.encodeToString(RecipeDraft.serializer(), details.toDraft()),
            ),
        )
        chatDao.trimRevisions(dishId, KEEP_REVISIONS)
    }

    /** Rolls the recipe back to the most recent snapshot. Returns false if there is none. */
    suspend fun undoLastChange(dishId: Long): Boolean {
        val revision = chatDao.latestRevision(dishId) ?: return false
        val draft = runCatching {
            json.decodeFromString(RecipeDraft.serializer(), revision.snapshot)
        }.getOrNull() ?: return false

        recipes.saveDraft(draft.copy(id = dishId))
        chatDao.deleteRevision(revision.id)
        return true
    }

    suspend fun hasUndo(dishId: Long): Boolean = chatDao.latestRevision(dishId) != null

    suspend fun pantryNames(): List<String> = pantryDao.getAll().map { it.name }

    private companion object {
        /** Turns of history resent to the server. It resends the recipe every time anyway. */
        const val HISTORY_WINDOW = 6

        /** An undo stack, not a history feature. */
        const val KEEP_REVISIONS = 20
    }
}

private fun RecipeWithDetails.toSnapshot(): RecipeSnapshotDto = RecipeSnapshotDto(
    title = recipe.title,
    servings = recipe.servings,
    ingredients = orderedIngredients.map {
        RecipeIngredientDtoNas(
            name = it.rawName,
            amount = it.measure.orEmpty(),
            optional = it.optional,
            section = it.section,
        )
    },
    steps = orderedSteps.map { RecipeStepDtoNas(text = it.text, minutes = it.minutes) },
    notes = recipe.notes,
)
