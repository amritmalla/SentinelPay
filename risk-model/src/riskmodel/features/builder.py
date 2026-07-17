"""Single source of feature engineering for training and serving (no train/serve skew)."""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass

import numpy as np

# Fixed category vocabulary for one-hot encoding. Unknown / missing → unknown bucket.
CATEGORIES: tuple[str, ...] = (
    "retail",
    "travel",
    "digital",
    "food",
    "services",
    "unknown",
)

DISPOSABLE_DOMAINS: frozenset[str] = frozenset(
    {
        "mailinator.com",
        "guerrillamail.com",
        "tempmail.com",
        "10minutemail.com",
        "yopmail.com",
        "trashmail.com",
    }
)

# Domain frequency prior used as a stable encoding (not recomputed from live traffic).
DOMAIN_FREQUENCY: Mapping[str, float] = {
    "gmail.com": 0.35,
    "yahoo.com": 0.12,
    "outlook.com": 0.10,
    "hotmail.com": 0.08,
    "icloud.com": 0.05,
    "example.com": 0.02,
    "corp.example": 0.01,
}


@dataclass(frozen=True, slots=True)
class TransactionFeatures:
    amount_cents: int
    customer_email: str
    velocity_last_hour: int
    merchant_category: str | None = None
    card_country: str | None = None
    merchant_country: str | None = None
    timestamp_epoch_ms: int | None = None


def _blank_to_none(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    return stripped if stripped else None


def _email_domain(email: str) -> str:
    if not email or "@" not in email:
        return "unknown"
    return email.rsplit("@", 1)[-1].strip().lower() or "unknown"


def _hour_of_day(timestamp_epoch_ms: int | None) -> float | None:
    if timestamp_epoch_ms is None or timestamp_epoch_ms <= 0:
        return None
    # UTC hour; cyclic encoding does not need local TZ for this synthetic model.
    seconds = timestamp_epoch_ms / 1000.0
    return (seconds % 86_400) / 3_600.0


def build_features(txn: TransactionFeatures) -> dict[str, float]:
    """Build the ordered feature map used identically in train and serve."""
    amount = max(int(txn.amount_cents), 0)
    log_amount = math.log1p(amount)

    hour = _hour_of_day(txn.timestamp_epoch_ms)
    if hour is None:
        hour_sin = 0.0
        hour_cos = 0.0
        is_night = 0.0
        hour_known = 0.0
    else:
        radians = 2.0 * math.pi * (hour / 24.0)
        hour_sin = math.sin(radians)
        hour_cos = math.cos(radians)
        is_night = 1.0 if 2.0 <= hour < 5.0 else 0.0
        hour_known = 1.0

    category = _blank_to_none(txn.merchant_category)
    category_key = category.lower() if category else "unknown"
    if category_key not in CATEGORIES:
        category_key = "unknown"

    card = _blank_to_none(txn.card_country)
    merchant = _blank_to_none(txn.merchant_country)
    if card is None or merchant is None:
        geo_mismatch = 0.0
        geo_known = 0.0
    else:
        geo_mismatch = 0.0 if card.upper() == merchant.upper() else 1.0
        geo_known = 1.0

    domain = _email_domain(txn.customer_email)
    disposable = 1.0 if domain in DISPOSABLE_DOMAINS else 0.0
    domain_freq = float(DOMAIN_FREQUENCY.get(domain, 0.005))

    velocity = float(max(int(txn.velocity_last_hour), 0))
    features: dict[str, float] = {
        "log_amount_cents": log_amount,
        "hour_sin": hour_sin,
        "hour_cos": hour_cos,
        "is_night": is_night,
        "hour_known": hour_known,
        "geo_mismatch": geo_mismatch,
        "geo_known": geo_known,
        "disposable_email": disposable,
        "domain_frequency": domain_freq,
        "velocity_last_hour": velocity,
        # Explicit interactions (GBT still learns others; these help ranking vs LR).
        "night_x_velocity": is_night * velocity,
        "geo_x_log_amount": geo_mismatch * log_amount,
        "disposable_x_velocity": disposable * velocity,
    }
    for cat in CATEGORIES:
        features[f"cat_{cat}"] = 1.0 if category_key == cat else 0.0
    return features


FEATURE_NAMES: tuple[str, ...] = tuple(build_features(TransactionFeatures(0, "", 0)).keys())


def feature_vector(txn: TransactionFeatures) -> np.ndarray:
    feats = build_features(txn)
    return np.asarray([feats[name] for name in FEATURE_NAMES], dtype=np.float64)
