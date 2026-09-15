package com.lucca.ko.data.repo

import com.lucca.ko.data.db.RecipeRevision
import com.lucca.ko.data.db.dao.RevisionDao
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * The undo stack for a recipe: a snapshot taken before every edit that isn't the user typing
 * into the editor themselves — an imported edit from Claude, primarily.
 *
 * Used by both the manual editor and [AgentImportRepository], so neither has to depend on the
 * other to get it.
 */
class RevisionRepository(private val revisionDao: RevisionDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeRevisions(dishId: Long): Flow<List<RecipeRevision>> = revisionDao.observeRevisions(dishId)

    suspend fun hasUndo(dishId: Long): Boolean = revisionDao.latestRevision(dishId) != null

    /** Records the recipe as it is now, so a later change can be undone. */
    suspend fun snapshot(dishId: Long, details: RecipeWithDetails, reason: String) {
        revisionDao.insertRevision(
            RecipeRevision(
                dishId = dishId,
                reason = reason,
                snapshot = json.encodeToString(RecipeDraft.serializer(), details.toDraft()),
            ),
        )
        revisionDao.trimRevisions(dishId, KEEP_REVISIONS)
    }

    /** Rolls a recipe back to its most recent snapshot. Returns false if there is none. */
    suspend fun undoLastChange(dishId: Long, recipes: RecipeRepository): Boolean {
        val revision = revisionDao.latestRevision(dishId) ?: return false
        val draft = runCatching {
            json.decodeFromString(RecipeDraft.serializer(), revision.snapshot)
        }.getOrNull() ?: return false

        recipes.saveDraft(draft.copy(id = dishId))
        revisionDao.deleteRevision(revision.id)
        return true
    }

    private companion object {
        /** An undo stack, not a history feature. */
        const val KEEP_REVISIONS = 20
    }
}
