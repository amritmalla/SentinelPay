"""HTTP scoring service: /score, /healthz, /metrics, /model-info."""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import lightgbm as lgb
import numpy as np
import shap
from fastapi import FastAPI, HTTPException
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field
from starlette.responses import Response

from riskmodel.features import FEATURE_NAMES, TransactionFeatures, build_features

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_MODEL = ROOT / "models" / "model.txt"
DEFAULT_META = ROOT / "models" / "metadata.json"

REQUESTS = Counter("riskmodel_score_requests_total", "Score requests", ["status"])
LATENCY = Histogram(
    "riskmodel_score_latency_seconds",
    "Score latency",
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
)
SCORE_HIST = Histogram(
    "riskmodel_score_value",
    "Calibrated score distribution",
    buckets=tuple(i / 10 for i in range(11)),
)

FACTOR_MAP: dict[str, str] = {
    "velocity_last_hour": "ml_velocity_last_hour_high",
    "night_x_velocity": "ml_night_velocity_conjunction",
    "geo_mismatch": "ml_geo_mismatch",
    "geo_x_log_amount": "ml_geo_amount_conjunction",
    "log_amount_cents": "ml_amount_outlier_for_category",
    "disposable_email": "ml_disposable_email",
    "disposable_x_velocity": "ml_card_testing_pattern",
    "is_night": "ml_night_activity",
    "domain_frequency": "ml_rare_email_domain",
}


class ScoreRequest(BaseModel):
    transaction_id: str | None = None
    merchant_id: str | None = None
    amount_cents: int = Field(..., ge=0)
    currency: str = "USD"
    customer_email: str = ""
    merchant_category: str | None = None
    card_country: str | None = None
    merchant_country: str | None = None
    timestamp_epoch_ms: int | None = None
    velocity_last_hour: int = Field(0, ge=0)


class ScoreResponse(BaseModel):
    score: float
    contributing_factors: list[str]
    model_version: str


def _apply_isotonic(raw: float, x: list[float], y: list[float]) -> float:
    if not x or not y:
        return float(np.clip(raw, 0.0, 1.0))
    return float(np.clip(np.interp(raw, x, y), 0.0, 1.0))


class ModelBundle:
    def __init__(self, model_path: Path, meta_path: Path) -> None:
        if not model_path.exists() or not meta_path.exists():
            raise FileNotFoundError(f"missing model artifacts under {model_path.parent}")
        self.metadata: dict[str, Any] = json.loads(meta_path.read_text(encoding="utf-8"))
        meta_features = tuple(self.metadata.get("feature_names", []))
        if meta_features != FEATURE_NAMES:
            raise RuntimeError(
                "feature list skew: metadata feature_names != features.FEATURE_NAMES"
            )
        self.booster = lgb.Booster(model_file=str(model_path))
        self.isotonic_x = [float(v) for v in self.metadata.get("isotonic_x", [])]
        self.isotonic_y = [float(v) for v in self.metadata.get("isotonic_y", [])]
        self.model_version = str(self.metadata["model_version"])
        # Background for SHAP: zeros vector is fine for TreeExplainer with LightGBM.
        self.explainer = shap.TreeExplainer(self.booster)

    def score(self, req: ScoreRequest) -> ScoreResponse:
        txn = TransactionFeatures(
            amount_cents=req.amount_cents,
            customer_email=req.customer_email,
            velocity_last_hour=req.velocity_last_hour,
            merchant_category=req.merchant_category,
            card_country=req.card_country,
            merchant_country=req.merchant_country,
            timestamp_epoch_ms=req.timestamp_epoch_ms,
        )
        feats = build_features(txn)
        row = np.asarray([[feats[name] for name in FEATURE_NAMES]], dtype=np.float64)
        raw = float(self.booster.predict(row)[0])
        calibrated = _apply_isotonic(raw, self.isotonic_x, self.isotonic_y)

        shap_values = self.explainer.shap_values(row)
        if isinstance(shap_values, list):
            contrib = np.asarray(shap_values[1][0], dtype=float)
        else:
            contrib = np.asarray(shap_values[0], dtype=float)

        ranked = sorted(
            zip(FEATURE_NAMES, contrib, strict=True),
            key=lambda item: item[1],
            reverse=True,
        )
        factors: list[str] = []
        for name, value in ranked:
            if value <= 0:
                continue
            factors.append(FACTOR_MAP.get(name, f"ml_{name}"))
            if len(factors) >= 3:
                break
        if not factors:
            factors = ["ml_no_strong_positive_factors"]

        return ScoreResponse(
            score=calibrated,
            contributing_factors=factors,
            model_version=self.model_version,
        )


def create_app(
    model_path: Path | None = None,
    meta_path: Path | None = None,
) -> FastAPI:
    app = FastAPI(title="SentinelPay risk-model", version="1.0.0")
    bundle = ModelBundle(model_path or DEFAULT_MODEL, meta_path or DEFAULT_META)
    app.state.bundle = bundle

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "model_version": bundle.model_version}

    @app.get("/model-info")
    def model_info() -> dict[str, Any]:
        return bundle.metadata

    @app.get("/metrics")
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.post("/score", response_model=ScoreResponse)
    def score(req: ScoreRequest) -> ScoreResponse:
        started = time.perf_counter()
        try:
            result = bundle.score(req)
            REQUESTS.labels(status="ok").inc()
            SCORE_HIST.observe(result.score)
            return result
        except Exception as exc:  # noqa: BLE001 — surface as 500 for client fallback
            REQUESTS.labels(status="error").inc()
            raise HTTPException(status_code=500, detail=str(exc)) from exc
        finally:
            LATENCY.observe(time.perf_counter() - started)

    return app


app = create_app()
