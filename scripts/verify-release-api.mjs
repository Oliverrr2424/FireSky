import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const base = process.argv[2] || 'https://fireskychase.pages.dev';
const id = crypto.randomUUID();
const email = `release-${id}@example.invalid`;
const password = crypto.randomUUID();
let token;
async function request(path, { method = 'GET', body, type = 'application/json' } = {}) {
  const response = await fetch(`${base}${path}`, {
    method, body,
    headers: { ...(body ? { 'Content-Type': type } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  assert.equal(response.ok, true, `${method} ${path}: ${response.status} ${response.ok ? '' : await response.text()}`);
  return response;
}
try {
  const signup = await (await request('/api/auth/password', { method: 'POST', body: JSON.stringify({
    action: 'signup', email, password, displayName: 'Release verification',
  }) })).json();
  token = signup.token;
  assert.ok(token);
  const login = await (await request('/api/auth/password', { method: 'POST', body: JSON.stringify({ action: 'login', email, password }) })).json();
  assert.ok(login.token);
  token = login.token;
  const photo = await readFile(new URL('../android/app/src/main/res/drawable-nodpi/onboarding_sunset_coast.jpg', import.meta.url));
  const photoPath = `/api/viewpoint-photos/${id}`;
  await request(`/api/viewpoint-photos?id=${id}`, { method: 'POST', body: photo, type: 'image/jpeg' });
  assert.deepEqual(Buffer.from(await (await request(photoPath)).arrayBuffer()), photo);
  const viewpoint = { id, name: 'Release verification', latitude: 43.65, longitude: -79.38 };
  const sync = async (value) => request('/api/sync', { method: 'PUT', body: JSON.stringify({ viewpoints: [value] }) });
  await sync({ ...viewpoint, photo_id: id });
  await sync(viewpoint);
  const synced = await (await request('/api/sync')).json();
  assert.equal(synced.viewpoints[0].photo_id, id);
  // Isolated event far outside the production forecast window.
  const event = { eventAt: 1, latitude: 0, longitude: 0, mode: 'sunset', probability: 50 };
  const reaction = await (await request('/api/feedback', { method: 'POST', body: JSON.stringify({ ...event, sentiment: 'dislike', reason: 'too_high' }) })).json();
  assert.deepEqual(reaction.mine, { sentiment: 'dislike', reason: 'too_high' });
  await request('/api/feedback', { method: 'POST', body: JSON.stringify({ ...event, sentiment: null }) });
  await request(photoPath, { method: 'DELETE' });
  console.log('PASS: signup, password login, authenticated photo round-trip, legacy sync preservation, reactions and photo removal');
} finally {
  if (token) {
    await request('/api/account', { method: 'DELETE' });
    console.log('Removed the synthetic release verification account and its data.');
  }
}
