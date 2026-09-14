FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencies first, so a prompt or code edit does not re-resolve the whole tree.
COPY pyproject.toml ./
RUN pip install --no-cache-dir \
      "fastapi>=0.115" "uvicorn[standard]>=0.32" "httpx>=0.27" \
      "pydantic>=2.9" "pydantic-settings>=2.6" "fastmcp>=2.3"

COPY ko_brain ./ko_brain
COPY data ./data

# The cache lives here; mount a volume so it survives a rebuild. Nothing in it is precious.
RUN mkdir -p /data
ENV KO_CACHE_PATH=/data/cache.sqlite3 \
    KO_HOST=0.0.0.0 \
    KO_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=60s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4).status==200 else 1)"

CMD ["python", "-m", "uvicorn", "ko_brain.main:app", "--host", "0.0.0.0", "--port", "8080"]
