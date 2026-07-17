"""Train LR baseline + LightGBM, calibrate, evaluate, write artifacts."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split

from riskmodel.datagen.generator import GeneratorConfig, dataset_hash, generate_dataset
from riskmodel.features import FEATURE_NAMES, TransactionFeatures, build_features

ROOT = Path(__file__).resolve().parents[3]
MODELS_DIR = ROOT / "models"
REPORTS_DIR = ROOT / "reports"

MODEL_SEMVER = "1.0.0"

# Deliberate small grid — documented, not AutoML hand-waving.
LGBM_GRID: tuple[dict[str, Any], ...] = (
    {"num_leaves": 15, "learning_rate": 0.05, "min_child_samples": 40, "max_depth": 4},
    {"num_leaves": 31, "learning_rate": 0.05, "min_child_samples": 20, "max_depth": 6},
    {"num_leaves": 63, "learning_rate": 0.03, "min_child_samples": 15, "max_depth": 7},
)


@dataclass(frozen=True, slots=True)
class TrainConfig:
    rows: int = 20_000
    seed: int = 42
    smoke: bool = False


def _rows_to_matrix(frame: pd.DataFrame) -> tuple[pd.DataFrame, np.ndarray]:
    vectors: list[list[float]] = []
    for _, row in frame.iterrows():
        feats = build_features(
            TransactionFeatures(
                amount_cents=int(row["amount_cents"]),
                customer_email=str(row["customer_email"]),
                velocity_last_hour=int(row["velocity_last_hour"]),
                merchant_category=str(row["merchant_category"]),
                card_country=str(row["card_country"]),
                merchant_country=str(row["merchant_country"]),
                timestamp_epoch_ms=int(row["timestamp_epoch_ms"]),
            )
        )
        vectors.append([feats[name] for name in FEATURE_NAMES])
    x = pd.DataFrame(vectors, columns=list(FEATURE_NAMES))
    y = frame["is_fraud"].to_numpy(dtype=np.int32)
    return x, y


def _threshold_metrics(y_true: np.ndarray, proba: np.ndarray, threshold: float) -> dict[str, float]:
    pred = (proba >= threshold).astype(int)
    alert_rate = float(pred.mean())
    if pred.sum() == 0:
        precision = 0.0
        recall = 0.0
    else:
        precision = float(precision_score(y_true, pred, zero_division=0))
        recall = float(recall_score(y_true, pred, zero_division=0))
    return {
        "threshold": threshold,
        "precision": precision,
        "recall": recall,
        "alert_rate": alert_rate,
    }


def _archetype_recall(
    frame: pd.DataFrame, proba: np.ndarray, threshold: float = 0.30
) -> dict[str, float]:
    out: dict[str, float] = {}
    pred = proba >= threshold
    for archetype in frame["archetype"].unique():
        if archetype == "legit":
            continue
        mask = frame["archetype"] == archetype
        fraud_mask = mask & (frame["is_fraud"] == 1)
        if int(fraud_mask.sum()) == 0:
            continue
        out[str(archetype)] = float(pred[fraud_mask.to_numpy()].mean())
    return out


def _select_lgbm(
    x_train: pd.DataFrame,
    y_train: np.ndarray,
    x_val: pd.DataFrame,
    y_val: np.ndarray,
    seed: int,
    smoke: bool,
) -> tuple[lgb.LGBMClassifier, dict[str, Any], float]:
    best_model: lgb.LGBMClassifier | None = None
    best_params: dict[str, Any] = {}
    best_pr_auc = -1.0
    grid = LGBM_GRID[:1] if smoke else LGBM_GRID
    n_estimators = 40 if smoke else 400
    n_pos = max(int(y_train.sum()), 1)
    n_neg = max(int(len(y_train) - n_pos), 1)
    scale_pos_weight = n_neg / n_pos
    for params in grid:
        model = lgb.LGBMClassifier(
            objective="binary",
            n_estimators=n_estimators,
            subsample=0.9,
            colsample_bytree=0.9,
            scale_pos_weight=scale_pos_weight,
            random_state=seed,
            verbosity=-1,
            **params,
        )
        model.fit(
            x_train,
            y_train,
            eval_set=[(x_val, y_val)],
            eval_metric="average_precision",
            callbacks=[lgb.early_stopping(40, verbose=False)] if not smoke else None,
        )
        proba = model.predict_proba(x_val)[:, 1]
        pr_auc = float(average_precision_score(y_val, proba))
        if pr_auc > best_pr_auc:
            best_pr_auc = pr_auc
            best_model = model
            best_params = {**params, "scale_pos_weight": scale_pos_weight}
    assert best_model is not None
    return best_model, best_params, best_pr_auc


def train(config: TrainConfig | None = None) -> dict[str, Any]:
    cfg = config or TrainConfig()
    rows = 800 if cfg.smoke else cfg.rows
    gen = GeneratorConfig(rows=rows, seed=cfg.seed)
    frame = generate_dataset(gen)
    digest = dataset_hash(frame)

    train_df, temp_df = train_test_split(
        frame, test_size=0.3, random_state=cfg.seed, stratify=frame["is_fraud"]
    )
    val_df, test_df = train_test_split(
        temp_df, test_size=0.5, random_state=cfg.seed, stratify=temp_df["is_fraud"]
    )

    x_train, y_train = _rows_to_matrix(train_df)
    x_val, y_val = _rows_to_matrix(val_df)
    x_test, y_test = _rows_to_matrix(test_df)

    # Linear baseline on non-interaction features only — the point of the GBT
    # comparison is learning conjunctions the linear model cannot.
    linear_cols = [c for c in FEATURE_NAMES if "_x_" not in c]
    lr = LogisticRegression(max_iter=500, class_weight="balanced", random_state=cfg.seed)
    lr.fit(x_train[linear_cols], y_train)
    lr_proba = lr.predict_proba(x_test[linear_cols])[:, 1]
    lr_metrics = {
        "roc_auc": float(roc_auc_score(y_test, lr_proba)),
        "pr_auc": float(average_precision_score(y_test, lr_proba)),
        "features": linear_cols,
    }

    booster, best_params, val_pr_auc = _select_lgbm(
        x_train, y_train, x_val, y_val, cfg.seed, cfg.smoke
    )

    # Isotonic calibration on held-out val raw scores so Java 0.30 / 0.70 thresholds
    # land on usable probabilities (native LGBM scores sat in a narrow low band).
    raw_val = booster.predict_proba(x_val)[:, 1]
    isotonic = IsotonicRegression(out_of_bounds="clip")
    isotonic.fit(raw_val, y_val)
    proba = isotonic.predict(booster.predict_proba(x_test)[:, 1])

    metrics = {
        "roc_auc": float(roc_auc_score(y_test, proba)),
        "pr_auc": float(average_precision_score(y_test, proba)),
        "at_0.30": _threshold_metrics(y_test, proba, 0.30),
        "at_0.70": _threshold_metrics(y_test, proba, 0.70),
        "per_archetype_recall_at_0.30": _archetype_recall(test_df.reset_index(drop=True), proba),
        "lr_baseline": lr_metrics,
        "val_pr_auc_uncalibrated": val_pr_auc,
    }

    importances = sorted(
        zip(FEATURE_NAMES, booster.feature_importances_.tolist(), strict=True),
        key=lambda item: item[1],
        reverse=True,
    )[:15]

    model_version = f"ml-v{MODEL_SEMVER}+{digest[:8]}"
    metadata: dict[str, Any] = {
        "model_version": model_version,
        "dataset_hash": digest,
        "row_count": len(frame),
        "seed": cfg.seed,
        "params": best_params,
        "calibration_method": "isotonic",
        "training_timestamp": datetime.now(UTC).isoformat(),
        "feature_names": list(FEATURE_NAMES),
        "metrics": metrics,
        "top_feature_importances": [
            {"feature": name, "importance": importance} for name, importance in importances
        ],
        "smoke": cfg.smoke,
    }

    if not cfg.smoke:
        MODELS_DIR.mkdir(parents=True, exist_ok=True)
        REPORTS_DIR.mkdir(parents=True, exist_ok=True)
        model_path = MODELS_DIR / "model.txt"
        booster.booster_.save_model(str(model_path))
        x_thr = getattr(isotonic, "X_thresholds_", None)
        y_thr = getattr(isotonic, "y_thresholds_", None)
        if x_thr is None or y_thr is None:
            x_thr = np.asarray(isotonic.f_, dtype=float)
            y_thr = np.asarray(isotonic.y_, dtype=float)
        metadata["isotonic_x"] = np.asarray(x_thr, dtype=float).tolist()
        metadata["isotonic_y"] = np.asarray(y_thr, dtype=float).tolist()

        # Score distribution reference for drift (PSI).
        hist, bin_edges = np.histogram(proba, bins=10, range=(0.0, 1.0), density=True)
        metadata["score_distribution"] = {
            "density": hist.tolist(),
            "bin_edges": bin_edges.tolist(),
        }

        (MODELS_DIR / "metadata.json").write_text(
            json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
        )
        _write_report(metadata, lr_metrics)

    return metadata


def _write_report(metadata: dict[str, Any], lr_metrics: dict[str, Any]) -> None:
    m = metadata["metrics"]
    lines = [
        "# Fraud model evaluation (synthetic data)",
        "",
        "> **Honesty:** trained on synthetic generator patterns, not real-world fraud.",
        "",
        f"- model_version: `{metadata['model_version']}`",
        f"- dataset_hash: `{metadata['dataset_hash'][:16]}…`",
        f"- rows: {metadata['row_count']}",
        f"- seed: {metadata['seed']}",
        f"- calibration: {metadata['calibration_method']}",
        "",
        "## Headline metrics (test set)",
        "",
        "| Model | ROC-AUC | PR-AUC |",
        "| --- | ---: | ---: |",
        f"| LightGBM (calibrated) | {m['roc_auc']:.4f} | {m['pr_auc']:.4f} |",
        (
            f"| Logistic regression baseline | {lr_metrics['roc_auc']:.4f} | "
            f"{lr_metrics['pr_auc']:.4f} |"
        ),
        "",
        "## Threshold operating points (Java BLOCK≥0.70 / REVIEW≥0.30)",
        "",
        "| Threshold | Precision | Recall | Alert rate |",
        "| ---: | ---: | ---: | ---: |",
        (
            f"| 0.30 | {m['at_0.30']['precision']:.4f} | {m['at_0.30']['recall']:.4f} | "
            f"{m['at_0.30']['alert_rate']:.4f} |"
        ),
        (
            f"| 0.70 | {m['at_0.70']['precision']:.4f} | {m['at_0.70']['recall']:.4f} | "
            f"{m['at_0.70']['alert_rate']:.4f} |"
        ),
        "",
        "## Per-archetype recall @ 0.30",
        "",
    ]
    for name, recall in sorted(m["per_archetype_recall_at_0.30"].items()):
        lines.append(f"- `{name}`: {recall:.3f}")
    lines.extend(["", "## Top feature importances", ""])
    for item in metadata["top_feature_importances"]:
        lines.append(f"- `{item['feature']}`: {item['importance']}")
    lines.append("")
    (REPORTS_DIR / "evaluation.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=20_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    meta = train(TrainConfig(rows=args.rows, seed=args.seed, smoke=args.smoke))
    print(json.dumps({"model_version": meta["model_version"], "pr_auc": meta["metrics"]["pr_auc"]}))


if __name__ == "__main__":
    main()
