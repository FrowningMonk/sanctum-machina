---
name: task-decomposition
description: |
  Decompose approved tech-spec into atomic task files with parallel creation and validation.

  Use when: "разбей на задачи", "декомпозиция", "decompose tech-spec",
  "создай задачи из техспека", "/decompose-tech-spec"
---

# Task Decomposition

Decompose tech-spec Implementation Tasks into individual task files with parallel creation and validation.

**Input:** `work/{feature}/tech-spec.md` (status: approved)
**Output:** `work/{feature}/tasks/*.md` (validated)
**Language:** Task files in English, communication in Russian

## Rules

### R1. `verify: user` requires visible UI delivery in the same task

`verify: [smoke, user]` means the user can open the current build and
validate the feature. This only holds if the task itself commits a UI
element the user can interact with.

Plumbing-only tasks — data-model fields, VM state accumulators, engine
wiring, ConfigKey/allowlist parsing, DataStore plumbing — use
`verify: [smoke]`. Their user-facing validation rolls up into the **last
task of the slice that delivers the UI control exposing the plumbing**.

Symptom to catch: §Verify-user lists steps like "toggle X in settings
sheet" or "enable Y and observe Z" — but the toggle/control doesn't exist
until a later task. The user ends up unable to verify, has to either skip
or hack a temporary default. Decomposition regret.

**Task-creator behaviour:** treat tech-spec's verify hint as a starting
point, not a contract. Re-derive from the task's Files-to-create/modify
list. If the files touch only backend slices (`ViewModel.kt` state,
`:core-runtime/*`, `:core-settings/*`, new ConfigKeys, allowlist parsing)
with no new user-facing composable — downgrade to `verify: [smoke]` and
add a note in §Verify-user: "end-to-end user verification deferred to
task N (UI delivery)".

**Validators behaviour:** `task-validator` / `reality-checker` flag any
task with `verify: [smoke, user]` whose §Verify-user steps reference a
control that the task itself doesn't create and that isn't already in the
codebase at the task's `depends_on` level. Treat as a major finding —
these slip silently and cost real time at user-verify.

## Phase 1: Create Tasks

1. Ask user for feature name if not provided.

2. Read `work/{feature}/tech-spec.md`. Check frontmatter `status: approved`.
   If not approved — tell user: "tech-spec не утверждён. Сначала запусти `/new-tech-spec` и доведи до approved." Stop.

3. Read `work/{feature}/user-spec.md`.

4. Note the task template path: `~/.claude/shared/work-templates/tasks/task.md.template`

5. Read skills/reviewers catalog from [skills-and-reviewers.md](~/.claude/skills/tech-spec-planning/references/skills-and-reviewers.md) — for passing correct skills/reviewers to task-creators.

6. For each task in Implementation Tasks — launch [`task-creator`](~/.claude/agents/task-creator.md) subagent in parallel.
   Pass each task-creator:
   - feature_path, task_number, task_name
   - template_path: `~/.claude/shared/work-templates/tasks/task.md.template`
   - files_to_modify, files_to_read (from tech-spec)
   - depends_on, wave, skills, reviewers, verify (from tech-spec)
   - teammate_name (if specified in tech-spec, optional)
   Each task-creator copies the template to `tasks/{N}.md` first, then edits each section in place. This ensures no sections are skipped.
   Each task-creator applies **R1** (see Rules above) when setting `verify` in frontmatter — downgrade to `[smoke]` for plumbing-only tasks.

7. Confirm each task-creator returned a file path. Skip reading task content — preserve context budget for validation phase.
8. Git commit: `draft(tasks): create {N} tasks from tech-spec for {feature}`

**Checkpoint:**
- [ ] All `tasks/*.md` files created
- [ ] Each task-creator returned file path
- [ ] Draft committed

## Phase 2: Validation (up to 3 iterations)

Tech-spec was already validated by 6 validators. This phase checks only: (1) task-creator correctly expanded tasks by template, (2) no mismatches with real code appeared during detailing.

### Validators

Launch both in parallel:

[`task-validator`](~/.claude/agents/task-validator.md) (sonnet) — Template Compliance + AC/TDD carry-forward:
- Batch: 5 tasks per call
- Pass: feature_path, task_numbers array, batch_number, iteration
- Report: `logs/tasks/template-batch{N}-review.json`

[`reality-checker`](~/.claude/agents/reality-checker.md) (sonnet) — Reality & Adequacy:
- Batch: 3 tasks per call
- Pass: feature_path, task_numbers array, batch_number, iteration
- Report: `logs/tasks/reality-batch{N}-review.json`

### Process

1. Launch both validators in parallel (task-validator in batches of 5, reality-checker in batches of 3).
2. Read JSON reports, collect findings.
3. If issues found — for each task with issues, launch [`task-creator`](~/.claude/agents/task-creator.md) in fix mode:
   - Pass: same inputs as creation + `mode: fix` + `findings` from validators
   - task-creator reads existing task, applies fixes, overwrites file
4. After each validation round, git commit: `chore(tasks): validation round {N} — {summary}`
5. Re-validate fixed tasks (repeat 1-4). Maximum 3 iterations.
6. If problems remain after 3rd iteration — show user: "Вот что осталось — давай решим вместе."

### Cross-Task Integration Check

After individual validation passes, run a final cross-task check:

1. Launch both validators on ALL tasks in a single batch (not split into smaller batches):
   - `task-validator` — focus: shared resource ownership (one owner, consumers depend_on owner), no competing instances in same wave; **R1 enforcement** — for every task with `verify: [smoke, user]`, confirm the task's Files-to-create list actually delivers the UI control that §Verify-user references; flag as major if §Verify-user depends on controls owned by a later task
   - `reality-checker` — focus: duplicate heavy resource init, hidden dependencies, inconsistent approaches across tasks

2. If issues found → launch `task-creator` in fix mode for affected tasks. Re-validate fixed tasks.

3. Max 2 iterations for cross-task check (on top of the 3 individual iterations).

**Checkpoint:**
- [ ] Both validators: status=approved OR user resolved remaining issues
- [ ] Cross-task integration check: no cross-task conflicts

## Phase 3: Present to User

1. Summary: task count, waves, dependencies, validation results (iterations, issues found/fixed).
2. Wait for user approval.
3. Git commit: `chore(tasks): task decomposition approved for {feature}`
4. Suggest next step: `/do-task` for individual tasks.

**Checkpoint:**
- [ ] Summary presented to user
- [ ] User approved task decomposition
- [ ] Approval committed

## Final Check

- [ ] All phases completed (tasks created, validation passed)
- [ ] All tasks match template (frontmatter: status, depends_on, wave, skills, reviewers, teammate_name)
- [ ] Validation: both validators passed or user confirmed remaining issues
