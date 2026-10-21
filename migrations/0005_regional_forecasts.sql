-- Reservation is a durable, atomic claim. Failed requests remain charged.
CREATE TABLE IF NOT EXISTS regional_runs (
 region_id TEXT NOT NULL, slot INTEGER NOT NULL, budget_day TEXT NOT NULL,
 units INTEGER NOT NULL CHECK(units > 0), sampled_at INTEGER NOT NULL,
 status TEXT NOT NULL DEFAULT 'claimed', error TEXT,
 PRIMARY KEY(region_id,slot)
);
CREATE INDEX IF NOT EXISTS regional_budget_day ON regional_runs(budget_day);
CREATE TABLE IF NOT EXISTS regional_forecasts (
 region_id TEXT NOT NULL, event_date TEXT NOT NULL, mode TEXT NOT NULL,
 sampled_at INTEGER NOT NULL, event_at INTEGER NOT NULL,
 probability REAL NOT NULL, quality REAL NOT NULL, model_version TEXT NOT NULL,
 PRIMARY KEY(region_id,event_date,mode,sampled_at,model_version)
);
CREATE INDEX IF NOT EXISTS regional_history ON regional_forecasts(region_id,event_date,mode,model_version,sampled_at);
