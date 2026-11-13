# Changelog

Upgrade notes per release. Install a new APK over the old one — don't uninstall — unless a
note says otherwise.

## v0.15.0 -- web recipe import, pantry expiry, settings groups, update check

- Settings regrouped into Sync, Data & backup and About. About has a **Check for updates** button (only when pressed) that asks GitHub for the latest release and links to the APK.
- Recipes menu: **Import from web page**. Paste a URL and the app reads the page's schema.org recipe data (ingredients, steps, times, servings) directly, with no AI involved.
- Pantry items can have an optional expiry date (phone-only, not synced; included in backups). Items expiring within 3 days float to a "Use soon" section and show on Today.

## v0.14.0 — recipe view and cook mode

- Recipes have no photo block any more. Ingredients show a bold amount, and a scale row
  (½× to 4×) rescales every amount and the serving count. Tap an ingredient to cross it off.
- Directions: tap a step to mark it as the current one. Times in a step ("6 minutes") are
  highlighted and start a countdown timer; timers stay listed until dismissed and chime when
  done (while the app is open — they are not background alarms).
- **Start cooking**: one big step at a time with previous/next, the ingredient list one tap
  away, the screen kept awake, and an optional read-aloud using the phone's speech engine.
- The calendar and the edit/delete actions moved into the recipe's top bar.
- The recipe list is plain text (no thumbnails), shows how many recipes you have, and can be
  sorted: A to Z, newest first, most cooked, quickest.
- Groceries are grouped by aisle in shop-walking order (produce first, frozen and drinks last)
  with a count per aisle and "N to buy" under the title. Tap a row to tick it; hold it to move
  it to another aisle. Ticking still puts the item back in the pantry.
- Groceries have amounts. Type "2 kg potatoes" and the amount is split off; adding the same item
  again adds the amounts ("2 kg" + "1 kg" = "3 kg"). "Add missing to shopping list" on a recipe
  carries each ingredient's amount, scaled by the recipe's scale. Hold a row to set or change an
  amount. Database v10 (one new nullable column, no data lost); amounts sync to the server and
  Claude's `add_to_shopping_list` accepts them.
- No database migration. Scale, crossed-off items and timers are not saved.

## v0.13.0 — new look, side menu, Today, fixed meal plan

- New dark charcoal and coral look, following the phone's light or dark setting.
- The bottom tabs are replaced by a side menu: Today, Recipes, Groceries, Pantry, Meals, Gym,
  Settings. The app opens on **Today**: the meals planned for this weekday and whether you have
  the ingredients, calories so far, and one line each for groceries, pantry and supplements.
- **The meal plan is a fixed week, not a calendar.** Meals sit on Monday to Sunday with no
  dates; a two-week plan has Week 1 and Week 2, and you choose by hand which one is current.
  "Cooked" ticks clear themselves each Monday; the meals stay.
- Upgrading: meals planned in the current week move to Week 1 and next week's to Week 2. Meals
  on any other date are kept in the database and in backups but no longer shown.
- Moving a planned meal now reaches the sync service (it didn't before).
- Server 0.5.0: needs a rebuild (`docker compose up -d --build`). Claude's plan tools take a
  weekday and week instead of a date, and it can read and switch the current week. An app older
  than 0.13.0 still syncs with the new server, but should be updated.
- No database migration.

## v0.12.0

- Claude can delete kitchen data (recipes, pantry items, shopping items, planned meals).
- The meal plan can show one or two weeks, set from the phone or from a chat.

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
