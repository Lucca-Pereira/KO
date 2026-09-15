# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew assembleDebug                 # APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest             # all unit tests, incl. Room migrations (Robolectric)
./gradlew testDebugUnitTest --tests "com.lucca.ko.RecipeRepositoryTest"
./gradlew testDebugUnitTest --tests "com.lucca.ko.db.MigrationTest.migrate6To7_validatesAgainstTheEntities"
./gradlew assembleRelease               # signed with the committed app/ko.keystore
./gradlew :app:kspDebugKotlin           # regenerate app/schemas/ after an entity change
```

No lint/format tooling is configured (no ktlint/detekt) — don't invent lint commands.

There is no emulator/device attached by default. To actually run the app: launch an
AVD or plug in a device, `adb install -r app/build/outputs/apk/debug/app-debug.apk`,
`adb shell am start -n com.lucca.ko/.MainActivity`. `adb`/`emulator` live under the SDK
at `sdk.dir` in `local.properties`; on Windows/Git Bash, prefix `adb`/`uiautomator`
calls that take a path (`push`, `pull`, `screencap -p /sdcard/...`) with
`MSYS_NO_PATHCONV=1` or they get mangled into a Windows path.

## Architecture

**Single `:app` module, manual DI.** `AppContainer` (constructed in `KoApp.onCreate`)
is the entire dependency graph — lazy `val`s, no Hilt/Dagger. Every repository takes
DAOs directly (never other repositories) to keep the graph flat; the one exception is
`AgentImportRepository`, which composes several repositories because applying an
import genuinely touches pantry + recipes + plan + shopping in one pass.

**Room is the single source of truth, deliberately.** No `fallbackToDestructiveMigration()`
— a missing migration crashes the app on launch rather than silently wiping data.
Every schema change needs: an entity change in `data/db/Entities.kt`, a hand-written
`MIGRATION_n_n+1` in `data/db/Migrations.kt` (added to `KO_MIGRATIONS`), a bumped
`version` in `KoDatabase`, and a test in `app/src/test/java/com/lucca/ko/db/MigrationTest.kt`
that runs the migration against a seeded historical schema and asserts on the result —
see any existing `migrateNToM_*` test for the pattern (seed a v2 db via the `seedV2()`
fixture, chain migrations with `helper.runMigrationsAndValidate`, assert row survival
*and* schema validity). Schemas are exported to `app/schemas/` by KSP and must be
committed — CI fails the build if they're stale (`kspDebugKotlin` then `git diff`).
`dishes`/`dishId` is the recipe table's real name in SQL (never renamed — SQLite on
minSdk 26 can't rename/drop columns or rewrite a cross-table FK without a full rebuild,
so a cosmetic rename would cost a real migration for no gain).

**Recipes have no in-app AI.** The app makes no calls to any AI provider — this was a
deliberate pivot away from two earlier approaches (a self-hosted NAS/Ollama service,
then briefly an in-app Claude API agent) documented in the README's upgrade notes.
Instead: the user asks Claude for a recipe/pantry update in an ordinary chat, Claude
writes a small JSON file (format documented in README.md under "Ask Claude for
recipes"), and `data/repo/AgentImportRepository.kt` applies it **additively** —
matches existing rows by name/title where relevant, never wipes anything. That's
distinct from `data/BackupRepository.kt`'s full export/import, which *does* wipe and
replace everything (`db.clearAllTables()`), for moving to a new phone.

A NAS-hosted sync service + MCP server (so Claude Desktop can write live instead of
via a manual file) is planned but not yet built — see
`C:\Users\lucca\.claude\plans\i-want-to-start-curried-dragon.md` for the design
(two-column `remoteId`/`syncedAt` sync correlation, idempotent push-then-pull protocol,
the exact pitfalls already found and fixed). `server/` currently has one scaffold file;
treat anything else under it as not-yet-real until that plan is implemented.

**Undo is a snapshot stack, not a diff.** `RevisionRepository` (backed by
`RevisionDao`/`recipe_revisions`) stores a full serialized `RecipeDraft` before any
non-manual-typing change to a recipe (an accepted import, in future a sync pull) —
`undoLastChange` just restores the snapshot wholesale. `domain/recipe/RecipeDiff.kt` is
the *display* layer for "what would change" (used nowhere currently that a chat/agent
UI existed — kept because the import-review pattern may return), matched by name for
ingredients and by position for steps, not a real patch format.

**Navigation**: type-safe routes as `@Serializable` objects/classes in `ui/nav/Routes.kt`,
wired in `ui/nav/KoRoot.kt`'s single `NavHost`. Every screen's top bar is
`ui/common/KoTopBar.kt`, which always renders a Settings gear via `LocalOpenSettings` —
a `CompositionLocal` provided once in `KoRoot` — rather than threading an
`onOpenSettings` callback through every screen signature.

**Package layout**: `data/db/` (entities, DAOs, migrations), `data/repo/` (one
repository per feature area — Pantry/Recipe/MealPlan/Shopping/Nutrition/Body/
Supplement/Revision/AgentImport/Backup), `data/prefs/` (DataStore-backed settings;
`SettingsRepository` holds only one-shot repair flags now — NAS URL/API-key storage
has been added and removed twice this project's life, check before assuming it
exists), `data/remote/` (external HTTP: `OpenFoodFactsClient` for barcode lookups —
the only network call the app makes), `domain/` (pure, unit-tested logic:
ingredient normalization/matching, measure parsing, nutrition math — no Android/Room
imports), `ui/<feature>/` (Compose screen + ViewModel per screen, `koFactory` in
`ui/VmFactory.kt` for boilerplate-free ViewModel construction from `AppContainer`).

**Testing**: Robolectric for anything touching Room or Android context (`@RunWith(RobolectricTestRunner::class)`,
`@Config(sdk = [34])`), real in-memory Room databases rather than mocked DAOs — the
project's own stated reason is that things like `ON DELETE SET NULL`, unique indices,
and cascade behavior *are* the behavior worth testing, and a mocked DAO would only
test the mock. HTTP clients (`OpenFoodFactsClient`) take a `baseUrl` constructor
param so tests can point them at `okhttp3.mockwebserver.MockWebServer` (or a dead
port, per `NutritionRepositoryTest`'s "no network" cases) instead of the real host —
keep that pattern for anything new that makes a network call. CI
(`.github/workflows/ci.yml`) runs unit tests, checks the exported schema is committed,
then builds and signs the release APK, attaching it to `v*` tag releases.
