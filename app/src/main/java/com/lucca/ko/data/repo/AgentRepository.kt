package com.lucca.ko.data.repo

import com.lucca.ko.data.db.AgentMessage
import com.lucca.ko.data.db.ChatRole
import com.lucca.ko.data.db.ProposalStatus
import com.lucca.ko.data.db.dao.ChatDao
import com.lucca.ko.data.remote.claude.ClaudeClient
import com.lucca.ko.data.remote.claude.ClaudeBlock
import com.lucca.ko.data.remote.claude.ClaudeMessage
import com.lucca.ko.data.remote.claude.KitchenTools
import com.lucca.ko.data.remote.claude.ToolOutcome
import com.lucca.ko.data.remote.claude.firstToolUse
import com.lucca.ko.data.remote.claude.text
import com.lucca.ko.data.remote.claude.textMessage
import kotlinx.coroutines.flow.Flow

/** How a chat turn with the agent came out. */
sealed interface AgentTurn {
    data class Answered(val message: AgentMessage) : AgentTurn

    data class Failed(val message: String) : AgentTurn
}

/**
 * One conversation with the recipe agent — general, attached to a recipe, or attached to a plan
 * slot, distinguished only by the `sessionKey` the caller passes in.
 *
 * The tool loop lives here: a read-only tool call ([ToolOutcome.Immediate]) is answered straight
 * away and the conversation keeps going without the user noticing; a tool that would write
 * something ([ToolOutcome.NeedsConfirmation]) stops the turn and is persisted as a pending
 * proposal on the assistant's message, mirroring how the old NAS chat's recipe proposals worked.
 * Accepting or rejecting a proposal is a local decision — see [resolveProposal] — with no further
 * call to Claude, exactly as accepting an edit never used to re-ask the model either.
 */
class AgentRepository(
    private val chatDao: ChatDao,
    private val claude: ClaudeClient,
    private val tools: KitchenTools,
) {
    fun observeMessages(sessionKey: String): Flow<List<AgentMessage>> = chatDao.observeMessages(sessionKey)

    suspend fun clearChat(sessionKey: String) = chatDao.clearChat(sessionKey)

    suspend fun send(sessionKey: String, systemPrompt: String, userText: String): AgentTurn {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return AgentTurn.Failed("")

        chatDao.insertMessage(AgentMessage(sessionKey = sessionKey, role = ChatRole.USER, content = trimmed))

        val working: MutableList<ClaudeMessage> = chatDao.messagesFor(sessionKey)
            .takeLast(HISTORY_WINDOW)
            .map { textMessage(if (it.role == ChatRole.USER) "user" else "assistant", it.content) }
            .toMutableList()

        var hops = 0
        while (true) {
            hops++
            val response = try {
                claude.send(working, tools.definitions, systemPrompt)
            } catch (e: Exception) {
                return AgentTurn.Failed(e.message ?: "Couldn't reach Claude.")
            }

            val toolUse = response.firstToolUse()
            val text = response.text()

            if (toolUse == null || hops > MAX_TOOL_HOPS) {
                return AgentTurn.Answered(persist(sessionKey, text.ifBlank { "…" }))
            }

            when (val outcome = tools.run(toolUse)) {
                is ToolOutcome.Immediate -> {
                    working += ClaudeMessage(role = "assistant", content = response.content)
                    working += ClaudeMessage(
                        role = "user",
                        content = listOf(
                            ClaudeBlock.ToolResult(toolUseId = toolUse.id, content = outcome.resultJson),
                        ),
                    )
                }

                is ToolOutcome.NeedsConfirmation -> {
                    return AgentTurn.Answered(
                        persist(
                            sessionKey,
                            text.ifBlank { outcome.summary },
                            proposalTool = outcome.toolName,
                            proposalJson = outcome.argsJson,
                            proposalSummary = outcome.summary,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Applies (or discards) a pending proposal. Returns the tool's own short confirmation line
     * on acceptance, e.g. "Saved to your library." — the caller may use it, but the persisted
     * message already carries [AgentMessage.proposalSummary] for the chat log itself.
     */
    suspend fun resolveProposal(messageId: Long, accepted: Boolean): String? {
        val message = chatDao.messageById(messageId) ?: return null
        val tool = message.proposalTool
        val args = message.proposalJson
        val result = if (accepted && tool != null && args != null) {
            tools.applyConfirmed(tool, args)
        } else {
            null
        }
        chatDao.updateMessage(
            message.copy(proposalStatus = if (accepted) ProposalStatus.ACCEPTED else ProposalStatus.REJECTED),
        )
        return result
    }

    suspend fun diffForSaveRecipe(argsJson: String) = tools.diffForSaveRecipe(argsJson)

    private suspend fun persist(
        sessionKey: String,
        text: String,
        proposalTool: String? = null,
        proposalJson: String? = null,
        proposalSummary: String? = null,
    ): AgentMessage {
        val message = AgentMessage(
            sessionKey = sessionKey,
            role = ChatRole.ASSISTANT,
            content = text,
            proposalTool = proposalTool,
            proposalJson = proposalJson,
            proposalSummary = proposalSummary,
            proposalStatus = if (proposalJson != null) ProposalStatus.PENDING else null,
        )
        return message.copy(id = chatDao.insertMessage(message))
    }

    private companion object {
        /** Turns of history resent to Claude on every message. */
        const val HISTORY_WINDOW = 16

        /** Read-only tool round-trips allowed before a turn just answers with whatever it has. */
        const val MAX_TOOL_HOPS = 4
    }
}
