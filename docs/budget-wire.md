# Budget sync wire (server 0.6.0)

Same pattern as the gym rows: `POST /v1/sync`, last-write-wins by `updatedAt` (epoch millis),
deletions both ways. One currency; amounts are integer cents.

## Push (`push.*`) and pull (response) -- same shapes

`expenses: [ExpenseWire]`

| field | type | notes |
|---|---|---|
| `remoteId` | string | phone-assigned id |
| `amountCents` | int | minor units |
| `category` | string | server's own tools lower-case it; phone should send lower-case |
| `note` | string? | |
| `spentOn` | string | ISO date `YYYY-MM-DD` |
| `deleted` | bool | tombstone: Claude deleted it, drop it locally. Phone-side deletes just disappear from `present` |
| `updatedAt` | int | epoch millis |

`budgetLimits: [BudgetLimitWire]` (key is the category, one per category)

| field | type | notes |
|---|---|---|
| `category` | string | the key; server lower-cases and trims it on push |
| `monthlyCents` | int | |
| `deleted` | bool | true = limit removed (Claude's `set_budget_limit(..., 0)`) |
| `updatedAt` | int | epoch millis; last write wins |

## Present lists (deletions made on the phone)

`present.expenses: [remoteId]` and `present.budgetLimits: [category]` (lower-case). Optional:
omit (null) and nothing is pruned. Rows the server has that were last written at or before
`lastSyncedAt` and are missing from the list are deleted; `lastSyncedAt == 0` prunes nothing.

## Pull

`expenses` and `budgetLimits` in the response carry every row (including `deleted: true`
tombstones) with `updatedAt > lastSyncedAt`.

## MCP tools

`add_expense(amount, category, note, spent_on=today)`, `list_expenses(month?, category?)`,
`update_expense(remote_id, ...)`, `delete_expense(remote_id)`, `set_budget_limit(category,
monthly_amount)` (0 removes), `get_budget_summary(month=current)`.

## Upgrade

Tables `expenses` and `budget_limits` are created with `CREATE TABLE IF NOT EXISTS` at startup, so an
existing database upgrades in place with no data loss.
