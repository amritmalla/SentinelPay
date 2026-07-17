"""Seeded synthetic transaction generator with fraud archetypes.

Honesty: labels reflect *generator* patterns, not real-world fraud. Archetypes
mirror the demo velocity rule and the ML feature set so evaluation is meaningful
as an engineering exercise.

Archetypes (fraud ~2% base rate before label noise):
- velocity_burst: high velocity_last_hour, same stable email
- card_testing: small amounts + disposable email
- amount_outlier: extreme amount for category
- geo_mismatch: card_country != merchant_country
- night_owl: 02:00–05:00 UTC plus a second weak signal
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

CATEGORIES = ("retail", "travel", "digital", "food", "services")
COUNTRIES = ("US", "GB", "DE", "FR", "CA", "AU", "IN", "BR")
LEGIT_DOMAINS = (
    "gmail.com",
    "yahoo.com",
    "outlook.com",
    "hotmail.com",
    "icloud.com",
    "example.com",
)
DISPOSABLE_DOMAINS = ("mailinator.com", "guerrillamail.com", "tempmail.com", "yopmail.com")

# Mean log-amount (cents) by category for legit traffic.
CATEGORY_LOG_MEAN = {
    "retail": 8.5,
    "travel": 10.5,
    "digital": 7.2,
    "food": 7.8,
    "services": 9.0,
}

FRAUD_RATE = 0.025
LABEL_FLIP_RATE = 0.01


@dataclass(frozen=True, slots=True)
class GeneratorConfig:
    rows: int = 20_000
    seed: int = 42
    fraud_rate: float = FRAUD_RATE
    label_flip_rate: float = LABEL_FLIP_RATE


def dataset_hash(frame: pd.DataFrame) -> str:
    """Stable content hash over sorted columns (excluding the hash itself)."""
    cols = sorted(c for c in frame.columns if c != "dataset_hash")
    payload = frame[cols].to_csv(index=False).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _sample_legit_amount(rng: np.random.Generator, category: str) -> int:
    mean = CATEGORY_LOG_MEAN[category]
    log_amount = rng.normal(mean, 0.7)
    return int(max(100, min(round(math_exp(log_amount)), 5_000_000)))


def math_exp(x: float) -> float:
    return float(np.exp(x))


def _business_hour_ms(rng: np.random.Generator, day_offset: int) -> int:
    # Prefer 08:00–20:00 UTC; occasional night hours for realism.
    if rng.random() < 0.85:
        hour = int(rng.integers(8, 20))
    else:
        hour = int(rng.integers(0, 24))
    minute = int(rng.integers(0, 60))
    base = 1_700_000_000_000  # fixed epoch anchor
    return base + day_offset * 86_400_000 + hour * 3_600_000 + minute * 60_000


def _night_hour_ms(rng: np.random.Generator, day_offset: int) -> int:
    hour = int(rng.integers(2, 5))
    minute = int(rng.integers(0, 60))
    base = 1_700_000_000_000
    return base + day_offset * 86_400_000 + hour * 3_600_000 + minute * 60_000


def _make_legit_row(rng: np.random.Generator, idx: int) -> dict[str, Any]:
    category = CATEGORIES[int(rng.integers(0, len(CATEGORIES)))]
    country = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
    domain = LEGIT_DOMAINS[int(rng.integers(0, len(LEGIT_DOMAINS)))]
    # Sprinkle single weak signals on legit traffic so fraud needs conjunctions.
    velocity = int(rng.integers(0, 3))
    ts = _business_hour_ms(rng, idx % 30)
    card_country = country
    merchant_country = country
    amount = _sample_legit_amount(rng, category)
    roll = rng.random()
    if roll < 0.04:
        velocity = int(rng.integers(6, 12))  # high velocity alone ≠ fraud
    elif roll < 0.07:
        ts = _night_hour_ms(rng, idx % 30)  # night alone ≠ fraud
    elif roll < 0.10:
        other = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
        while other == country:
            other = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
        card_country = other  # geo alone ≠ fraud
    elif roll < 0.12:
        amount = int(rng.integers(400_000, 900_000))  # large alone ≠ fraud
    return {
        "transaction_id": f"txn-{idx:08d}",
        "amount_cents": amount,
        "customer_email": f"user{idx}@{domain}",
        "merchant_category": category,
        "card_country": card_country,
        "merchant_country": merchant_country,
        "timestamp_epoch_ms": ts,
        "velocity_last_hour": velocity,
        "is_fraud": 0,
        "archetype": "legit",
    }


def _inject_fraud(rng: np.random.Generator, idx: int, archetype: str) -> dict[str, Any]:
    country = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
    row = _make_legit_row(rng, idx)
    row["is_fraud"] = 1
    row["archetype"] = archetype

    if archetype == "velocity_burst":
        # Conjunction the linear model underfits: high velocity + mid/high amount.
        row["velocity_last_hour"] = int(rng.integers(8, 18))
        row["customer_email"] = f"burst{idx % 50}@example.com"
        row["amount_cents"] = int(rng.integers(80_000, 250_000))
    elif archetype == "card_testing":
        row["amount_cents"] = int(rng.integers(50, 400))
        domain = DISPOSABLE_DOMAINS[int(rng.integers(0, len(DISPOSABLE_DOMAINS)))]
        row["customer_email"] = f"test{idx}@{domain}"
        row["velocity_last_hour"] = int(rng.integers(4, 12))
    elif archetype == "amount_outlier":
        # Extreme only for low-ticket categories — interaction LR struggles with.
        row["merchant_category"] = "food" if rng.random() < 0.6 else "digital"
        row["amount_cents"] = int(rng.integers(1_200_000, 4_000_000))
    elif archetype == "geo_mismatch":
        other = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
        while other == country:
            other = COUNTRIES[int(rng.integers(0, len(COUNTRIES)))]
        row["card_country"] = country
        row["merchant_country"] = other
        row["amount_cents"] = int(rng.integers(40_000, 180_000))
        row["velocity_last_hour"] = int(rng.integers(2, 6))
    elif archetype == "night_owl":
        row["timestamp_epoch_ms"] = _night_hour_ms(rng, idx % 30)
        # Conjunction: night + elevated amount AND mild velocity.
        row["amount_cents"] = int(rng.integers(250_000, 700_000))
        row["velocity_last_hour"] = int(rng.integers(3, 8))
    else:
        raise ValueError(f"unknown archetype: {archetype}")
    return row


def generate_dataset(config: GeneratorConfig | None = None) -> pd.DataFrame:
    cfg = config or GeneratorConfig()
    rng = np.random.default_rng(cfg.seed)
    n_fraud = max(1, int(round(cfg.rows * cfg.fraud_rate)))
    n_legit = cfg.rows - n_fraud

    archetypes = (
        "velocity_burst",
        "card_testing",
        "amount_outlier",
        "geo_mismatch",
        "night_owl",
    )
    rows: list[dict[str, Any]] = []
    for i in range(n_legit):
        rows.append(_make_legit_row(rng, i))
    for i in range(n_fraud):
        archetype = archetypes[i % len(archetypes)]
        rows.append(_inject_fraud(rng, n_legit + i, archetype))

    frame = pd.DataFrame(rows)
    frame = frame.sample(frac=1.0, random_state=cfg.seed).reset_index(drop=True)

    # Label noise both ways so metrics aren't a fake 1.0 AUC.
    flip_mask = rng.random(len(frame)) < cfg.label_flip_rate
    frame.loc[flip_mask, "is_fraud"] = 1 - frame.loc[flip_mask, "is_fraud"]

    frame.attrs["config"] = asdict(cfg)
    return frame


def write_dataset(path: Path, config: GeneratorConfig | None = None) -> tuple[pd.DataFrame, str]:
    frame = generate_dataset(config)
    digest = dataset_hash(frame)
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_parquet(path, index=False)
    meta = {
        "dataset_hash": digest,
        "rows": len(frame),
        "config": asdict(config or GeneratorConfig()),
        "fraud_rate_observed": float(frame["is_fraud"].mean()),
        "archetype_counts": frame["archetype"].value_counts().to_dict(),
    }
    path.with_suffix(".meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    return frame, digest


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Generate synthetic fraud dataset")
    parser.add_argument("--rows", type=int, default=20_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, default=Path("data/transactions.parquet"))
    args = parser.parse_args()
    frame, digest = write_dataset(args.out, GeneratorConfig(rows=args.rows, seed=args.seed))
    print(f"wrote {len(frame)} rows → {args.out} hash={digest[:8]}")


if __name__ == "__main__":
    main()
