-- FireSky user data. D1's foreign keys are kept on for local development;
-- deletion is explicit in account.js so account erasure also works on older DBs.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT,
  display_name TEXT NOT NULL,
  avatar_url TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_identities (
  provider TEXT NOT NULL CHECK(provider IN ('google', 'apple')),
  provider_subject TEXT NOT NULL,
  user_id TEXT NOT NULL,
  email TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(provider, provider_subject),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
CREATE TABLE IF NOT EXISTS oauth_states (
  state TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  return_to TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS oauth_states_expiry ON oauth_states(expires_at);
CREATE TABLE IF NOT EXISTS user_settings (
  user_id TEXT PRIMARY KEY,
  notifications_enabled INTEGER NOT NULL DEFAULT 1,
  alert_lead_minutes INTEGER NOT NULL DEFAULT 60,
  sunrise_alerts INTEGER NOT NULL DEFAULT 1,
  sunset_alerts INTEGER NOT NULL DEFAULT 1,
  alert_threshold INTEGER NOT NULL DEFAULT 70,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS saved_locations (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  admin1 TEXT,
  country_code TEXT,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  label TEXT,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS saved_locations_user ON saved_locations(user_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS viewpoints (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  note TEXT,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS viewpoints_user ON viewpoints(user_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS forecast_feedback (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  event_at INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('sunrise', 'sunset')),
  place_name TEXT,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  predicted_probability INTEGER NOT NULL,
  outcome TEXT NOT NULL DEFAULT 'unknown' CHECK(outcome IN ('yes', 'no', 'unknown')),
  sentiment TEXT CHECK(sentiment IN ('like', 'dislike')),
  notes TEXT,
  updated_at INTEGER NOT NULL,
  UNIQUE(user_id,event_at,mode,latitude,longitude),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS forecast_snapshots (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  event_at INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('sunrise', 'sunset')),
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  calculated_at INTEGER NOT NULL,
  probability INTEGER NOT NULL,
  quality INTEGER NOT NULL,
  model_version TEXT,
  UNIQUE(user_id,event_at,mode,latitude,longitude,calculated_at),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS snapshots_event ON forecast_snapshots(user_id,event_at,mode,calculated_at);
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  push_token TEXT NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
