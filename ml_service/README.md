# FireSky v2 Inference Service

This service wraps the local `data_sources/modeling_v2/firesky_v2_model.joblib`
artifact behind a small FastAPI API.

## Local Run

```bash
python -m venv .venv
.venv\Scripts\pip install -r ml_service\requirements.txt
.venv\Scripts\uvicorn ml_service.app:app --reload --port 8080
```

Health check:

```bash
curl http://127.0.0.1:8080/health
```

## API

`POST /predict`

```json
{
  "latitude": 37.7749,
  "longitude": -122.4194,
  "weather": {},
  "air": {},
  "events": ["sunrise", "sunset"]
}
```

The Cloudflare Function sends the Open-Meteo weather and air-quality payloads
it already fetches. The service returns `scores.sunrise` and `scores.sunset`.

## Score Calibration

The v2 validation thresholds were selected on a rank-normalized blend of
LightGBM, ordinal LightGBM, and XGBoost components. The service therefore does
not display the raw tree probability as the user-facing percent. It maps each
raw component onto the empirical component distribution stored in the model
artifact, then blends those ranks with the trained weights.

Debug fields:

- `probability` / `score`: calibrated user-facing FireSky chance, 0-100.
- `rawProbability`: uncalibrated raw blend, useful for diagnosing model
  saturation.
- `components.*Rank`: calibrated component ranks used for the displayed score.

## Deploy

The model artifact is intentionally ignored by git. For a one-step local
container deploy, build from this repository root so Docker can copy the local
artifact:

```bash
docker build -f ml_service/Dockerfile -t firesky-v2-inference .
```

For Git-based hosts such as Render or Railway, upload
`firesky_v2_model.joblib` as an external artifact or use Git LFS/private object
storage, then set:

```bash
FIRESKY_MODEL_PATH=/path/to/firesky_v2_model.joblib
```

After deployment, set the Cloudflare Pages environment variable:

```bash
ML_FORECAST_URL=https://your-service.example.com/predict
```

The app will automatically fall back to the existing rule score if this URL is
missing or the ML service returns an error.
