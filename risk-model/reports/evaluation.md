# Fraud model evaluation (synthetic data)

> **Honesty:** trained on synthetic generator patterns, not real-world fraud.

- model_version: `ml-v1.0.0+2a2a534d`
- dataset_hash: `2a2a534d296749e9…`
- rows: 20000
- seed: 42
- calibration: isotonic

## Headline metrics (test set)

| Model | ROC-AUC | PR-AUC |
| --- | ---: | ---: |
| LightGBM (calibrated) | 0.8584 | 0.6946 |
| Logistic regression baseline | 0.8513 | 0.6790 |

## Threshold operating points (Java BLOCK≥0.70 / REVIEW≥0.30)

| Threshold | Precision | Recall | Alert rate |
| ---: | ---: | ---: | ---: |
| 0.30 | 0.8875 | 0.6636 | 0.0267 |
| 0.70 | 0.9839 | 0.5701 | 0.0207 |

## Per-archetype recall @ 0.30

- `amount_outlier`: 0.842
- `card_testing`: 1.000
- `geo_mismatch`: 0.857
- `night_owl`: 1.000
- `velocity_burst`: 1.000

## Top feature importances

- `hour_sin`: 5
- `hour_cos`: 4
- `log_amount_cents`: 3
- `velocity_last_hour`: 3
- `geo_x_log_amount`: 2
- `cat_travel`: 2
- `geo_mismatch`: 1
- `disposable_email`: 1
- `cat_food`: 1
- `is_night`: 0
- `hour_known`: 0
- `geo_known`: 0
- `domain_frequency`: 0
- `night_x_velocity`: 0
- `disposable_x_velocity`: 0
