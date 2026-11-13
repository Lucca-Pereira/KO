import pytest

from ko_sync import fixed_week


def test_anchor_is_a_monday():
    assert fixed_week.ANCHOR.weekday() == 0


def test_slot_date_and_describe_round_trip():
    for week in (1, 2):
        for weekday in fixed_week.WEEKDAYS:
            assert fixed_week.describe(fixed_week.slot_date(week, weekday)) == (week, weekday)


def test_slot_dates_match_the_app():
    # The same values FixedWeek.kt produces; the phone and server must agree on these.
    assert fixed_week.slot_date(1, "MONDAY") == "2001-01-01"
    assert fixed_week.slot_date(1, "SUNDAY") == "2001-01-07"
    assert fixed_week.slot_date(2, "TUESDAY") == "2001-01-09"
    assert (fixed_week.FIRST, fixed_week.LAST) == ("2001-01-01", "2001-01-14")


def test_weekday_accepts_case_and_unambiguous_prefixes():
    assert fixed_week.slot_date(1, " friday ") == "2001-01-05"
    assert fixed_week.slot_date(1, "Thu") == "2001-01-04"
    for bad in ("T", "S", "Funday", ""):
        with pytest.raises(ValueError):
            fixed_week.slot_date(1, bad)
    with pytest.raises(ValueError):
        fixed_week.slot_date(3, "MONDAY")


def test_describe_rejects_real_dates():
    assert fixed_week.describe("2026-11-13") is None
    assert fixed_week.describe("2001-01-15") is None
    assert fixed_week.describe("not a date") is None
