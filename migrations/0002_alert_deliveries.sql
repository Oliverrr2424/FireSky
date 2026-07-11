CREATE TABLE IF NOT EXISTS alert_deliveries (
  user_id TEXT NOT NULL,
  event_at INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('sunrise', 'sunset')),
  device_id TEXT NOT NULL,
  sent_at INTEGER NOT NULL,
  PRIMARY KEY(user_id, event_at, mode, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY(device_id) REFERENCES devices(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS alert_deliveries_event ON alert_deliveries(event_at);
