"""Add online score calibration metadata to the FireSky v2 model artifact.

The v2 validation score is a rank-normalized blend of three model components,
not a direct average of raw tree probabilities. This script stores reference
component distributions from the training feature matrix so online inference can
map each raw component onto the same 0..1 rank scale before blending.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data_sources" / "modeling_v2"
MODEL_PATH = OUT / "firesky_v2_model.joblib"
FEATURES_PATH = OUT / "firesky_v2_features.csv"


def _clean_sorted(values: np.ndarray) -> list[float]:
    arr = np.asarray(values, dtype=float)
    arr = arr[np.isfinite(arr)]
    return np.sort(arr).astype(float).tolist()


def _quantiles(values: np.ndarray) -> dict[str, float]:
    arr = np.asarray(values, dtype=float)
    arr = arr[np.isfinite(arr)]
    probs = [0, 0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99, 1]
    return {f"p{int(p * 100):02d}": float(v) for p, v in zip(probs, np.quantile(arr, probs))}


def build_calibration(artifact: dict[str, Any], dataset: pd.DataFrame) -> dict[str, Any]:
    features = artifact["features"]
    X = dataset[features].astype(float)
    source = dataset.get("source", pd.Series(["reference"] * len(dataset))).astype(str)
    reference_mask = source.eq("sunsetbot_reanalysis").to_numpy()
    if not reference_mask.any():
        reference_mask = np.ones(len(dataset), dtype=bool)

    lgb = artifact["classifier_lgbm"].predict_proba(X)[:, 1]
    ordinal_raw = artifact["ordinal_regressor_lgbm"].predict(X)
    xgb = artifact["classifier_xgb"].predict_proba(X)[:, 1]
    ordinal01 = np.clip(ordinal_raw / 5.0, 0.0, 1.0)
    weights = artifact.get("blend_weights_lgb_ord_xgb", (0.3, 0.4, 0.3))
    raw_blend = weights[0] * lgb + weights[1] * ordinal01 + weights[2] * xgb

    return {
        "method": "component_empirical_cdf_v1",
        "reference": "sunsetbot_reanalysis_final_model_components",
        "reference_size": int(reference_mask.sum()),
        "components": {
            "lightgbm": _clean_sorted(lgb[reference_mask]),
            "ordinal_raw": _clean_sorted(ordinal_raw[reference_mask]),
            "xgboost": _clean_sorted(xgb[reference_mask]),
        },
        "diagnostics": {
            "raw_component_quantiles": {
                "lightgbm": _quantiles(lgb[reference_mask]),
                "ordinal_raw": _quantiles(ordinal_raw[reference_mask]),
                "ordinal01": _quantiles(ordinal01[reference_mask]),
                "xgboost": _quantiles(xgb[reference_mask]),
                "raw_blend": _quantiles(raw_blend[reference_mask]),
            },
            "note": (
                "Raw tree outputs are intentionally not displayed as final percent. "
                "Online inference maps raw components to empirical ranks, matching "
                "the rank-normalized blend used to choose validation thresholds."
            ),
        },
    }


def main() -> None:
    artifact = joblib.load(MODEL_PATH)
    dataset = pd.read_csv(FEATURES_PATH)
    artifact["score_calibration"] = build_calibration(artifact, dataset)

    backup = MODEL_PATH.with_suffix(".joblib.pre_calibration_backup")
    if not backup.exists():
        shutil.copy2(MODEL_PATH, backup)
    joblib.dump(artifact, MODEL_PATH)
    print(
        f"Updated {MODEL_PATH} with {artifact['score_calibration']['method']} "
        f"(n={artifact['score_calibration']['reference_size']})"
    )


if __name__ == "__main__":
    main()
