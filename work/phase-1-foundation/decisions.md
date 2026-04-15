# Decisions Log: phase-1-foundation

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Git init + .gitignore

**Status:** Done
**Commit:** b1e8747
**Agent:** main agent
**Summary:** Initialized git repo in `C:\AI-WORK\PhoneWrap\`; created `.gitignore` (IDE, Gradle, Android, secrets, logs, gallery-source, Claude local settings) and `.gitattributes` (LF baseline, CRLF override for `.bat`/`.cmd`, binary markers for APK/AAB/DEX/keystore/ML formats); committed on branch `phase/1-foundation` with annotated tag `master` as the immutable zero-state marker per patterns.md § Git Workflow.
**Deviations:**
- Renamed the initial unborn branch to `phase/1-foundation` via `git symbolic-ref HEAD` *before* the first commit, so `master` exists only as an annotated tag and never as a branch. This deviates from the literal hint order in the task ("commit → tag master → branch phase/1-foundation → checkout") but better matches patterns.md's stated intent that "`master` is a tag, not a branch". Avoids ref-name ambiguity between branch and tag.
- Commit scope expanded beyond `.gitignore` + `.gitattributes` to include pre-existing `.claude/`, `CLAUDE.md`, and `work/` directories so the AC-level requirement "exactly 1 commit AND clean `git status`" could be satisfied simultaneously. Commit message broadened accordingly. `.claude/settings.local.json` was detected and added to `.gitignore` before staging so it did not land in history.
- Round-1 reviewer fixes (Android build artefacts, PKCS#12 signing bundles, Android/ML binary markers) were folded into the initial commit via `git commit --amend`; the `master` tag was re-anchored at the new SHA. This keeps the `git log --oneline = 1 commit` acceptance criterion true, instead of leaving a separate `fix:` commit.
- Tech-spec has no per-task checkbox list (only TAC-N acceptance criteria), so the "- [ ] Task N → - [x] Task N" step from the do-task workflow was a no-op.
- AC-2 ("`git log --oneline` shows exactly 1 commit with conventional message") is satisfied by the initial task commit `b1e8747`; the subsequent do-task completion commit (status + decisions + gitignore narrowing for methodology logs) is a methodology marker, not part of the task's infra deliverable.
- `.gitignore` narrowed after reviews completed: removed blanket `logs/` rule so `work/*/logs/` (methodology logs — reviewer JSON reports, QA reports) are tracked. `*.log` still catches runtime log files anywhere. Applied in the completion commit.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: OK (3 minor hardening suggestions adopted) → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- infrastructure-reviewer: findings (all minor/nit, Android artefact & binary-marker gaps) → [logs/working/task-1/infrastructure-reviewer-1.json](logs/working/task-1/infrastructure-reviewer-1.json)

*Round 2 (after amend):*
- infrastructure-reviewer: OK → [logs/working/task-1/infrastructure-reviewer-2.json](logs/working/task-1/infrastructure-reviewer-2.json)

**Verification:**
- `git log --oneline` → 1 commit (`b1e8747 chore: initialize repository with .gitignore, .gitattributes, and project knowledge`)
- `git tag` → contains `master`; `git rev-parse master^{commit}` == `HEAD`
- `git branch --show-current` → `phase/1-foundation`
- `git status` → clean
- `git check-ignore` over 10 canonical paths (gallery-source/, .idea/, build/, local.properties, .env, fake.keystore, fake.jks, release-keys/, gradle.properties.local, app.log) → all matched by expected rules
- Extended ignore spot-check (cert.p12, cert.pfx, keystore.properties, signing.properties, app.apk, app.aab, classes.dex, .kotlin/, app/release/, app/debug/, hs_err_pid123.log) → all matched
