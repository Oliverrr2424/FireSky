import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {sha256} from '../functions/_shared/account.js';
import {onRequestGet as getFeedback, onRequestPost as postFeedback} from '../functions/api/feedback.js';
import {onRequestPost as uploadPhoto} from '../functions/api/viewpoint-photos/index.js';
import {onRequestGet as readPhoto, onRequestDelete as removePhoto} from '../functions/api/viewpoint-photos/[id].js';
import {onRequestGet as getSync, onRequestPut as putSync} from '../functions/api/sync.js';
import {onRequestDelete as deleteAccount} from '../functions/api/account.js';

// A D1 look-alike over node:sqlite: statements are bound lazily, batches run in order,
// and ArrayBuffer parameters become BLOBs the way D1 stores them.
function database() {
  const sql = new DatabaseSync(':memory:');
  for (const file of ['0001_accounts.sql', '0006_reactions_and_viewpoint_photos.sql']) sql.exec(readFileSync(new URL(`../migrations/${file}`, import.meta.url), 'utf8'));
  const wrap = (query, args = []) => {
    const isRead = /^\s*select/i.test(query);
    const statement = {
      bind: (...values) => wrap(query, values.map((value) => (value instanceof ArrayBuffer ? new Uint8Array(value) : value))),
      run: async () => ({meta: {changes: Number(sql.prepare(query).run(...args).changes)}}),
      all: async () => ({results: sql.prepare(query).all(...args)}),
      first: async () => sql.prepare(query).get(...args) ?? null,
    };
    statement.exec = () => (isRead ? statement.all() : statement.run());
    return statement;
  };
  return {prepare: (query) => wrap(query), batch: async (items) => { const out = []; for (const item of items) out.push(await item.exec()); return out; }, sql};
}

async function signUp(db, name) {
  const token = `${name}-`.padEnd(40, 'x');
  const now = Date.now();
  db.sql.prepare('INSERT INTO users (id,email,display_name,created_at,updated_at) VALUES (?,?,?,?,?)').run(name, `${name}@example.test`, name, now, now);
  db.sql.prepare('INSERT INTO sessions (id,user_id,token_hash,created_at,expires_at,last_seen_at) VALUES (?,?,?,?,?,?)').run(`s-${name}`, name, await sha256(token), now, now + 86400000, now);
  return token;
}

const call = (handler, db, url, {token, method = 'GET', body, headers = {}, params} = {}) => handler({
  request: new Request(`https://example.test${url}`, {method, body, headers: {...(token ? {Authorization: `Bearer ${token}`} : {}), ...headers}}),
  env: {FIRESKY_DB: db}, params,
});
const json = (value) => JSON.stringify(value);
const eventAt = Date.parse('2026-10-22T22:20:00Z');
const react = (db, token, extra = {}) => call(postFeedback, db, '/api/feedback', {token, method: 'POST', body: json({eventAt, mode: 'sunset', placeName: 'Toronto', latitude: 43.6532, longitude: -79.3832, probability: 54, ...extra})});

test('a reaction toggles, switches and withdraws; reasons only attach to a dislike', async () => {
  const db = database(), ada = await signUp(db, 'ada');
  let result = await (await react(db, ada, {sentiment: 'like', reason: 'too_high'})).json();
  assert.deepEqual(result.counts, {like: 1, dislike: 0});
  assert.deepEqual(result.mine, {sentiment: 'like', reason: null});
  result = await (await react(db, ada, {sentiment: 'dislike', reason: 'too_high'})).json();
  assert.deepEqual(result.counts, {like: 0, dislike: 1});
  assert.deepEqual(result.mine, {sentiment: 'dislike', reason: 'too_high'});
  result = await (await react(db, ada, {sentiment: 'dislike', reason: 'not-a-reason'})).json();
  assert.equal(result.mine.reason, null);
  result = await (await react(db, ada, {sentiment: null})).json();
  assert.deepEqual(result.counts, {like: 0, dislike: 0});
  assert.equal(result.mine, null);
  assert.equal(db.sql.prepare('SELECT COUNT(*) n FROM forecast_feedback').get().n, 0);
});

test('counts aggregate people near the same event; GET is public and returns your own reaction only when signed in', async () => {
  const db = database(), ada = await signUp(db, 'ada'), bo = await signUp(db, 'bo'), cy = await signUp(db, 'cy');
  await react(db, ada, {sentiment: 'like'});
  await react(db, bo, {sentiment: 'dislike', reason: 'wrong_time', latitude: 43.7, longitude: -79.4, eventAt: eventAt + 6 * 60000});
  await react(db, cy, {sentiment: 'like', latitude: 45.4, longitude: -75.7});
  const query = `/api/feedback?eventAt=${eventAt}&mode=sunset&lat=43.65&lon=-79.38`;
  const anonymous = await (await call(getFeedback, db, query)).json();
  assert.deepEqual(anonymous.counts, {like: 1, dislike: 1});
  assert.equal(anonymous.mine, null);
  assert.equal(anonymous.signedIn, false);
  const bos = await (await call(getFeedback, db, query, {token: bo})).json();
  assert.deepEqual(bos.mine, {sentiment: 'dislike', reason: 'wrong_time'});
  assert.deepEqual((await (await call(getFeedback, db, query.replace('sunset', 'sunrise'))).json()).counts, {like: 0, dislike: 0});
  assert.equal((await call(getFeedback, db, '/api/feedback?mode=sunset')).status, 400);
  assert.equal((await call(getFeedback, db, query, {token: 'expired'.padEnd(40, 'x')})).status, 200);
  assert.equal((await react(db, undefined, {sentiment: 'like'})).status, 401);
});

const jpeg = (size = 600) => { const bytes = new Uint8Array(size); bytes.set([0xff, 0xd8, 0xff, 0xe0]); return bytes; };
const upload = (db, token, id, bytes, type = 'image/jpeg') => call(uploadPhoto, db, `/api/viewpoint-photos?id=${id}&width=800&height=600`, {token, method: 'POST', body: bytes, headers: {'Content-Type': type}});

test('photo upload validates type, signature and size, and the owner can read it back', async () => {
  const db = database(), ada = await signUp(db, 'ada'), bo = await signUp(db, 'bo');
  assert.equal((await upload(db, ada, 'photo-ada-0001', jpeg(), 'text/plain')).status, 415);
  assert.equal((await upload(db, ada, 'photo-ada-0001', new Uint8Array(40).fill(7))).status, 415);
  assert.equal((await upload(db, ada, 'photo-ada-0001', new Uint8Array(0))).status, 400);
  assert.equal((await upload(db, ada, 'photo-ada-0001', jpeg(1_500_001))).status, 413);
  assert.equal((await upload(db, undefined, 'photo-ada-0001', jpeg())).status, 401);
  const stored = await upload(db, ada, 'photo-ada-0001', jpeg());
  assert.equal(stored.status, 200);
  assert.equal((await stored.json()).byteSize, 600);
  const read = await call(readPhoto, db, '/api/viewpoint-photos/photo-ada-0001', {token: ada, params: {id: 'photo-ada-0001'}});
  assert.equal(read.status, 200);
  assert.equal(read.headers.get('Content-Type'), 'image/jpeg');
  assert.deepEqual(new Uint8Array(await read.arrayBuffer()), jpeg());
  assert.equal((await call(readPhoto, db, '/x', {token: bo, params: {id: 'photo-ada-0001'}})).status, 404);
  assert.equal((await call(readPhoto, db, '/x', {params: {id: 'photo-ada-0001'}})).status, 401);
  assert.equal((await upload(db, bo, 'photo-ada-0001', jpeg())).status, 409);
  assert.equal((await upload(db, ada, 'photo-ada-0001', jpeg(900))).status, 200);
  assert.equal(db.sql.prepare('SELECT byte_size n FROM viewpoint_photos').get().n, 900);
});

test('one account cannot exceed its photo quota', async () => {
  const db = database(), ada = await signUp(db, 'ada');
  const now = Date.now();
  for (let i = 0; i < 60; i++) db.sql.prepare('INSERT INTO viewpoint_photos (id,user_id,content_type,byte_size,data,created_at) VALUES (?,?,?,?,?,?)').run(`filler-${i}-xxxx`, 'ada', 'image/jpeg', 10, jpeg(10), now);
  assert.equal((await upload(db, ada, 'photo-ada-0002', jpeg())).status, 413);
  assert.equal((await upload(db, ada, 'filler-1-xxxx', jpeg())).status, 200);
});

test('sync keeps photos that belong to the viewpoint owner, drops foreign ids, and sweeps stale orphans', async () => {
  const db = database(), ada = await signUp(db, 'ada'), bo = await signUp(db, 'bo');
  await upload(db, ada, 'photo-ada-0001', jpeg());
  await upload(db, bo, 'photo-bo-00001', jpeg());
  const hour = 60 * 60 * 1000;
  db.sql.prepare("UPDATE viewpoint_photos SET created_at = ? WHERE id = 'photo-bo-00001'").run(Date.now() - 2 * hour);
  await upload(db, ada, 'photo-ada-stale', jpeg());
  db.sql.prepare("UPDATE viewpoint_photos SET created_at = ? WHERE id = 'photo-ada-stale'").run(Date.now() - 2 * hour);
  await upload(db, ada, 'photo-ada-fresh', jpeg());
  const viewpoints = [
    {id: 'v1', name: 'Rooftop', latitude: 43.65, longitude: -79.38, photo_id: 'photo-ada-0001'},
    {id: 'v2', name: 'Borrowed', latitude: 43.7, longitude: -79.4, photo_id: 'photo-bo-00001'},
    {id: 'v3', name: 'Plain', latitude: 43.8, longitude: -79.5},
  ];
  assert.equal((await call(putSync, db, '/api/sync', {token: ada, method: 'PUT', body: json({locations: [], viewpoints})})).status, 200);
  const synced = await (await call(getSync, db, '/api/sync', {token: ada})).json();
  const byId = Object.fromEntries(synced.viewpoints.map((row) => [row.id, row.photo_id]));
  assert.deepEqual(byId, {v1: 'photo-ada-0001', v2: null, v3: null});
  const left = db.sql.prepare('SELECT id FROM viewpoint_photos ORDER BY id').all().map((row) => row.id);
  assert.deepEqual(left, ['photo-ada-0001', 'photo-ada-fresh', 'photo-bo-00001']);
});

test('legacy sync preserves viewpoint photos, while explicit null removes them', async () => {
  const db = database(), ada = await signUp(db, 'ada');
  await upload(db, ada, 'photo-ada-0001', jpeg());
  const viewpoint = {id: 'v1', name: 'Rooftop', latitude: 43.65, longitude: -79.38};
  const sync = (value) => call(putSync, db, '/api/sync', {token: ada, method: 'PUT', body: json({viewpoints: [value]})});
  await sync({...viewpoint, photo_id: 'photo-ada-0001'});
  await sync(viewpoint);
  assert.equal(db.sql.prepare('SELECT photo_id FROM viewpoints WHERE id = ?').get('v1').photo_id, 'photo-ada-0001');
  await sync({...viewpoint, photo_id: null});
  assert.equal(db.sql.prepare('SELECT photo_id FROM viewpoints WHERE id = ?').get('v1').photo_id, null);
});

test('deleting a photo clears the viewpoint reference; deleting the account removes every photo', async () => {
  const db = database(), ada = await signUp(db, 'ada');
  await upload(db, ada, 'photo-ada-0001', jpeg());
  await call(putSync, db, '/api/sync', {token: ada, method: 'PUT', body: json({viewpoints: [{id: 'v1', name: 'Rooftop', latitude: 43.65, longitude: -79.38, photo_id: 'photo-ada-0001'}]})});
  assert.equal(db.sql.prepare('SELECT photo_id p FROM viewpoints').get().p, 'photo-ada-0001');
  assert.equal((await call(removePhoto, db, '/x', {token: ada, method: 'DELETE', params: {id: 'photo-ada-0001'}})).status, 200);
  assert.equal(db.sql.prepare('SELECT photo_id p FROM viewpoints').get().p, null);
  assert.equal(db.sql.prepare('SELECT COUNT(*) n FROM viewpoint_photos').get().n, 0);
  await upload(db, ada, 'photo-ada-0002', jpeg());
  assert.equal((await call(deleteAccount, db, '/api/account', {token: ada, method: 'DELETE'})).status, 200);
  assert.equal(db.sql.prepare('SELECT COUNT(*) n FROM viewpoint_photos').get().n, 0);
});
