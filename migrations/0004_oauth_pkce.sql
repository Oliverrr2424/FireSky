-- PKCE for native OAuth: the app sends a code_challenge with /api/auth/start,
-- the callback redirects with a short-lived one-time code instead of a session
-- token, and /api/auth/exchange only issues the session to whoever holds the
-- matching code_verifier.
ALTER TABLE oauth_states ADD COLUMN code_challenge TEXT;

CREATE TABLE IF NOT EXISTS oauth_codes (
  code_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS oauth_codes_expiry ON oauth_codes(expires_at);
