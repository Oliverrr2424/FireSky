// Camera-viewpoint photos stored in D1. The app resizes before upload; these
// limits keep a row under D1's 2 MB cap and a single account from filling the DB.
export const MAX_PHOTO_BYTES = 1_500_000;
export const MAX_PHOTOS_PER_USER = 60;
export const MAX_PHOTO_BYTES_PER_USER = 40_000_000;
const TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export function cleanPhotoId(value) {
  const id = String(value || '').trim();
  return /^[A-Za-z0-9_-]{8,64}$/.test(id) ? id : null;
}

export function photoType(header) {
  const type = String(header || '').split(';')[0].trim().toLowerCase();
  return TYPES.has(type) ? type : null;
}

// The Content-Type header is client-controlled, so the leading bytes must agree with it.
export function matchesSignature(type, bytes) {
  const at = (index, ...values) => values.every((value, offset) => bytes[index + offset] === value);
  if (type === 'image/jpeg') return at(0, 0xff, 0xd8, 0xff);
  if (type === 'image/png') return at(0, 0x89, 0x50, 0x4e, 0x47);
  if (type === 'image/webp') return at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50);
  return false;
}

// D1 hands BLOBs back as an ArrayBuffer, a typed array or an array of numbers depending on the path.
export function blobBytes(value) {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  return Uint8Array.from(value || []);
}
