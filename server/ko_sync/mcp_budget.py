"""The budget side of the MCP surface: log spending, set monthly limits, see where the month stands.

Same pattern as the gym tools: rows carry `updatedAt` (last-write-wins) and a `deleted` tombstone
the phone applies on its next pull. Amounts come in as decimals and are stored as integer cents,
in a single currency (no conversion). Categories are case-insensitive and kept lower-cased.
"""

from __future__ import annotations

from calendar import monthrange
from datetime import date as Date
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

from fastmcp.exceptions import ToolError

from . import store
from .config import today
from .mcp_server import DELETE, READ, WRITE, mcp


def _cents(amount: float | str, *, allow_zero: bool = False) -> int:
    try:
        value = Decimal(str(amount))
    except InvalidOperation as e:
        raise ToolError(f"amount must be a number, got {amount!r}") from e
    cents = int((value * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    if cents < 0 or (cents == 0 and not allow_zero):
        raise ToolError(f"amount must be positive, got {amount!r}")
    return cents


def _money(cents: int) -> float:
    return cents / 100


def _category(value: str) -> str:
    category = value.strip().lower()
    if not category:
        raise ToolError("category must not be empty")
    return category


def _date(value: str | None) -> str:
    if value is None:
        return today()
    try:
        return Date.fromisoformat(value).isoformat()
    except ValueError as e:
        raise ToolError(f"spent_on must be an ISO date like 2026-09-29, got {value!r}") from e


def _month_range(month: str | None) -> tuple[str, str, str]:
    month = month or today()[:7]
    try:
        first = Date.fromisoformat(f"{month}-01")
    except ValueError as e:
        raise ToolError(f"month must look like 2026-09, got {month!r}") from e
    last = first.replace(day=monthrange(first.year, first.month)[1])
    return month, first.isoformat(), last.isoformat()


def _out(expense: dict) -> dict:
    return {
        "remoteId": expense["remoteId"],
        "amount": _money(expense["amountCents"]),
        "category": expense["category"],
        "note": expense["note"],
        "spentOn": expense["spentOn"],
    }


def _live_expense(remote_id: str) -> dict:
    expense = store.gym_by_id("expenses", remote_id)
    if expense is None or expense["deleted"]:
        raise ToolError(f"No expense {remote_id!r}. list_expenses shows the current ones.")
    return expense


def summarize(expenses: list[dict], limits: list[dict]) -> dict:
    """Per category spent / limit / remaining, plus a total. `remaining` is None with no limit."""
    spent: dict[str, int] = {}
    for e in expenses:
        key = e["category"].strip().lower()
        spent[key] = spent.get(key, 0) + e["amountCents"]
    limit_by = {limit["category"]: limit["monthlyCents"] for limit in limits}
    categories = []
    for key in sorted(set(spent) | set(limit_by)):
        s, lim = spent.get(key, 0), limit_by.get(key)
        categories.append(
            {
                "category": key,
                "spent": _money(s),
                "limit": _money(lim) if lim is not None else None,
                "remaining": _money(lim - s) if lim is not None else None,
            }
        )
    total_spent = sum(spent.values())
    total_limit = sum(limit_by.values()) if limit_by else None
    return {
        "categories": categories,
        "total": {
            "spent": _money(total_spent),
            "limit": _money(total_limit) if total_limit is not None else None,
            "remaining": _money(total_limit - total_spent) if total_limit is not None else None,
        },
    }


@mcp.tool(annotations=WRITE)
async def add_expense(
    amount: float, category: str, note: str | None = None, spent_on: str | None = None
) -> dict:
    """Record money spent, e.g. "spent 12.50 on lunch".

    Args:
        amount: Amount as a decimal number in the user's single currency, e.g. 12.5.
        category: Spending category, e.g. groceries, eating out, transport.
        note: Optional free text.
        spent_on: ISO date. Omit for today.
    """
    expense = store.upsert_gym(
        "expenses",
        {
            "remoteId": store.new_id(),
            "amountCents": _cents(amount),
            "category": _category(category),
            "note": note,
            "spentOn": _date(spent_on),
            "deleted": False,
            "updatedAt": store.now_millis(),
        },
    )
    return _out(expense)


@mcp.tool(annotations=READ)
async def list_expenses(month: str | None = None, category: str | None = None) -> list[dict]:
    """Expenses for a month, oldest first.

    Args:
        month: YYYY-MM. Omit for the current month.
        category: Only this category.
    """
    _, start, end = _month_range(month)
    cat = _category(category) if category else None
    return [_out(e) for e in store.expenses_between(start, end, cat)]


@mcp.tool(annotations=WRITE)
async def update_expense(
    remote_id: str,
    amount: float | None = None,
    category: str | None = None,
    note: str | None = None,
    spent_on: str | None = None,
) -> dict:
    """Correct an expense. Only the fields you pass change.

    Args:
        remote_id: The expense's remoteId, from list_expenses.
        amount: New amount as a decimal.
        category: New category.
        note: New note.
        spent_on: New ISO date.
    """
    expense = _live_expense(remote_id)
    changes = {
        "amountCents": _cents(amount) if amount is not None else None,
        "category": _category(category) if category is not None else None,
        "note": note,
        "spentOn": _date(spent_on) if spent_on is not None else None,
    }
    updated = {**expense, **{k: v for k, v in changes.items() if v is not None}}
    return _out(store.upsert_gym("expenses", {**updated, "updatedAt": store.newer_than(expense)}))


@mcp.tool(annotations=DELETE)
async def delete_expense(remote_id: str) -> dict:
    """Remove an expense (it disappears from the phone on its next sync).

    Args:
        remote_id: The expense's remoteId, from list_expenses.
    """
    expense = _live_expense(remote_id)
    store.upsert_gym(
        "expenses", {**expense, "deleted": True, "updatedAt": store.newer_than(expense)}
    )
    return {"deleted": remote_id, "amount": _money(expense["amountCents"])}


@mcp.tool(annotations=WRITE)
async def set_budget_limit(category: str, monthly_amount: float) -> dict:
    """Set the monthly limit for a category. An amount of 0 removes the limit.

    Args:
        category: Spending category.
        monthly_amount: Monthly limit as a decimal in the user's currency.
    """
    key = _category(category)
    cents = _cents(monthly_amount, allow_zero=True)
    existing = store.gym_by_id("budget_limits", key)
    store.upsert_gym(
        "budget_limits",
        {
            "category": key,
            "monthlyCents": cents,
            "deleted": cents == 0,
            "updatedAt": store.newer_than(existing),
        },
    )
    return {"category": key, "monthly": _money(cents) if cents else None}


@mcp.tool(annotations=READ)
async def get_budget_summary(month: str | None = None) -> dict:
    """Where a month stands: per category spent, limit and remaining, plus a total.

    Args:
        month: YYYY-MM. Omit for the current month.
    """
    label, start, end = _month_range(month)
    return {"month": label, **summarize(store.expenses_between(start, end), store.live_limits())}
