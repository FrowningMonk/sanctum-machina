package app.sanctum.machina.core.registry

/**
 * Reason classifier for [ModelRegistry.resetConversation] / [DefaultModelRegistry] diagnostics.
 *
 * Each call site picks one — the parameter has no default (Decision 1) so the compiler forces an
 * explicit choice and prevents silent regressions when new reset paths are added. The chosen
 * value is emitted verbatim into the `inference-reset` `ErrorLog` channel as `reason=<NAME>`,
 * which is the primary diagnostic signal for Bug 1 (dirty KV-cache between chats) and adjacent
 * conversation-state issues.
 */
enum class ResetReason {
  CHAT_SWITCH,
  DRAFT_COMMIT,
  LIGHT_OVERRIDE,
  SYSTEM_PROMPT,
  HEAVY,
  USER,

  /**
   * Quick / Draft chat entry: warm `Conversation` was created by
   * [WarmupCoordinator] before any per-model DataStore overrides loaded into
   * `model.configValues`, so its `SamplerConfig` and `systemInstruction`
   * reflect allowlist defaults only. `ChatViewModel.bootstrapChatModelId`
   * fires this reset after `applyEffectiveConfigToModel` so the recreated
   * Conversation picks up the merged values before the user's first turn
   * (post-Phase-3.6 fix; symmetric to [CHAT_SWITCH] in Persistent identity).
   */
  QUICK_BOOTSTRAP,
}
