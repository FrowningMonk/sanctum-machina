/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.core.data

const val TASK_ID_LLM_CHAT = "llm_chat"

/**
 * Phase 4 Task 1: marker for embedder rows. Drives [RuntimeType] derivation in
 * [AllowedModel.toModel] and is the gate for parsing `defaultRagConfig`.
 */
const val TASK_ID_LLM_EMBEDDING = "llm_embedding"
