import { test } from 'node:test';
import assert from 'node:assert/strict';
import { passwordHash } from '../functions/_shared/account.js';

test('password hashes retain the existing PBKDF2 format and work factor', async () => {
  const password = 'release-regression-only';
  const salt = new Uint8Array(16).fill(17);
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  const expected = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 180000 }, key, 256);
  const actual = await passwordHash(password, Buffer.from(salt).toString('base64'));
  assert.equal(actual, Buffer.from(expected).toString('base64'));
  assert.notEqual(await passwordHash(`${password}!`, Buffer.from(salt).toString('base64')), actual);
});
