-- Forecast reactions carry an optional reason for a dislike, and saved camera
-- viewpoints can carry one photo. Photos live in D1 as BLOBs: the app downsizes
-- them to well under D1's 2 MB row limit before upload.
ALTER TABLE forecast_feedback ADD COLUMN reason TEXT;
CREATE INDEX IF NOT EXISTS forecast_feedback_event ON forecast_feedback(mode, event_at);

ALTER TABLE viewpoints ADD COLUMN photo_id TEXT;

CREATE TABLE IF NOT EXISTS viewpoint_photos (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  content_type TEXT NOT NULL CHECK(content_type IN ('image/jpeg', 'image/png', 'image/webp')),
  width INTEGER,
  height INTEGER,
  byte_size INTEGER NOT NULL,
  data BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS viewpoint_photos_user ON viewpoint_photos(user_id, created_at DESC);
