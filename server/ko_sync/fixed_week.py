"""The meal plan is a fixed week (or two), not a calendar: "week 1, Tuesday" rather than a date.

The phone stores a slot in the `date` column every plan entry already has, as a date inside one
reserved fortnight starting Monday 2001-01-01, so neither the table nor the sync wire changed.
This is the same translation the app does in `domain/plan/FixedWeek.kt`; the two must agree.
"""

from datetime import date, timedelta

WEEKS = 2
ANCHOR = date(2001, 1, 1)  # a Monday
FIRST = ANCHOR.isoformat()
LAST = (ANCHOR + timedelta(days=7 * WEEKS - 1)).isoformat()

WEEKDAYS = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")


def slot_date(week: int, weekday: str) -> str:
    """The stored ISO date for `weekday` of `week`. Raises ValueError on a bad week or name."""
    if week not in range(1, WEEKS + 1):
        raise ValueError(f"week must be 1 or {WEEKS}, got {week!r}")
    key = weekday.strip().upper()
    matches = [d for d in WEEKDAYS if d == key or (len(key) >= 3 and d.startswith(key))]
    if len(matches) != 1:
        raise ValueError(f"weekday must be one of {', '.join(WEEKDAYS)}, got {weekday!r}")
    offset = 7 * (week - 1) + WEEKDAYS.index(matches[0])
    return (ANCHOR + timedelta(days=offset)).isoformat()


def week_range(week: int) -> tuple[str, str]:
    """First and last stored date of `week`, inclusive."""
    return slot_date(week, "MONDAY"), slot_date(week, "SUNDAY")


def describe(iso_date: str) -> tuple[int, str] | None:
    """(week, weekday) for a stored slot date; None for a real calendar date left over from
    before the plan became fixed — those are no longer part of the plan."""
    try:
        offset = (date.fromisoformat(iso_date) - ANCHOR).days
    except ValueError:
        return None
    if not 0 <= offset < 7 * WEEKS:
        return None
    return offset // 7 + 1, WEEKDAYS[offset % 7]
