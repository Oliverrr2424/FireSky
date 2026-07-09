---
title: FireSky V2 Inference
emoji: 🔥
colorFrom: red
colorTo: purple
sdk: docker
app_port: 8080
pinned: false
---

# FireSky V2 Inference

FastAPI inference service for the FireSky v2 model.

- `GET /health`
- `POST /predict`

Cloudflare Pages calls `POST /predict` through the `ML_FORECAST_URL`
environment variable.

The returned `score` is a calibrated FireSky chance. Raw LightGBM/XGBoost
probabilities are converted to empirical component ranks before blending so the
online score matches the v2 validation threshold scale.
