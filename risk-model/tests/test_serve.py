from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from riskmodel.features import FEATURE_NAMES
from riskmodel.serve.app import create_app

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models" / "model.txt"
META = ROOT / "models" / "metadata.json"


@pytest.fixture(scope="module")
def client() -> TestClient:
    if not MODEL.exists():
        pytest.skip("committed model artifact missing")
    return TestClient(create_app(MODEL, META))


def test_health_and_model_info(client: TestClient) -> None:
    health = client.get("/healthz")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"

    info = client.get("/model-info")
    assert info.status_code == 200
    assert info.json()["feature_names"] == list(FEATURE_NAMES)


def test_score_bounds_and_factors(client: TestClient) -> None:
    response = client.post(
        "/score",
        json={
            "amount_cents": 150_000,
            "customer_email": "burst@example.com",
            "merchant_category": "retail",
            "card_country": "GB",
            "merchant_country": "US",
            "timestamp_epoch_ms": 1_700_010_000_000,
            "velocity_last_hour": 10,
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert 0.0 <= body["score"] <= 1.0
    assert body["model_version"].startswith("ml-v")
    assert body["contributing_factors"]
    assert all(f.startswith("ml_") for f in body["contributing_factors"])


def test_score_validation_error(client: TestClient) -> None:
    response = client.post("/score", json={"customer_email": "x"})
    assert response.status_code == 422


def test_skew_tripwire_feature_list() -> None:
    import json

    meta = json.loads(META.read_text(encoding="utf-8"))
    assert tuple(meta["feature_names"]) == FEATURE_NAMES
