package app.sanctum.machina.ui.chat

/**
 * Phase-3 Decision 2: a single [ChatViewModel] serves three route families
 * (`chat/quick`, `chat/draft`, `chat/{chatId}`). The identity is resolved once
 * from nav args at VM construction and drives every mode-specific branch:
 * Room-backed message flow (Persistent only), draft→committed transition
 * (Draft only), and the active-model lookup strategy (Quick/Draft read
 * `registry.activeModelName` — the model warmed by [WarmupCoordinator] — while
 * Persistent reads `chat.model_id` from Room).
 */
sealed class ChatIdentity {
  /** `chat/quick` — incognito in-memory chat, purged on process death. */
  object Quick : ChatIdentity()

  /** `chat/draft` — staging area before the first message commits a persistent row. */
  object Draft : ChatIdentity()

  /** `chat/{chatId}` — persistent chat identified by its Room row id. */
  data class Persistent(val id: Long) : ChatIdentity()
}

/**
 * One-shot navigation requests emitted from [ChatViewModel]. Collected by the
 * host composable in [app.sanctum.machina.ui.SanctumApp] (Task 10 wires the
 * collector) rather than pushed directly through a `NavController` reference,
 * since ViewModels outlive config changes but `NavController` does not — giving
 * the VM a handle to the controller would leak its lifetime and is discouraged
 * by the Compose-Navigation guidance.
 */
sealed class ChatNavigationEvent {
  /**
   * Draft→Persistent atomic transition (AC-P7). The host should perform
   * `navController.navigate("chat/$chatId") { popUpTo("chat/draft"){inclusive=true} }`
   * so the draft route is removed from the back stack.
   */
  data class NavigateToPersistent(val chatId: Long) : ChatNavigationEvent()
}
