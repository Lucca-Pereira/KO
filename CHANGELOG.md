# Changelog

Upgrade notes per release. Install a new APK over the old one — don't uninstall — unless a
note says otherwise.

## v0.11.0 — Claude can run the gym tab

- The food diary, supplement ticks and weigh-ins now sync with the NAS service, so Claude can
  log food (estimating macros), tick off supplements, record weight and measurements, report
  the day against your target, and correct or remove its own gym entries.
- Supplements and targets are sent to the service read-only; they're still set on the phone.
- MCP tools carry read-only/destructive hints, so Claude apps can skip permission prompts for
  reads.
- Database v9: new sync columns on the gym tables. Nothing existing moves.
- Server: needs a rebuild (`docker compose up -d --build`); optional `KO_TIMEZONE`.

## v0.10.1

- Fixes **Sync now** sending the previously saved token on the first tap after changing it.

## v0.10.0 — the Claude connector

- The sync service becomes a claude.ai custom connector (Google sign-in, email allowlist),
  reachable from the Claude app on any device via Tailscale Funnel. The phone syncs over the
  same public HTTPS address, so it no longer needs Tailscale.
- New read tools for Claude (recipe browsing, meal plan, shopping list).
- Deleting on the phone (or ticking off shopping) now removes it from Claude's view too.

## v0.9.2

- Fixes the **Your details** Save button silently doing nothing on incomplete data.

## v0.9.1

- Fixes a crash when syncing with a plain `http://` address.

## v0.9.0

- Optional live sync with a self-hosted service. Opt-in; the migration only adds columns.

## v0.8.1

- The in-app Claude API agent from v0.8.0 is removed again: usage billing wasn't worth it for a
  personal app. Recipes come from asking Claude and importing the file it writes.

## v0.8.0

- A recipe assistant calling the Claude API from inside the app. Superseded by v0.8.1.

## v0.7.0

- Adds the gym side. The migration only creates new tables.

## v0.4.0

- Recipes become a library of their own instead of attachments to a calendar day, and duplicate
  copies of the same meal are merged. Export a backup first, then install over the top.

## v0.1.2

- Builds are signed with one committed key from here on. Coming from v0.1.1 or earlier,
  uninstall once first.
