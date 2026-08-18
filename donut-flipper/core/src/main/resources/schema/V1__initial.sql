-- DonutSMP Flipper -- initial schema.
--
-- Design notes:
--  * unit_price is stored, not just derived, because every query that matters
--    filters or aggregates on it and SQLite cannot index an expression cheaply
--    across the volumes this table reaches.
--  * exact_key and family_key are denormalised onto both fact tables. Joining
--    through an items dimension would be tidier and materially slower on the
--    hot path, which runs every time the in-game UI refreshes.
--  * item_json keeps the full normalised item so keys can be recomputed after a
--    keying-logic change without re-fetching months of history from the API.

CREATE TABLE IF NOT EXISTS listings (
    listing_id  TEXT PRIMARY KEY,
    seller      TEXT    NOT NULL,
    price       INTEGER NOT NULL,
    count       INTEGER NOT NULL,
    unit_price  REAL    NOT NULL,
    exact_key   TEXT    NOT NULL,
    family_key  TEXT    NOT NULL,
    item_json   TEXT    NOT NULL,
    first_seen  INTEGER NOT NULL,
    last_seen   INTEGER NOT NULL,
    -- Set when a listing stops appearing in the feed. It either sold or was
    -- cancelled; the transaction feed tells us which. Time-to-disappear is the
    -- backtest's proxy for how fast an item really moves.
    gone_at     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_listings_exact  ON listings (exact_key, gone_at);
CREATE INDEX IF NOT EXISTS idx_listings_family ON listings (family_key, gone_at);
CREATE INDEX IF NOT EXISTS idx_listings_active ON listings (gone_at, unit_price);

CREATE TABLE IF NOT EXISTS sales (
    sale_id     TEXT PRIMARY KEY,
    seller      TEXT    NOT NULL,
    buyer       TEXT,
    price       INTEGER NOT NULL,
    count       INTEGER NOT NULL,
    unit_price  REAL    NOT NULL,
    exact_key   TEXT    NOT NULL,
    family_key  TEXT    NOT NULL,
    item_json   TEXT    NOT NULL,
    sold_at     INTEGER NOT NULL
);

-- Valuation windows are always "this key, recent first", so the index leads
-- with the key and carries the timestamp descending.
CREATE INDEX IF NOT EXISTS idx_sales_exact_time  ON sales (exact_key, sold_at DESC);
CREATE INDEX IF NOT EXISTS idx_sales_family_time ON sales (family_key, sold_at DESC);
CREATE INDEX IF NOT EXISTS idx_sales_time        ON sales (sold_at DESC);

CREATE TABLE IF NOT EXISTS valuations (
    exact_key        TEXT PRIMARY KEY,
    fair_unit_price  REAL    NOT NULL,
    sample_count     INTEGER NOT NULL,
    distinct_sellers INTEGER NOT NULL,
    sales_per_day    REAL    NOT NULL,
    confidence       TEXT    NOT NULL,
    -- Fractional drift per day. Negative means the item is losing value, which
    -- makes an apparent discount a falling knife rather than an opportunity.
    trend            REAL    NOT NULL DEFAULT 0,
    rejected_samples INTEGER NOT NULL DEFAULT 0,
    computed_at      INTEGER NOT NULL
);

-- One row per listing we have already surfaced, so a flip that stays on the
-- board for an hour is not re-announced on every 60-second sweep.
CREATE TABLE IF NOT EXISTS alerts (
    listing_id  TEXT PRIMARY KEY,
    alerted_at  INTEGER NOT NULL,
    score       REAL    NOT NULL,
    net_profit  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
