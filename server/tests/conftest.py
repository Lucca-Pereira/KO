import pytest

from ko_sync import store
from ko_sync.config import settings


@pytest.fixture(autouse=True)
def _fresh_db(tmp_path, monkeypatch):
    monkeypatch.setenv("KO_DB_PATH", str(tmp_path / "ko-sync.sqlite3"))
    settings.cache_clear()
    store.reset_for_tests()
    yield
    store.reset_for_tests()
    settings.cache_clear()
