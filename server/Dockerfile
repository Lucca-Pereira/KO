FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencies first, so a code edit does not re-resolve the whole tree.
COPY pyproject.toml ./
RUN pip install --no-cache-dir \
      "fastapi>=0.115" "uvicorn[standard]>=0.32" "pydantic>=2.9" "pydantic-settings>=2.6" \
      "fastmcp>=2.3"

COPY ko_sync ./ko_sync

# The sync database lives here; mount a volume so it survives a rebuild.
RUN mkdir -p /data
ENV KO_DB_PATH=/data/ko-sync.sqlite3 \
    KO_HOST=0.0.0.0 \
    KO_PORT=8090

EXPOSE 8090

HEALTHCHECK --interval=60s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=4).status==200 else 1)"

CMD ["python", "-m", "uvicorn", "ko_sync.main:app", "--host", "0.0.0.0", "--port", "8090"]
