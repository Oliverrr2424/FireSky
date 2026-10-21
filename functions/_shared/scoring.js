// Shared deterministic rules used by the web forecast and regional history.
function clamp(value, min = 0, max = 100) {
  return Math.max(min, Math.min(max, value));
}

function scoreRange(value, ideal, tolerance, max = 100) {
  if (value == null || Number.isNaN(value)) return 45;
  return clamp(max - (Math.abs(value - ideal) / tolerance) * max);
}

function scoreLowerBetter(value, good, bad) {
  if (value == null || Number.isNaN(value)) return 55;
  return clamp(((bad - value) / (bad - good)) * 100);
}

function scoreHigherBetter(value, good, great) {
  if (value == null || Number.isNaN(value)) return 55;
  return clamp(((value - good) / (great - good)) * 100);
}

function scoreBand(value, outerLow, innerLow, innerHigh, outerHigh) {
  if (value == null || Number.isNaN(value)) return 45;
  if (value <= outerLow || value >= outerHigh) return 0;
  if (value >= innerLow && value <= innerHigh) return 100;
  if (value < innerLow) return clamp(((value - outerLow) / (innerLow - outerLow)) * 100);
  return clamp(((outerHigh - value) / (outerHigh - innerHigh)) * 100);
}

function weightedAverage(pairs) {
  const usable = pairs.filter(([value]) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const totalWeight = usable.reduce((sum, [, weight]) => sum + weight, 0);
  return usable.reduce((sum, [value, weight]) => sum + value * weight, 0) / totalWeight;
}

function weightedProductScore(pairs, floor = 0.03) {
  const usable = pairs.filter(([value]) => value != null && !Number.isNaN(value));
  if (!usable.length) return 0;
  const totalWeight = usable.reduce((sum, [, weight]) => sum + weight, 0);
  const product = usable.reduce((nextProduct, [value, weight]) => {
    const normalized = Math.max(floor, clamp(value) / 100);
    return nextProduct * normalized ** (weight / totalWeight);
  }, 1);
  return clamp(product * 100);
}

function parseLocalDate(value) {
  return value ? new Date(value) : null;
}

function localIsoToUtcDate(value, utcOffsetSeconds = 0) {
  if (!value) return null;
  const [datePart, timePart = '00:00'] = value.split('T');
  const [year, month, day] = datePart.split('-').map(Number);
  const [hour = 0, minute = 0, second = 0] = timePart.split(':').map(Number);
  return new Date(Date.UTC(year, month - 1, day, hour, minute, second) - utcOffsetSeconds * 1000);
}

const RAD = Math.PI / 180;
const DEG = 180 / Math.PI;
const DAY_MS = 86400000;
const J1970 = 2440588;
const J2000 = 2451545;

function toJulian(date) {
  return date.valueOf() / DAY_MS - 0.5 + J1970;
}

function toDays(date) {
  return toJulian(date) - J2000;
}

function rightAscension(l, b) {
  const e = 23.4397 * RAD;
  return Math.atan2(Math.sin(l) * Math.cos(e) - Math.tan(b) * Math.sin(e), Math.cos(l));
}

function declination(l, b) {
  const e = 23.4397 * RAD;
  return Math.asin(Math.sin(b) * Math.cos(e) + Math.cos(b) * Math.sin(e) * Math.sin(l));
}

function solarMeanAnomaly(d) {
  return RAD * (357.5291 + 0.98560028 * d);
}

function eclipticLongitude(m) {
  const c = RAD * (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m));
  const p = RAD * 102.9372;
  return m + c + p + Math.PI;
}

function siderealTime(d, lw) {
  return RAD * (280.16 + 360.9856235 * d) - lw;
}

function sunAltitude(date, latitude, longitude) {
  const lw = -longitude * RAD;
  const phi = latitude * RAD;
  const d = toDays(date);
  const m = solarMeanAnomaly(d);
  const l = eclipticLongitude(m);
  const dec = declination(l, 0);
  const ra = rightAscension(l, 0);
  const h = siderealTime(d, lw) - ra;
  return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h)) / RAD;
}

function sunAzimuth(date, latitude, longitude) {
  const lw = -longitude * RAD;
  const phi = latitude * RAD;
  const d = toDays(date);
  const m = solarMeanAnomaly(d);
  const l = eclipticLongitude(m);
  const dec = declination(l, 0);
  const ra = rightAscension(l, 0);
  const h = siderealTime(d, lw) - ra;
  const azimuthFromSouth = Math.atan2(
    Math.sin(h),
    Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi)
  );
  return (azimuthFromSouth * DEG + 180 + 360) % 360;
}

function findSunAltitudeCrossing(startDate, endDate, targetAltitude, latitude, longitude) {
  const start = startDate.getTime();
  const end = endDate.getTime();
  const steps = 72;
  let previousTime = start;
  let previousValue = sunAltitude(new Date(previousTime), latitude, longitude) - targetAltitude;
  for (let i = 1; i <= steps; i += 1) {
    const nextTime = start + ((end - start) * i) / steps;
    const nextValue = sunAltitude(new Date(nextTime), latitude, longitude) - targetAltitude;
    if (previousValue === 0 || previousValue * nextValue <= 0) {
      let low = previousTime;
      let high = nextTime;
      for (let j = 0; j < 28; j += 1) {
        const mid = (low + high) / 2;
        const midValue = sunAltitude(new Date(mid), latitude, longitude) - targetAltitude;
        if (previousValue * midValue <= 0) {
          high = mid;
        } else {
          low = mid;
          previousValue = midValue;
        }
      }
      return new Date((low + high) / 2);
    }
    previousTime = nextTime;
    previousValue = nextValue;
  }
  return null;
}

function computeBlueHourWindows({ latitude, longitude, sunrise, sunset }) {
  const threeHours = 3 * 60 * 60 * 1000;
  const morningStart = sunrise ? findSunAltitudeCrossing(new Date(sunrise.getTime() - threeHours), sunrise, -6, latitude, longitude) : null;
  const morningEnd = sunrise ? findSunAltitudeCrossing(new Date(sunrise.getTime() - threeHours), sunrise, -4, latitude, longitude) : null;
  const eveningStart = sunset ? findSunAltitudeCrossing(sunset, new Date(sunset.getTime() + threeHours), -4, latitude, longitude) : null;
  const eveningEnd = sunset ? findSunAltitudeCrossing(sunset, new Date(sunset.getTime() + threeHours), -6, latitude, longitude) : null;
  return {
    sunrise: { start: morningStart, end: morningEnd },
    sunset: { start: eveningStart, end: eveningEnd }
  };
}

function findEventAltitudeTime(eventDate, targetAltitude, latitude, longitude, mode) {
  if (!eventDate) return null;
  const oneHour = 60 * 60 * 1000;
  const before = new Date(eventDate.getTime() - 3 * oneHour);
  const after = new Date(eventDate.getTime() + 3 * oneHour);
  return findSunAltitudeCrossing(before, after, targetAltitude, latitude, longitude);
}

function peakColorAltitudeBand(window, air = {}, mode) {
  const highCloud = scoreBand(window?.cloudHigh, 0, 26, 72, 96) / 100;
  const midCloud = scoreBand(window?.cloudMid, 0, 18, 62, 90) / 100;
  const lowCloudClearance = scoreLowerBetter(window?.cloudLow, 14, 72) / 100;
  const totalCloud = scoreBand(window?.cloudTotal, 4, 34, 76, 97) / 100;
  const visibilityKm = (window?.visibility ?? 10000) / 1000;
  const visibility = scoreHigherBetter(visibilityKm, 5, 18) / 100;
  const aerosol = weightedAverage([
    [scoreBand(air?.aerosol_optical_depth ?? 0.1, 0, 0.04, 0.24, 0.65), 0.45],
    [scoreLowerBetter(air?.us_aqi ?? 40, 18, 120), 0.3],
    [scoreBand(air?.pm2_5 ?? 8, 0, 3, 14, 42), 0.25]
  ]) / 100;
  const cloudScreen = weightedAverage([
    [highCloud * 100, 0.46],
    [midCloud * 100, 0.28],
    [totalCloud * 100, 0.18],
    [lowCloudClearance * 100, 0.08]
  ]) / 100;
  const clarity = weightedAverage([
    [visibility * 100, 0.46],
    [lowCloudClearance * 100, 0.26],
    [aerosol * 100, 0.18],
    [scoreLowerBetter(window?.precipProbability ?? 0, 8, 68), 0.1]
  ]) / 100;
  const highCloudAfterglow = highCloud * 1.55 + midCloud * 0.42;
  const hazeCompression = clamp((0.56 - clarity) * 2.2, 0, 1.1);
  const weakScreenCompression = cloudScreen < 0.42 ? 0.75 : 0;
  const blockedHorizonCompression = lowCloudClearance < 0.45 ? 0.65 : 0;
  const deepTwilightAltitude = clamp(
    -3.7 - highCloudAfterglow + hazeCompression + weakScreenCompression,
    -7.2,
    -2.35
  );
  const nearHorizonAltitude = clamp(
    0.75 + midCloud * 0.65 - blockedHorizonCompression - hazeCompression * 0.28,
    -0.45,
    2.1
  );

  return mode === 'sunrise'
    ? { startAltitude: deepTwilightAltitude, endAltitude: nearHorizonAltitude }
    : { startAltitude: nearHorizonAltitude, endAltitude: deepTwilightAltitude };
}

function computeAppearanceWindows({ latitude, longitude, sunrise, sunset, windows, airSnapshot, airSnapshots }) {
  const sunriseBand = peakColorAltitudeBand(windows?.sunrise, airSnapshots?.sunrise ?? airSnapshot, 'sunrise');
  const sunsetBand = peakColorAltitudeBand(windows?.sunset, airSnapshots?.sunset ?? airSnapshot, 'sunset');
  const sunriseStart = findEventAltitudeTime(sunrise, sunriseBand.startAltitude, latitude, longitude, 'sunrise');
  const sunriseEnd = findEventAltitudeTime(sunrise, sunriseBand.endAltitude, latitude, longitude, 'sunrise');
  const sunsetStart = findEventAltitudeTime(sunset, sunsetBand.startAltitude, latitude, longitude, 'sunset');
  const sunsetEnd = findEventAltitudeTime(sunset, sunsetBand.endAltitude, latitude, longitude, 'sunset');
  return {
    sunrise: { start: sunriseStart, end: sunriseEnd, ...sunriseBand },
    sunset: { start: sunsetStart, end: sunsetEnd, ...sunsetBand }
  };
}

function nearestIndex(times, targetDate, utcOffsetSeconds = null) {
  if (!targetDate) return 0;
  let best = 0;
  let bestDiff = Infinity;
  times.forEach((time, index) => {
    const timeDate = utcOffsetSeconds == null ? new Date(time) : localIsoToUtcDate(time, utcOffsetSeconds);
    const diff = Math.abs(timeDate.getTime() - targetDate.getTime());
    if (diff < bestDiff) {
      best = index;
      bestDiff = diff;
    }
  });
  return best;
}

function avg(values) {
  const usable = values.filter((value) => value != null && !Number.isNaN(value));
  if (!usable.length) return null;
  return usable.reduce((sum, value) => sum + value, 0) / usable.length;
}

function valueAt(hourly, key, index) {
  return hourly?.[key]?.[index] ?? null;
}

function pickWindow(hourly, centerDate, mode, utcOffsetSeconds = null) {
  const times = hourly?.time ?? [];
  const center = nearestIndex(times, centerDate, utcOffsetSeconds);
  const indexes = [center - 1, center, center + 1].filter((index) => index >= 0 && index < times.length);
  const cloudTotal = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover', index)));
  const cloudLow = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_low', index)));
  const cloudMid = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_mid', index)));
  const cloudHigh = avg(indexes.map((index) => valueAt(hourly, 'cloud_cover_high', index)));
  const humidity = avg(indexes.map((index) => valueAt(hourly, 'relative_humidity_2m', index)));
  const precipProbability = avg(indexes.map((index) => valueAt(hourly, 'precipitation_probability', index)));
  const precipitation = avg(indexes.map((index) => valueAt(hourly, 'precipitation', index)));
  const visibility = avg(indexes.map((index) => valueAt(hourly, 'visibility', index)));
  const wind = avg(indexes.map((index) => valueAt(hourly, 'wind_speed_10m', index)));
  const gust = avg(indexes.map((index) => valueAt(hourly, 'wind_gusts_10m', index)));
  const cape = avg(indexes.map((index) => valueAt(hourly, 'cape', index)));
  const shortwave = avg(indexes.map((index) => valueAt(hourly, 'shortwave_radiation', index)));
  const direct = avg(indexes.map((index) => valueAt(hourly, 'direct_radiation', index)));
  const diffuse = avg(indexes.map((index) => valueAt(hourly, 'diffuse_radiation', index)));
  const directNormal = avg(indexes.map((index) => valueAt(hourly, 'direct_normal_irradiance', index)));
  const sunshineDuration = avg(indexes.map((index) => valueAt(hourly, 'sunshine_duration', index)));
  const vapourPressureDeficit = avg(indexes.map((index) => valueAt(hourly, 'vapour_pressure_deficit', index)));

  return {
    mode,
    center,
    indexes,
    start: times[indexes[0]] ?? null,
    end: times[indexes[indexes.length - 1]] ?? null,
    cloudTotal,
    cloudLow,
    cloudMid,
    cloudHigh,
    humidity,
    precipProbability,
    precipitation,
    visibility,
    wind,
    gust,
    cape,
    shortwave,
    direct,
    diffuse,
    directNormal,
    sunshineDuration,
    vapourPressureDeficit
  };
}

function computeWindowScore(window, air, mode, context = {}) {
  const aqi = air?.us_aqi ?? null;
  const pm25 = air?.pm2_5 ?? null;
  const aod = air?.aerosol_optical_depth ?? null;
  const dust = air?.dust ?? null;

  const highCloud = scoreBand(window.cloudHigh, 0, 28, 72, 96);
  const midCloud = scoreBand(window.cloudMid, 0, 18, 62, 90);
  const lowCloudPenalty = scoreLowerBetter(window.cloudLow, 12, 72);
  const totalCloud = scoreBand(window.cloudTotal, 4, 34, 76, 97);
  const humidity = scoreBand(window.humidity, mode === 'sunrise' ? 22 : 18, mode === 'sunrise' ? 48 : 34, mode === 'sunrise' ? 82 : 72, 96);
  const rain = scoreLowerBetter((window.precipProbability ?? 0) + (window.precipitation ?? 0) * 24, 5, 72);
  const visibility = scoreHigherBetter((window.visibility ?? 8000) / 1000, 5, 18);
  const aerosol = clamp(
    scoreBand(aod ?? 0.1, 0, 0.04, 0.24, 0.65) * 0.42 +
      scoreBand(pm25 ?? 8, 0, 3, 14, 42) * 0.3 +
      scoreLowerBetter(aqi ?? 40, 18, 120) * 0.2 +
      scoreLowerBetter(dust ?? 0, 5, 95) * 0.05
  );
  const wind = scoreBand(window.wind, 0, 3, 22, 42);
  const instability = scoreLowerBetter(window.cape ?? 0, 80, 1400);
  const vpd = scoreBand(window.vapourPressureDeficit ?? 0.7, 0, 0.2, 1.4, 2.6);
  const directBeam = window.directNormal ?? window.direct;
  const beamPurity = directBeam != null && window.diffuse != null && directBeam > 0
    ? scoreHigherBetter(directBeam / (window.diffuse + 1), 0.45, 3.2)
    : 55;
  const sunAccess = mode === 'sunset'
    ? weightedAverage([
        [scoreHigherBetter(window.directNormal ?? window.direct ?? 0, 45, 430), 0.5],
        [scoreHigherBetter(window.sunshineDuration ?? 0, 240, 2600), 0.25],
        [scoreLowerBetter(window.diffuse ?? 0, 40, 260), 0.25]
      ])
    : weightedAverage([
        [lowCloudPenalty, 0.45],
        [rain, 0.25],
        [visibility, 0.3]
      ]);
  const localTexture = scoreBand(Math.abs((window.cloudHigh ?? 0) - (window.cloudMid ?? 0)) + Math.abs((window.cloudMid ?? 0) - (window.cloudLow ?? 0)) * 0.35, 0, 8, 36, 78);
  const horizonOpening = context.horizonOpening ?? weightedAverage([
    [lowCloudPenalty, 0.48],
    [rain, 0.25],
    [visibility, 0.2],
    [sunAccess, 0.07]
  ]);
  const solarCorridor = context.solarCorridor ?? weightedAverage([
    [horizonOpening, 0.58],
    [lowCloudPenalty, 0.18],
    [rain, 0.14],
    [sunAccess, 0.1]
  ]);
  const regionalTexture = context.regionalTexture ?? localTexture;
  const cloudScreen = weightedAverage([
    [highCloud, 0.36],
    [midCloud, 0.23],
    [totalCloud, 0.18],
    [lowCloudPenalty, 0.14],
    [regionalTexture, 0.09]
  ]);
  const blockersClearance = weightedAverage([
    [horizonOpening, 0.36],
    [rain, 0.27],
    [visibility, 0.19],
    [lowCloudPenalty, 0.18]
  ]);
  const colorChemistry = weightedAverage([
    [aerosol, 0.38],
    [humidity, 0.3],
    [vpd, 0.14],
    [wind, 0.1],
    [instability, 0.08]
  ]);
  const cloudCanvas = weightedAverage([
    [highCloud, 0.46],
    [midCloud, 0.29],
    [totalCloud, 0.14],
    [regionalTexture, 0.11]
  ]);
  const intensityScreen = weightedAverage([
    [highCloud, 0.42],
    [midCloud, 0.23],
    [regionalTexture, 0.22],
    [totalCloud, 0.13]
  ]);
  const opticalColor = weightedAverage([
    [scoreBand(aod ?? 0.1, 0, 0.04, 0.2, 0.55), 0.34],
    [scoreBand(pm25 ?? 8, 0, 3, 12, 35), 0.22],
    [scoreLowerBetter(aqi ?? 40, 18, 110), 0.18],
    [scoreLowerBetter(dust ?? 0, 5, 75), 0.1],
    [humidity, 0.1],
    [vpd, 0.06]
  ]);
  const contrast = weightedAverage([
    [visibility, 0.38],
    [beamPurity, 0.32],
    [scoreBand(window.humidity, mode === 'sunrise' ? 18 : 14, mode === 'sunrise' ? 36 : 28, mode === 'sunrise' ? 76 : 68, 92), 0.3]
  ]);

  const probability = weightedProductScore([
    [horizonOpening, 0.23],
    [solarCorridor, 0.18],
    [lowCloudPenalty, 0.17],
    [rain, 0.15],
    [visibility, 0.09],
    [cloudCanvas, 0.18]
  ]);

  const quality = weightedAverage([
    [intensityScreen, 0.34],
    [opticalColor, 0.24],
    [contrast, 0.16],
    [beamPurity, 0.1],
    [regionalTexture, 0.1],
    [wind, 0.06]
  ]);

  const blockers = [];
  if ((window.cloudLow ?? 0) > 48) blockers.push('Low cloud may block the horizon');
  if ((window.precipProbability ?? 0) > 45) blockers.push('Precipitation risk is elevated');
  if ((window.cloudTotal ?? 0) < 15) blockers.push('Too little cloud texture');
  if ((window.cloudTotal ?? 0) > 88) blockers.push('Cloud deck may be too thick');
  if (((window.visibility ?? 10000) / 1000) < 5) blockers.push('Visibility is limited');
  if (horizonOpening < 35) blockers.push(mode === 'sunset' ? 'Western low-sky opening is weak' : 'Eastern low-sky opening is weak');
  if (solarCorridor < 38) blockers.push(mode === 'sunset' ? 'Sunset light may be blocked upstream' : 'Sunrise light may be blocked upstream');

  const boosts = [];
  if ((window.cloudHigh ?? 0) >= 24 && (window.cloudHigh ?? 0) <= 70) boosts.push('High cloud screen is favorable');
  if ((window.cloudMid ?? 0) >= 18 && (window.cloudMid ?? 0) <= 64) boosts.push('Mid cloud has room to color');
  if ((aod ?? 0.1) >= 0.05 && (aod ?? 0.1) <= 0.32) boosts.push('Aerosol level is balanced');
  if ((window.precipProbability ?? 0) < 25) boosts.push('Precipitation interference is low');
  if (horizonOpening >= 68) boosts.push(mode === 'sunset' ? 'Western low sky is open' : 'Eastern low sky is open');
  if (solarCorridor >= 68) boosts.push(mode === 'sunset' ? 'Sunset light corridor is open' : 'Sunrise light corridor is open');

  return {
    probability,
    quality,
    factors: {
      highCloud,
      midCloud,
      lowCloudPenalty,
      totalCloud,
      humidity,
      rain,
      visibility,
      aerosol,
      wind,
      instability,
      beamPurity,
      sunAccess,
      horizonOpening,
      solarCorridor,
      regionalTexture,
      cloudScreen,
      blockersClearance,
      colorChemistry,
      cloudCanvas,
      intensityScreen,
      opticalColor,
      contrast
    },
    blockers,
    boosts,
    confidence: clamp(58 + (window.indexes.length - 1) * 8 + (context.sampleCount ? Math.min(12, context.sampleCount / 10) : 0) - blockers.length * 6)
  };
}


export { RAD, DEG, clamp, scoreRange, scoreLowerBetter, scoreHigherBetter, scoreBand, weightedAverage, weightedProductScore, parseLocalDate, localIsoToUtcDate, toJulian, toDays, rightAscension, declination, solarMeanAnomaly, eclipticLongitude, siderealTime, sunAltitude, sunAzimuth, findSunAltitudeCrossing, computeBlueHourWindows, findEventAltitudeTime, peakColorAltitudeBand, computeAppearanceWindows, nearestIndex, avg, valueAt, pickWindow, computeWindowScore };
