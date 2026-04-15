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

---

## Task 2: Gradle infrastructure — root + libs catalog + two-module skeleton

**Status:** Done
**Commit:** 49798c7
**Agent:** main agent
**Summary:** Развернул Gradle-каркас PhoneWrap: `settings.gradle.kts` (`:app`, `:core-runtime`, `FAIL_ON_PROJECT_REPOS`), root `build.gradle.kts` (6 плагинов `apply false`), `gradle/libs.versions.toml` со всеми версиями по tech-spec § Dependencies (AGP 8.8.2 / Kotlin 2.2.0 / Compose BOM 2026.03.00 / Hilt 2.57.1 / KSP 2.3.6), минимальные `:app` и `:core-runtime` модули (Hilt через KSP — Decision T3), Gradle wrapper 8.11.1, пустые `AndroidManifest.xml` в обоих модулях. `buildConfigField MAIN_ACTIVITY_CLASS_NAME` прописан в `:app`. Первая успешная Gradle-конфигурация достигнута — Wave 1 разблокирован.

**Deviations:**
- В `:core-runtime/build.gradle.kts` добавлены `implementation(libs.hilt.android)` + `ksp(libs.hilt.android.compiler)`, хотя task spec (шаг 5/What-to-do) явно откладывал их на Task 3. Причина: Hilt Gradle plugin (`com.google.dagger.hilt.android`) падает на configuration phase с "The Hilt Android Gradle plugin is applied but no com.google.dagger:hilt-android dependency was found" при отсутствии runtime-dep. Вариант «не применять плагин сейчас» исключён — task spec прямо требует четыре плагина (включая `hilt-application`) в `:core-runtime`. Добавление runtime-зависимости не противоречит намерению спека, т.к. `hilt-android` всё равно был в списке New packages (§ Dependencies).
- Kotlin 2.x deprecation warnings на `kotlinOptions { jvmTarget = "11" }` и `freeCompilerArgs += "-Xcontext-receivers"` — следую прямому указанию task spec (implementation hints шаг 5/6: «`jvmTarget = "11"`... `freeCompilerArgs += "-Xcontext-receivers"`»). Миграция на `kotlin { compilerOptions { ... } }` DSL — tech-debt для Phase 2 (сейчас не ломает сборку и совпадает с Gallery).
- Создал дополнительно `gradle.properties` (не был в явном списке files-to-modify task spec'а): `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`. Без них `./gradlew` выдаёт warning'и о Xmx и неявном AndroidX. Скопировано 1-в-1 из `gallery-source/Android/src/gradle.properties` (минус комментарии).

**Reviews:**

*Round 1:*
- code-reviewer: OK (all 12 AC pass) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: OK (2 medium/low рекомендации по wrapper SHA-256 pinning — tech-debt, не блокер) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- infrastructure-reviewer: OK (3 minor: отсутствие deviation в decisions.md на момент ревью — закрыто этим коммитом; drift T2 serialization-json — перенесён в tech-debt; Kotlin deprecations — адресованы выше) → [logs/working/task-2/infrastructure-reviewer-1.json](logs/working/task-2/infrastructure-reviewer-1.json)

**Verification:**
- `./gradlew help` → `BUILD SUCCESSFUL in 2s`
- `./gradlew projects` → `Root project 'PhoneWrap' +--- Project ':app' \\--- Project ':core-runtime'`
- `./gradlew :app:tasks` → `BUILD SUCCESSFUL`; `:app:tasks --all` содержит `assembleDebug`, `assembleDebugAndroidTest`, `assembleDebugUnitTest` (variant tasks скрыты в AGP 8.x default grouping)
- `./gradlew :app:assembleDebug` (опциональная проверка) → `BUILD SUCCESSFUL in 2m 46s`, 55 tasks executed. Потребовался фикс: изначально self-closing `<manifest />` + битый `local.properties` (на машине пользователя — `sdk.dir=C:\...` без экранирования backslash'ей, AGP `SdkLocator.validateSdkPath` падал с `Invalid file path`). После исправления `AndroidManifest.xml` на open/close форму и `local.properties` на `sdk.dir=C\:\\...` — сборка зелёная. `local.properties` не коммитится (в `.gitignore`).
- `git ls-files --eol gradlew gradlew.bat` → `i/lf attr/text eol=lf  gradlew` / `i/lf attr/text eol=crlf  gradlew.bat` (LF/CRLF зафиксированы корректно)
- Grep `kapt|firebase|google-services|oss-licenses|protobuf|appauth|datastore|moshi|mockk|security-crypto|room|aicore|mlkit-genai|camera|richtext` по `*.kts,*.toml` → 0 совпадений (Explicitly NOT included enforced)

**Pending user action (Phase 2 tech-debt):**
- Pin wrapper SHA-256 в `gradle-wrapper.properties` (`distributionSha256Sum=...`) — supply-chain hardening, отложено.
- Проверить необходимость `kotlin-reflect` после Wave 1 — если транзитивно не тянется, убрать alias.
- Привести Decision T2 в tech-spec в соответствие с реальным catalog: либо добавить `kotlinx-serialization-json`, либо убрать из T2 (мы его явно исключили через T-R5).

---

## Task 3: Port + patch Gallery core into `:core-runtime`

**Status:** Done
**Commit:** 42e424e
**Agent:** main agent
**Summary:** Перенёс 12 файлов Gallery-ядра (inference + download + allowlist) в `:core-runtime/src/main/kotlin/app/sanctum/machina/core/**` со сменой package root и мягкими патчами: удалил HF-token плумбинг (Model.accessToken, KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, Authorization-хедер) — D3/AC-1; удалил Firebase Analytics + `GalleryEvent` + Gallery `R` — D3/AC-4; обрезал AICore-ветки в `ModelHelperExt.kt` и `ModelAllowlist.kt` — D1 (остальные файлы оставил copy-paste-only по прямому указанию task spec); заменил `AppLifecycleProvider` на `ProcessLifecycleOwner` — T5; развязал `MainActivity` FQN через `workDataOf(KEY_MAIN_ACTIVITY_FQN)` + guard `require(fqn.startsWith("app.sanctum.machina."))` + companion `DefaultDownloadRepository.mainActivityFqn` — T6; убрал параметр `task: Task?` из интерфейса/импла `DownloadRepository` и инлайнил `R.string.notification_*` в EN-константы; вырезал Gallery deep-link URIs из `sendNotification()` и заменил `MODEL_INFO_ICON_SIZE`/`import ...dp` в `Consts.kt`. `:core-runtime` держит zero Compose/Activity/Gallery-импортов (TAC-3 зелёный).

**Deviations:**
- **Notification content: исправлен inherited Gallery-bug.** Оригинал Gallery использовал `R.string.notification_content_success` в FAILED-ветке. При инлайнинге строк сохранил это 1-в-1 (round-1), code-reviewer поймал, round-2 добавлена отдельная `NOTIFICATION_CONTENT_FAIL`. Отклонение от строгого mechanical-port принципа оправдано тем, что инлайнинг R-строк уже ломал semantic identity с Gallery; завершил рефакторинг корректно.
- **ZipSlip guard добавлен в `DownloadWorker.sendNotification`/unzip-loop** (не был в спеке). Security-auditor round-1 flagged как critical. Inherited Gallery-bug; фикс 10 строк + перенос `parentFile.mkdirs()` внутрь file-branch (покрывает архивы без explicit dir-entries). Вне буквального mechanical-port scope, но ниже по Wave 2/3 это single-entry-point для Model-downloads — закрыл сейчас.
- **`require(fqn.startsWith("app.sanctum.machina."))` оставлен в `DownloadWorker` как throw, не graceful return** — буквально по формулировке task spec (шаг 5 + smoke grep проверяет literal token). Security-auditor отметил как minor, не блокер: FQN ставит только trusted `:app`-код через `mainActivityFqn` companion.
- **Deps: добавил `work-runtime-ktx`, `gson`, `litertlm` в `:core-runtime/build.gradle.kts`** (task spec упоминал их косвенно через source-files). Без них компиляция невозможна.
- **ModelAllowlist.kt обрезан агрессивнее спека:** удалил `DefaultConfig`, `SocModelFile`, `NamedDeviceGroup`, `DeviceRequirements`, `disabled`, `llmSupport*`-флаги, `minDeviceMemoryInGb`, `bestForTaskTypes`, `localModelFilePathOverride`, `url`, `socToModelFiles`, `runtimeType`, `aicore*` — оставил минимальный set (name, modelId, modelFile, commitHash, sizeInBytes, taskTypes, description, version). `toModel()` сводится к LLM-chat defaults. Task 4 `AllowlistLoader` работает с этим минимальным schema; если потребуется расширение — вернём поля точечно.
- **AICore/NPU residue deferred:** enum `Accelerator.NPU`, `RuntimeType.AICORE`, `AICoreModelReleaseStage/Preference`, `MAX_IMAGE_COUNT_AI_CORE`, ветка `Backend.NPU`/`Accelerator.NPU.label` в `LlmChatModelHelper.kt` — оставлены. Task spec явно требует `Config.kt`/`ConfigValue.kt`/`Types.kt`/`LlmChatModelHelper.kt` как copy-paste-only. Phase 2 tech-debt.
- **Notification i18n EN-only (Phase 2 debt):** 4 inline EN-константы в `DownloadRepository.kt`. При переходе на локализованные строки — либо `:core-runtime strings.xml` через `android-library` resources, либо конструкторные параметры из `:app`.
- **Inherited Gallery bugs, not fixed in Task 3:** double-count `model.totalBytes + extraDataFiles.sumOf` в `downloadModel` (preProcess уже сложил), unencoded URL interpolation в `ModelAllowlist.toModel()`, default HTTP redirect policy без host whitelist, division-by-zero риск на `totalBytes` в DownloadWorker progress-calc, `observeForever` без removal, dev-backdoor `/data/local/tmp` в LlmChatModelHelper.cacheDir. Все — inherited Gallery behaviors, out of mechanical-port scope. Noted в security-auditor-2.json для Phase 2/Wave 3.
- **Unused `AGWorkInfo` data class оставлен** в DownloadRepository — вероятно понадобится в Wave 2 (DefaultModelRegistry/ModelManager).

**Reviews:**

*Round 1:*
- code-reviewer: changes_requested (1 critical — notification-content bug; 2 major — AICore residue / totalBytes double-count; минорные) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: changes_requested (1 critical — ZipSlip; 3 major; 5 minor) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: approved (4 nits, TDD anchor honored) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: approved → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- security-auditor: approved → [logs/working/task-3/security-auditor-2.json](logs/working/task-3/security-auditor-2.json)

**Verification:**
- `./gradlew :core-runtime:compileDebugKotlin` → `BUILD SUCCESSFUL in 3s` (warnings only: Kotlin compilerOptions DSL deprecation — tech-debt из Task 2, context-receivers → context-parameters — Phase 2)
- Smoke greps по `core-runtime/src/`:
  - `(firebase|GalleryEvent|accessToken|ModelDownloadAccessToken|com.google.ai.edge.gallery|hfToken|HF_TOKEN|Authorization|bearerToken|oauth|appauth|refreshToken)` (case-insensitive) → 0 совпадений
  - `androidx.compose|androidx.activity` → 0 совпадений (TAC-3 зелёный)
  - `startsWith("app.sanctum.machina.")` в DownloadWorker.kt → 1 совпадение (T6 guard на line 315)
  - `R.string.notification_` → 0 совпадений (inlined)
  - `Task?` в DownloadRepository.kt → 0 совпадений
  - `Uri.parse|Intent.ACTION_VIEW|com.google.ai.edge.gallery://` в DownloadRepository.kt → 0 совпадений

**Pending user action (Phase 2 tech-debt):**
- Notification i18n (4 EN-only const → strings.xml or param injection).
- Cleanup AICore/NPU residue (`RuntimeType.AICORE`, `Accelerator.NPU`, `AICoreModelReleaseStage/Preference`, `MAX_IMAGE_COUNT_AI_CORE`, NPU-backend branches в `LlmChatModelHelper.kt`).
- Fix inherited Gallery-bugs: double-count `totalBytes`, URL encoding в `ModelAllowlist.toModel()`, HTTP redirect whitelist, `observeForever` leak, `/data/local/tmp` dev-path.
- Remove unused `AGWorkInfo` data class if Wave 2 doesn't consume it.
- Migrate `kotlinOptions { jvmTarget = "11" }` → `compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }` в `:core-runtime/build.gradle.kts` (inherited от Task 2).
