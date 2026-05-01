# FireSky Now Forecast Algorithm

## Goal

Predict same-day vivid sunrise and sunset potential for North America. The score is not a guarantee of observed sky color; it is a short-range probability estimate built from weather-model variables that are physically connected to twilight color.

## Research Takeaways

1. Cloud cover and humidity are empirically useful predictors. A small Stanford study using sunset photo labels found cloud cover and humidity were the strongest individual weather predictors among the tested variables, while visibility and wind were weaker but still informative.
2. High and mid clouds are the main "screen" for vivid color. Low clouds and near-100% total overcast often block the low-angle light path instead of reflecting it.
3. A clear low horizon matters. Sunset and sunrise color depends on whether low-angle sunlight can reach the cloud field. For sunset this is mainly westward; for sunrise it is mainly eastward.
4. Aerosols are not simply good or bad. Aerosol optical depth changes twilight color through Rayleigh/Mie scattering, but too much PM, dust, smoke, or haze lowers visibility and can grey out the scene.
5. Short-term model quality matters. Same-day North America forecasting should prefer rapidly updating, high-resolution model inputs where available, especially HRRR in the United States.

## Current Model

The algorithm is a transparent weighted model, not a trained black box:

- `cloudScreen`: high cloud, mid cloud, total cloud, low-cloud penalty, regional cloud texture.
- `horizonOpening`: low cloud, total cloud, precipitation, and visibility in the east/west sunlight corridor.
- `colorChemistry`: aerosol optical depth, PM2.5/AQI, humidity, vapor pressure deficit, wind, instability.
- `sunAccess`: direct radiation, direct normal irradiance, sunshine duration, and diffuse radiation for sunset; horizon/visibility proxy for sunrise.
- `blockersClearance`: precipitation, low-cloud obstruction, visibility, and horizon opening.

The final probability is weighted toward `cloudScreen` and `blockersClearance`, then adjusted by aerosol/humidity chemistry, sun access, and regional texture. Quality is a stricter score that rewards vivid cloud screen plus clean light transmission.

## Calibration Plan

High accuracy requires validation data. The next serious step is to store forecast snapshots before sunrise/sunset and compare them with observed labels:

- User labels: miss, weak, good, vivid.
- Optional photo-based labels: dominant red/orange/pink saturation in sky region.
- Verification metrics: AUC, Brier score, reliability curve, false-positive rate for "vivid".
- Calibration method: logistic regression or isotonic calibration over the transparent score components.

Until this dataset exists, the app should describe scores as evidence-weighted predictions with confidence, not as guaranteed truth.

## Sources

- Open-Meteo Weather Forecast API: https://open-meteo.com/en/docs
- Open-Meteo GFS & HRRR API: https://open-meteo.com/en/docs/gfs-api
- Open-Meteo Air Quality API: https://open-meteo.com/en/docs/air-quality-api
- NOAA HRRR overview: https://rapidrefresh.noaa.gov/hrrr/
- SunsetWx model notes: https://sunsetwx.com/about-the-model/
- Detecting and Predicting Beautiful Sunsets: https://cs.stanford.edu/~emmap1/sunset_paper.pdf
- Colour and aerosol optical depth study: https://cp.copernicus.org/articles/18/2345/2022/cp-18-2345-2022.html
- Twilight aerosol optical depth study: https://www.sciencedirect.com/science/article/abs/pii/S0021850218302593
