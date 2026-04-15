package app.sanctum.machina.ui.chat

enum class MessageRole { USER, ASSISTANT }

data class Message(
    val role: MessageRole,
    val text: String,
    val streaming: Boolean = false,
    val interrupted: Boolean = false,
    val footer: String? = null,
)
