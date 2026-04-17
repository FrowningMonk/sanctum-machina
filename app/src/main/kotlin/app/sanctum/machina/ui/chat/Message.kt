package app.sanctum.machina.ui.chat

enum class MessageRole { USER, ASSISTANT }

data class Message(
    val role: MessageRole,
    val text: String,
    val streaming: Boolean = false,
    val interrupted: Boolean = false,
    val footer: String? = null,
    /**
     * Reasoning channel captured from ResultListener's partialThinkingResult
     * (D9, AC-14). Null when the model doesn't support thinking or the user
     * has `enableThinking = false`. Empty-string means the stream started but
     * has emitted no thinking yet — still rendered so the UI surfaces the
     * collapsible affordance while the user waits.
     */
    val thinkingText: String? = null,
    /**
     * Attachments the user sent with this message (AC-26, D28). Rendered in
     * the user bubble by `MessageBubble` so history stays consistent with the
     * content actually dispatched to the model. Empty for assistant messages.
     */
    val attachments: List<Attachment> = emptyList(),
)
