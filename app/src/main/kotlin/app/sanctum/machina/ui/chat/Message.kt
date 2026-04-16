package app.sanctum.machina.ui.chat

enum class MessageRole { USER, ASSISTANT }

data class Message(
    val role: MessageRole,
    val text: String,
    val streaming: Boolean = false,
    val interrupted: Boolean = false,
    val footer: String? = null,
    /**
     * Attachments the user sent with this message (AC-26, D28). Rendered in
     * the user bubble by `MessageBubble` so history stays consistent with the
     * content actually dispatched to the model. Empty for assistant messages.
     */
    val attachments: List<Attachment> = emptyList(),
)
