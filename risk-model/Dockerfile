# Multi-stage, slim, non-root — matches Java service hardening bar.
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS build
WORKDIR /app
COPY pyproject.toml README.md .python-version uv.lock ./
COPY src ./src
COPY models ./models
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12-slim-bookworm
WORKDIR /app
# libgomp1: OpenMP runtime required by LightGBM's native library at import time.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 appuser
COPY --from=build /app/.venv /app/.venv
COPY --from=build /app/src /app/src
COPY --from=build /app/models /app/models
COPY --from=build /app/pyproject.toml /app/README.md /app/
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONPATH=/app/src \
    PYTHONDONTWRITEBYTECODE=1
USER 10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz')"
CMD ["uvicorn", "riskmodel.serve.app:app", "--host", "0.0.0.0", "--port", "8090"]
