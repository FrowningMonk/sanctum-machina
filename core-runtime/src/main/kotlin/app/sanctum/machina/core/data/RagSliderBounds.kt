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

/**
 * Phase 4 Task 1 (Decision 11): slider bounds for the RAG knobs in `ProjectSettingsScreen`.
 *
 * **Status:** placeholder values from documented sources (EmbeddingGemma model card +
 * PocketSage baseline). Device-bench-day-2 on Honor 200 refines the ranges based on
 * latency × chunkSize × embeddingDim grid measurements — when those land, this file is
 * updated AND the spike report `work/phase-4-projects-rag/logs/spike-day1.md` records the
 * basis for each refined number.
 *
 * Why this lives in `:core-runtime/core/data` rather than `:app/ui/projects`: T9
 * (`ProjectSettingsViewModel`) reads these bounds to drive UI sliders, and T9 lives in
 * `:app`; but consumer scope keeps these grep-able alongside [RagDefaults] which carries
 * the per-model baseline. Pure constants — no Android dependencies, no UI imports.
 *
 * Pairing rule: defaults (per-model) come from [Model.defaultRagConfig], ranges (global UI
 * bounds) come from here. The slider gate `0 <= overlap < chunkSize` is enforced in the
 * ViewModel — these constants describe the SLIDER bounds, not the cross-knob constraint.
 */
object RagSliderBounds {
  /**
   * `chunkSize` in characters of PDF-extracted text. Upper bound 1600 is conservative: even
   * with an aggressive 4-bytes-per-token estimate that lands ≈ 400 tokens, well under the
   * 2048-token max_input of the seq2048 EmbeddingGemma variant. Lower bound 200 below which
   * retrieval becomes too noisy in early PocketSage experiments.
   */
  val chunkSizeRange: IntRange = 200..1600
  const val chunkSizeStep: Int = 100

  /**
   * `chunkOverlap` in characters. Cross-knob constraint `overlap < chunkSize` lives in the
   * ViewModel gate — this range is the slider's standalone domain. Upper bound 400 is the
   * largest sensible overlap for the smallest chunkSize on the slider; in practice the gate
   * narrows it further at runtime.
   */
  val chunkOverlapRange: IntRange = 0..400
  const val chunkOverlapStep: Int = 25

  /**
   * `topK` — number of chunks retrieved per send. 1..10 covers the entire useful range
   * empirically observed in academic RAG literature for fixed-prompt context budgets; PocketSage
   * baseline is 4, fits comfortably mid-range.
   */
  val topKRange: IntRange = 1..10
  const val topKStep: Int = 1

  /**
   * `embeddingDim` from EmbeddingGemma Matryoshka enum. Slider-presented as a discrete
   * picker, not a continuous range — but the bounds make the valid-value set explicit for
   * the ViewModel's parse-and-snap logic.
   */
  val embeddingDimChoices: List<Int> = listOf(128, 256, 512, 768)
}
