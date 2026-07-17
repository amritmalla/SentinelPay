#!/usr/bin/env python3
"""Compute PSI between training score distribution and a live histogram.

Usage:
  uv run python scripts/drift-check.py --live-density 0.1,0.2,...
  (or scrape riskmodel_score_value buckets from Prometheus and pass densities)
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def psi(expected: list[float], actual: list[float], eps: float = 1e-6) -> float:
    if len(expected) != len(actual):
        raise ValueError("histogram lengths must match")
    total = 0.0
    for e, a in zip(expected, actual, strict=True):
        e_i = max(e, eps)
        a_i = max(a, eps)
        total += (a_i - e_i) * math.log(a_i / e_i)
    return total


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--metadata",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "models" / "metadata.json",
    )
    parser.add_argument(
        "--live-density",
        type=str,
        required=True,
        help="Comma-separated live score densities (same bins as metadata)",
    )
    args = parser.parse_args()
    meta = json.loads(args.metadata.read_text(encoding="utf-8"))
    expected = [float(x) for x in meta["score_distribution"]["density"]]
    actual = [float(x) for x in args.live_density.split(",")]
    value = psi(expected, actual)
    print(f"psi={value:.6f}")
    if value >= 0.25:
        print("drift=significant")
    elif value >= 0.1:
        print("drift=moderate")
    else:
        print("drift=low")


if __name__ == "__main__":
    main()
