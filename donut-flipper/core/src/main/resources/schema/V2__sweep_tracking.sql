-- Reconciliation by sweep number rather than by timestamp.
--
-- The original design marked a listing gone when its last_seen predated the
-- current sweep's start time. Timestamps are stored at second resolution, so two
-- sweeps landing inside the same second compared as equal and nothing was ever
-- reconciled. Sweeps are normally a minute apart, which hid the problem, but
-- correctness that depends on clock granularity is not correctness.
--
-- A monotonic counter makes the comparison exact regardless of timing.

ALTER TABLE listings ADD COLUMN last_sweep INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_listings_sweep ON listings (gone_at, last_sweep);

CREATE TABLE IF NOT EXISTS sweep_counter (
    id    INTEGER PRIMARY KEY CHECK (id = 1),
    value INTEGER NOT NULL
);

INSERT OR IGNORE INTO sweep_counter (id, value) VALUES (1, 0);
