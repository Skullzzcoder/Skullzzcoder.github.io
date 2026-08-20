package dev.skullzz.donutflipper.store;

import com.google.gson.Gson;
import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.ItemKey;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQLite persistence for collected market data.
 *
 * <p>Opened in WAL mode so the background daemon can keep writing while the
 * Minecraft mod reads. In the default rollback-journal mode those two processes
 * would block each other, and a UI stalling for a second every time the poller
 * commits is exactly the kind of thing that makes a tool feel broken.
 */
public final class Database implements AutoCloseable {

    private static final Gson GSON = new Gson();

    /**
     * Migration scripts in order. Index 0 is version 1. Append here when adding
     * a migration; never renumber or edit a script that has already shipped.
     */
    private static final String[] MIGRATIONS = {
            "/schema/V1__initial.sql",
            "/schema/V2__sweep_tracking.sql",
    };
    private static final int TARGET_VERSION = MIGRATIONS.length;

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path file) throws SQLException, IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            // NORMAL rather than FULL: a crash can cost the last few seconds of
            // polling, which we simply re-fetch. Paying an fsync per commit for
            // data that is re-derivable is not a trade worth making.
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            // Readers wait rather than instantly failing when the daemon holds a
            // write lock during a bulk sweep commit.
            st.execute("PRAGMA busy_timeout=5000");
        }
        Database db = new Database(conn);
        db.migrate();
        return db;
    }

    /** In-memory database for tests. */
    public static Database openInMemory() throws SQLException, IOException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database db = new Database(conn);
        db.migrate();
        return db;
    }

    private void migrate() throws SQLException, IOException {
        int current = currentVersion();
        if (current >= TARGET_VERSION) {
            return;
        }
        for (int v = current + 1; v <= TARGET_VERSION; v++) {
            String sql = readResource(MIGRATIONS[v - 1]);
            try (Statement st = conn.createStatement()) {
                for (String stmt : splitStatements(sql)) {
                    st.execute(stmt);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, v);
                ps.setLong(2, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }
        }
    }

    private int currentVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            // schema_version does not exist yet -- this is a fresh database.
            return 0;
        }
    }

    /**
     * Splits a migration script into executable statements.
     *
     * <p>Line comments are stripped before splitting. Splitting on {@code ';'}
     * alone leaves the trailing comment block after the final statement as a
     * non-blank fragment, which SQLite rejects as "incomplete input" -- so the
     * migration fails on a script that is perfectly valid.
     *
     * <p>Naive with respect to semicolons inside string literals. The migration
     * scripts are ours and contain none; if that ever changes this needs a real
     * tokeniser rather than a quiet wrong answer.
     */
    static List<String> splitStatements(String sql) {
        String stripped = sql.replaceAll("(?m)--[^\\n]*$", "");
        List<String> out = new ArrayList<>();
        for (String part : stripped.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing schema resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Listings
    // ------------------------------------------------------------------

    /**
     * Records a sweep's worth of listings, allocating a fresh sweep number.
     *
     * @return the sweep number used, to pass to {@link #markMissingAsGone}
     */
    public long upsertListings(Collection<Listing> listings) throws SQLException {
        long sweepId = nextSweepId();
        upsertListings(listings, sweepId);
        return sweepId;
    }

    /**
     * Records a sweep's worth of listings under an explicit sweep number.
     *
     * <p>Existing rows keep their original {@code first_seen} and only advance
     * {@code last_seen} and {@code last_sweep}. Preserving true listing age
     * matters: the scanner uses it to tell a genuinely fresh mispricing from one
     * that has sat there for six hours because everyone else already judged it junk.
     */
    public void upsertListings(Collection<Listing> listings, long sweepId) throws SQLException {
        String sql = """
                INSERT INTO listings (listing_id, seller, price, count, unit_price,
                                      exact_key, family_key, item_json, first_seen, last_seen,
                                      gone_at, last_sweep)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                ON CONFLICT(listing_id) DO UPDATE SET
                    last_seen  = excluded.last_seen,
                    price      = excluded.price,
                    gone_at    = NULL,
                    last_sweep = excluded.last_sweep
                """;
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Listing l : listings) {
                ItemKey key = l.key();
                ps.setString(1, l.listingId());
                ps.setString(2, l.seller());
                ps.setLong(3, l.price());
                ps.setInt(4, l.item().count());
                ps.setDouble(5, l.unitPrice());
                ps.setString(6, key.exact());
                ps.setString(7, key.family());
                ps.setString(8, GSON.toJson(l.item()));
                ps.setLong(9, l.firstSeen().getEpochSecond());
                ps.setLong(10, l.lastSeen().getEpochSecond());
                ps.setLong(11, sweepId);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * Allocates the next sweep number. Monotonic and independent of the clock.
     */
    public long nextSweepId() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sweep_counter SET value = value + 1 WHERE id = 1");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM sweep_counter WHERE id = 1")) {
            return rs.next() ? rs.getLong(1) : 1L;
        }
    }

    /**
     * Marks listings that vanished from the feed. Anything still flagged active
     * but absent from the named sweep either sold or was cancelled.
     *
     * <p>Compares sweep numbers rather than timestamps. The timestamp version of
     * this reconciled nothing when two sweeps fell inside the same stored second,
     * because {@code last_seen < sweepStart} is false when they are equal.
     *
     * @param sweepId the sweep that just completed
     * @param goneAt  when to record the disappearance
     */
    public int markMissingAsGone(long sweepId, Instant goneAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE listings SET gone_at = ? WHERE gone_at IS NULL AND last_sweep < ?")) {
            ps.setLong(1, goneAt.getEpochSecond());
            ps.setLong(2, sweepId);
            return ps.executeUpdate();
        }
    }

    public List<Listing> activeListings() throws SQLException {
        List<Listing> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT listing_id, seller, price, item_json, first_seen, last_seen "
                        + "FROM listings WHERE gone_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Listing(
                        rs.getString("listing_id"),
                        rs.getString("seller"),
                        rs.getLong("price"),
                        GSON.fromJson(rs.getString("item_json"), AuctionItem.class),
                        Instant.ofEpochSecond(rs.getLong("first_seen")),
                        Instant.ofEpochSecond(rs.getLong("last_seen"))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Sales
    // ------------------------------------------------------------------

    /** Inserts sales, ignoring ones already recorded -- the feed overlaps between sweeps. */
    public int insertSales(Collection<Sale> sales) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO sales (sale_id, seller, buyer, price, count, unit_price,
                                             exact_key, family_key, item_json, sold_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Sale s : sales) {
                ItemKey key = s.key();
                ps.setString(1, s.saleId());
                ps.setString(2, s.seller());
                ps.setString(3, s.buyer());
                ps.setLong(4, s.price());
                ps.setInt(5, s.item().count());
                ps.setDouble(6, s.unitPrice());
                ps.setString(7, key.exact());
                ps.setString(8, key.family());
                ps.setString(9, GSON.toJson(s.item()));
                ps.setLong(10, s.soldAt().getEpochSecond());
                ps.addBatch();
            }
            for (int r : ps.executeBatch()) {
                if (r > 0) inserted++;
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
        return inserted;
    }

    /** Sales for one exact key, newest first, bounded by time and count. */
    public List<Sale> salesForKey(String exactKey, Instant since, int limit) throws SQLException {
        return querySales("exact_key", exactKey, since, limit);
    }

    /** Sales across a whole material family -- the thin-data fallback. */
    public List<Sale> salesForFamily(String familyKey, Instant since, int limit) throws SQLException {
        return querySales("family_key", familyKey, since, limit);
    }

    private List<Sale> querySales(String column, String value, Instant since, int limit)
            throws SQLException {
        List<Sale> out = new ArrayList<>();
        String sql = "SELECT sale_id, seller, buyer, price, item_json, sold_at FROM sales "
                + "WHERE " + column + " = ? AND sold_at >= ? ORDER BY sold_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setLong(2, since.getEpochSecond());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Sale(
                            rs.getString("sale_id"),
                            rs.getString("seller"),
                            rs.getString("buyer"),
                            rs.getLong("price"),
                            GSON.fromJson(rs.getString("item_json"), AuctionItem.class),
                            Instant.ofEpochSecond(rs.getLong("sold_at"))));
                }
            }
        }
        return out;
    }

    /** Distinct exact keys that have any sale history -- the valuation work list. */
    public List<String> keysWithSales(Instant since) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT exact_key FROM sales WHERE sold_at >= ?")) {
            ps.setLong(1, since.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // Alert dedupe
    // ------------------------------------------------------------------

    /**
     * Returns true the first time a listing is worth announcing.
     *
     * <p>Re-alerts only if the flip got materially better (25%+ score jump),
     * which happens when a valuation firms up. Without this the UI would replay
     * the same twenty flips on every sweep and you would stop reading it.
     */
    public boolean shouldAlert(String listingId, double score, long netProfit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT score FROM alerts WHERE listing_id = ?")) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && score <= rs.getDouble("score") * 1.25) {
                    return false;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO alerts (listing_id, alerted_at, score, net_profit) "
                        + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, listingId);
            ps.setLong(2, Instant.now().getEpochSecond());
            ps.setDouble(3, score);
            ps.setLong(4, netProfit);
            ps.executeUpdate();
        }
        return true;
    }

    /**
     * Listings that were live at a past instant -- the backtest's view of the
     * board as it stood then.
     *
     * <p>A listing counts as live if we had already seen it and it had not yet
     * disappeared. Getting this wrong in the permissive direction would let the
     * backtest "buy" listings that were already gone, which flatters the results
     * for free.
     */
    public List<Listing> listingsActiveAt(Instant at) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT listing_id, seller, price, item_json, first_seen, last_seen "
                + "FROM listings WHERE first_seen <= ? AND (gone_at IS NULL OR gone_at > ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, at.getEpochSecond());
            ps.setLong(2, at.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Listing(
                            rs.getString("listing_id"),
                            rs.getString("seller"),
                            rs.getLong("price"),
                            GSON.fromJson(rs.getString("item_json"), AuctionItem.class),
                            Instant.ofEpochSecond(rs.getLong("first_seen")),
                            Instant.ofEpochSecond(rs.getLong("last_seen"))));
                }
            }
        }
        return out;
    }

    /**
     * Sales for a key inside an explicit time range.
     *
     * <p>The upper bound is what keeps the backtest honest: valuing an item with
     * sales that had not happened yet is the classic way to build a backtest that
     * looks brilliant and predicts nothing.
     */
    public List<Sale> salesForKeyBetween(String exactKey, Instant from, Instant to)
            throws SQLException {
        List<Sale> out = new ArrayList<>();
        String sql = "SELECT sale_id, seller, buyer, price, item_json, sold_at FROM sales "
                + "WHERE exact_key = ? AND sold_at >= ? AND sold_at < ? ORDER BY sold_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, exactKey);
            ps.setLong(2, from.getEpochSecond());
            ps.setLong(3, to.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Sale(
                            rs.getString("sale_id"),
                            rs.getString("seller"),
                            rs.getString("buyer"),
                            rs.getLong("price"),
                            GSON.fromJson(rs.getString("item_json"), AuctionItem.class),
                            Instant.ofEpochSecond(rs.getLong("sold_at"))));
                }
            }
        }
        return out;
    }

    public long countRows(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public Connection connection() {
        return conn;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
