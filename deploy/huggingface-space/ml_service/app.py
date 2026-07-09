from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .features import build_features_for_event

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_PATH = ROOT / "data_sources" / "modeling_v2" / "firesky_v2_model.joblib"
MODEL_VERSION = "firesky-v2"
REPORT_METRICS = {
    "oofRocAuc": 0.8830795968022246,
    "oofAveragePrecision": 0.5189726904882964,
}


class ForecastRequest(BaseModel):
    latitude: float = Field(..., ge=-90, le=90)
    longitude: float = Field(..., ge=-180, le=180)
    weather: dict[str, Any]
    air: dict[str, Any] | None = None
    events: list[str] = Field(default_factory=lambda: ["sunrise", "sunset"])


def _model_path() -> Path:
    return Path(os.environ.get("FIRESKY_MODEL_PATH", DEFAULT_MODEL_PATH)).resolve()


@lru_cache(maxsize=1)
def load_artifact() -> dict[str, Any]:
    path = _model_path()
    if not path.exists():
        raise FileNotFoundError(f"Model artifact not found: {path}")
    return joblib.load(path)


def _clip01(value: float) -> float:
    return float(np.clip(value, 0.0, 1.0))


def _empirical_cdf(value: float, reference: list[float] | np.ndarray | None) -> float | None:
    if reference is None:
        return None
    arr = np.asarray(reference, dtype=float)
    if arr.size == 0 or not np.isfinite(value):
        return None
    return float(np.searchsorted(arr, value, side="right") / arr.size)


def _clean_number(value: float | int | None) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if np.isfinite(number) else None


def _level(score: float, thresholds: dict[str, float]) -> str:
    if score >= 0.85:
        return "excellent"
    if score >= thresholds["balanced"]:
        return "high"
    if score >= thresholds["recall90"]:
        return "watch"
    if score >= 0.42:
        return "possible"
    if score >= 0.24:
        return "weak"
    return "low"


def _confidence(score: float, threshold: float, coverage: float) -> float:
    margin = abs(score - threshold)
    return float(np.clip(48 + coverage * 28 + min(18, margin * 55), 35, 92))


def predict_event(artifact: dict[str, Any], request: ForecastRequest, event: str) -> dict[str, Any]:
    features = artifact["features"]
    raw_features = build_features_for_event(
        weather=request.weather,
        air=request.air,
        latitude=request.latitude,
        longitude=request.longitude,
        event=event,
    )
    row = {feature: raw_features.get(feature, np.nan) for feature in features}
    X = pd.DataFrame([row], columns=features).astype(float)

    lgb = float(artifact["classifier_lgbm"].predict_proba(X)[:, 1][0])
    ordinal_raw = float(artifact["ordinal_regressor_lgbm"].predict(X)[0])
    ordinal = _clip01(ordinal_raw / 5.0)
    xgb = float(artifact["classifier_xgb"].predict_proba(X)[:, 1][0])

    weights = artifact.get("blend_weights_lgb_ord_xgb", (0.3, 0.4, 0.3))
    raw_blend = _clip01(weights[0] * lgb + weights[1] * ordinal + weights[2] * xgb)
    calibration = artifact.get("score_calibration") or {}
    component_refs = calibration.get("components") or {}
    lgb_rank = _empirical_cdf(lgb, component_refs.get("lightgbm"))
    ordinal_rank = _empirical_cdf(ordinal_raw, component_refs.get("ordinal_raw"))
    xgb_rank = _empirical_cdf(xgb, component_refs.get("xgboost"))
    has_calibration = None not in (lgb_rank, ordinal_rank, xgb_rank)
    score = _clip01(
        weights[0] * lgb_rank + weights[1] * ordinal_rank + weights[2] * xgb_rank
        if has_calibration
        else raw_blend
    )
    thresholds = {
        "recall90": float(artifact.get("threshold_recall90", 0.63)),
        "balanced": float(artifact.get("threshold_balanced", 0.72)),
        "bestMinAccuracyRecall": float(artifact.get("threshold_best_min_ar", 0.72)),
    }
    finite_count = int(np.isfinite(X.to_numpy(dtype=float)).sum())
    coverage = finite_count / max(len(features), 1)

    return {
        "event": event,
        "score": round(score * 100, 1),
        "probability": round(score * 100, 1),
        "rawProbability": raw_blend,
        "calibratedProbability": score,
        "level": _level(score, thresholds),
        "confidence": round(_confidence(score, thresholds["balanced"], coverage), 1),
        "featureCoverage": round(coverage, 3),
        "calibration": {
            "method": calibration.get("method", "raw_blend_no_calibration"),
            "reference": calibration.get("reference"),
            "referenceSize": calibration.get("reference_size"),
            "applied": has_calibration,
        },
        "components": {
            "lightgbm": _clean_number(lgb),
            "ordinalLightgbm": _clean_number(ordinal),
            "ordinalRaw": _clean_number(ordinal_raw),
            "xgboost": _clean_number(xgb),
            "lightgbmRank": _clean_number(lgb_rank),
            "ordinalRank": _clean_number(ordinal_rank),
            "xgboostRank": _clean_number(xgb_rank),
            "rawBlend": _clean_number(raw_blend),
        },
        "thresholds": {key: round(value * 100, 1) for key, value in thresholds.items()},
    }


app = FastAPI(title="FireSky v2 inference", version="0.1.0")


@app.get("/health")
def health() -> dict[str, Any]:
    path = _model_path()
    return {
        "ok": path.exists(),
        "modelVersion": MODEL_VERSION,
        "modelPath": str(path),
    }


@app.post("/predict")
def predict(request: ForecastRequest) -> dict[str, Any]:
    try:
        artifact = load_artifact()
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    events = [event for event in request.events if event in {"sunrise", "sunset"}]
    if not events:
        raise HTTPException(status_code=400, detail="events must include sunrise and/or sunset")

    try:
        scores = {event: predict_event(artifact, request, event) for event in events}
    except Exception as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    return {
        "status": "ok",
        "modelVersion": MODEL_VERSION,
        "calibration": artifact.get("score_calibration", {}).get("method", "raw_blend_no_calibration"),
        "note": "Uses calibrated component ranks so online scores match the rank-blend scale used by v2 validation thresholds.",
        "metrics": REPORT_METRICS,
        "scores": scores,
    }
