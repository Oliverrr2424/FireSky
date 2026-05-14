# FireSky ML Dataset and Model

## Data Built

The merged training table is:

`data_sources/modeling/firesky_training_dataset.csv`

It contains 3272 labeled sunrise/sunset events:

- Stanford sunset labels: 1791 city-day sunset samples from 10 US cities.
- Shanghai sunsetbot ERA-5 reanalysis: 1109 sunrise/sunset samples with positive and negative labels.
- Shanghai sunsetbot public records: 372 case records, included only as low-weight supplemental samples because the table is selection-biased toward visible events.

The primary training subset has 2900 rows and a realistic rare-positive distribution:

- Negative: 2480
- Positive: 420

## Label Definition

The binary label is `label_good`.

- Stanford: uses `Good Sunset` directly.
- Shanghai reanalysis: `小到中烧`, `中到大烧`, and `大烧` are positive; lower levels are negative.
- Shanghai public records: same threshold, but these rows have `is_selection_biased=true`.

## Feature Enrichment

Historical features are fetched from Open-Meteo archive and air-quality APIs, using the same family of variables that the app already requests at forecast time.

For each event, the builder extracts event-time, pre-event, near-event, and post-event weather windows around the local sunrise or sunset time:

- Cloud cover: total, low, mid, high.
- Moisture and temperature: humidity, dew point, vapor pressure deficit, temperature.
- Light path proxies: radiation, sunshine duration, low-cloud obstruction, mid/high cloud screen.
- Air quality: PM2.5, PM10, ozone, nitrogen dioxide, sulfur dioxide, carbon monoxide, aerosol optical depth, dust.
- Location and season: latitude, longitude, event type, seasonal sine/cosine.

Known leakage columns are kept in the CSV for analysis but excluded from training:

- Social post count.
- Observed cloud and color labels.
- Existing sunsetbot prediction.
- Source id.
- Case URLs and human-facing quality strings.

## Model Output

The selected benchmark model is a random forest:

`data_sources/modeling/firesky_model.joblib`

A deploy-friendly logistic model is also exported:

`data_sources/modeling/firesky_logistic_model.json`

The logistic JSON contains imputer medians, scaler values, one-hot categories, coefficients, and intercept, so it can be ported into the Cloudflare worker or frontend without Python.

## Validation

Primary validation excludes the selection-biased Shanghai public-record rows.

Random stratified validation:

- Random forest AUC: 0.864
- Average precision: 0.517
- Brier score: 0.107
- Best-F1 threshold: 0.46
- Best F1 for positive class: 0.557

Held-out city validation:

- Random forest AUC: 0.823
- Average precision: 0.421
- Brier score: 0.113
- Best-F1 threshold: 0.29
- Best F1 for positive class: 0.495

The held-out city result is the more honest estimate for new locations. It says the model has useful signal, but local calibration will still matter.

## Most Useful Feature Families

Top random-forest signals are dominated by:

- Low-cloud cover before and near the event.
- High-cloud cover before and after the event.
- Mid-cloud cover before the event.
- Seasonality.
- The derived cloud-screen and light-path scores.

This matches the physical expectation: good color needs a reflective mid/high cloud screen without too much low-cloud blockage.

## Rebuild Commands

```powershell
python scripts\build_training_dataset.py --sleep 0.15
python scripts\train_firesky_model.py
```

