import { errorResponse, jsonResponse } from '../_shared/openMeteo.js';

const MAX_BODY_BYTES = 8 * 1024;
const RETENTION_SECONDS = 30 * 24 * 60 * 60;
const ALLOWED_EVENTS = new Set([
  'app_open',
  'api_request',
  'api_error',
  'heatmap_render',
  'forecast_feedback',
  'favorite_location',
  'forecast_shared',
  'location_selected',
  'outlook_day_selected',
  'map_sample_selected',
  'notification_opened',
  'web_error',
  'web_unhandled_rejection',
  'ui_long_task',
  'native_crash',
  'native_anr'
]);

function cleanText(value, max = 160) {
  return typeof value === 'string' ? value.slice(0, max) : undefined;
}

function sanitize(payload) {
  const event = cleanText(payload?.event, 64);
  if (!ALLOWED_EVENTS.has(event)) return null;
  const rawDetail = payload?.detail && typeof payload.detail === 'object' ? payload.detail : {};
  return {
    event,
    at: Number.isFinite(payload?.at) ? payload.at : Date.now(),
    // An opaque per-install id lets us de-duplicate reports without retaining
    // account, location, IP, or device identifiers in the event body.
    installation: cleanText(payload?.installation, 96),
    platform: cleanText(payload?.platform, 24),
    detail: {
      route: cleanText(rawDetail.route, 80),
      durationMs: Number.isFinite(rawDetail.durationMs) ? Math.round(rawDetail.durationMs) : undefined,
      status: Number.isFinite(rawDetail.status) ? rawDetail.status : undefined,
      reason: cleanText(rawDetail.reason, 160),
      type: cleanText(rawDetail.type, 32),
      value: cleanText(rawDetail.value, 32),
      kind: cleanText(rawDetail.kind, 32),
      action: cleanText(rawDetail.action, 32),
      total: Number.isFinite(rawDetail.total) ? rawDetail.total : undefined,
      dayOffset: Number.isFinite(rawDetail.dayOffset) ? rawDetail.dayOffset : undefined,
      score: Number.isFinite(rawDetail.score) ? rawDetail.score : undefined,
      samples: Number.isFinite(rawDetail.samples) ? rawDetail.samples : undefined
    }
  };
}

export async function onRequestPost({ request, env }) {
  try {
    const length = Number(request.headers.get('Content-Length') ?? 0);
    if (length > MAX_BODY_BYTES) return errorResponse('Telemetry payload is too large', 413);
    const text = await request.text();
    if (text.length > MAX_BODY_BYTES) return errorResponse('Telemetry payload is too large', 413);
    const event = sanitize(JSON.parse(text));
    if (!event) return errorResponse('Unsupported telemetry event', 400);

    const cache = env.FIRESKY_CACHE;
    if (cache) {
      const day = new Date(event.at).toISOString().slice(0, 10);
      await cache.put(`firesky:telemetry:v1:${day}:${crypto.randomUUID()}`, JSON.stringify(event), {
        expirationTtl: RETENTION_SECONDS
      });
    }
    return jsonResponse({ accepted: true }, { status: 202, headers: { 'Cache-Control': 'no-store' } });
  } catch {
    // Avoid returning diagnostics detail to the client and never make logging
    // failures impact the weather API.
    return errorResponse('Unable to record telemetry', 400);
  }
}

export function onRequestOptions() {
  return new Response(null, {
    status: 204,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    }
  });
}
