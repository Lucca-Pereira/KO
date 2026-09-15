package com.lucca.ko.data.remote.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The wire contract with the Anthropic Messages API (https://api.anthropic.com/v1/messages).
 *
 * Hand-rolled rather than pulling in a client SDK: this app needs one endpoint, in one shape,
 * with tool use — a dozen small DTOs is less surface area than a general-purpose SDK dependency.
 */

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 1536,
    val system: String? = null,
    val messages: List<ClaudeMessage>,
    val tools: List<ClaudeTool> = emptyList(),
    @SerialName("tool_choice") val toolChoice: ClaudeToolChoice? = null,
)

@Serializable
data class ClaudeMessage(val role: String, val content: List<ClaudeBlock>)

@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
data class ClaudeToolChoice(val type: String, val name: String? = null)

@Serializable
data class ClaudeResponse(
    val id: String = "",
    val role: String = "assistant",
    val content: List<ClaudeBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

/** One block of a message's content: text the model said, or a tool it wants to call. */
@Serializable(with = ClaudeBlockSerializer::class)
sealed interface ClaudeBlock {

    @Serializable
    data class Text(val text: String, val type: String = "text") : ClaudeBlock

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement,
        val type: String = "tool_use",
    ) : ClaudeBlock

    @Serializable
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
        val type: String = "tool_result",
    ) : ClaudeBlock
}

/** Discriminates on the `type` field Anthropic puts on every content block. */
private object ClaudeBlockSerializer : JsonContentPolymorphicSerializer<ClaudeBlock>(ClaudeBlock::class) {
    override fun selectDeserializer(element: JsonElement) =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "tool_use" -> ClaudeBlock.ToolUse.serializer()
            "tool_result" -> ClaudeBlock.ToolResult.serializer()
            else -> ClaudeBlock.Text.serializer()
        }
}

/** The error body Anthropic sends on a non-2xx response. */
@Serializable
data class ClaudeErrorEnvelope(val error: ClaudeErrorDetail = ClaudeErrorDetail())

@Serializable
data class ClaudeErrorDetail(val type: String = "", val message: String = "")

/** A one-block user/assistant text turn — the common case. */
fun textMessage(role: String, text: String) =
    ClaudeMessage(role = role, content = listOf(ClaudeBlock.Text(text)))

/** Convenience for reading a [ClaudeResponse] as plain assistant text. */
fun ClaudeResponse.text(): String =
    content.filterIsInstance<ClaudeBlock.Text>().joinToString("\n") { it.text }

fun ClaudeResponse.firstToolUse(): ClaudeBlock.ToolUse? =
    content.filterIsInstance<ClaudeBlock.ToolUse>().firstOrNull()
