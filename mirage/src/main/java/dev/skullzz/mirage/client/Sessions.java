package dev.skullzz.mirage.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.skullzz.mirage.Mirage;

/**
 * Sessions of the tracker: the one running, the ones finished, and what to do about them.
 *
 * <p>Nothing is counted unless tracking is on and a session has been started, both by
 * hand. A tally that starts itself is a tally you cannot trust the start of.
 */
public final class Sessions {

    /** Off until switched on: this reads chat, and that should be a decision. */
    private static boolean tracking = false;
    private static boolean hud = false;

    /** How many payments out in a row before it says something. */
    private static int alertAfter = 5;

    /** Rakeback, in basis points, so 12.5% is a whole number. */
    private static int rakebackBps = 500;

    private static Tracker.Session current;
    private static final List<Tracker.Session> past = new ArrayList<>();

    /** The most recent payments, newest last, for the panel. */
    private static final List<Tracker.Payment> recent = new ArrayList<>();
    private static final int RECENT = 40;

    /** Said once when a losing run reaches the threshold, not every payment after it. */
    private static int alertedAt = 0;
    private static String lastAlert = "";

    private Sessions() {
    }

    // -------------------------------------------------------------------- settings

    public static boolean tracking() {
        return tracking;
    }

    public static void setTracking(boolean on) {
        tracking = on;
        save();
    }

    public static boolean hud() {
        return hud;
    }

    public static void setHud(boolean on) {
        hud = on;
        save();
    }

    public static int alertAfter() {
        return alertAfter;
    }

    public static void setAlertAfter(int count) {
        alertAfter = Math.max(2, Math.min(20, count));
        save();
    }

    public static int rakebackBps() {
        return rakebackBps;
    }

    public static void setRakebackBps(int points) {
        rakebackBps = Math.max(0, Math.min(5000, points));
        save();
    }

    // -------------------------------------------------------------------- sessions

    public static Tracker.Session current() {
        return current;
    }

    public static List<Tracker.Session> past() {
        return past;
    }

    public static List<Tracker.Payment> recent() {
        return recent;
    }

    public static String lastAlert() {
        return lastAlert;
    }

    public static void start() {
        if (current != null) stop();
        current = new Tracker.Session(System.currentTimeMillis());
        alertedAt = 0;
        lastAlert = "";
        save();
    }

    public static void stop() {
        if (current == null) return;
        current.ended = System.currentTimeMillis();
        past.add(0, current);
        current = null;
        save();
    }

    public static boolean forget(int index) {
        if (index < 0 || index >= past.size()) return false;
        past.remove(index);
        save();
        return true;
    }

    /**
     * Takes one chat line.
     *
     * @return what it made of it, or null if the line was not a payment
     */
    public static Tracker.Payment offer(String line) {
        if (!tracking) return null;

        Tracker.Payment payment = Tracker.read(line, System.currentTimeMillis());
        if (payment == null) return null;

        recent.add(payment);
        while (recent.size() > RECENT) recent.remove(0);

        if (current != null) {
            current.payments.add(payment);
            checkStreak();
        }
        save();
        return payment;
    }

    /**
     * Says something once when a losing run reaches the threshold.
     *
     * <p>Once per run, not once per payment: an alert that repeats is one that gets
     * ignored, and the run it was about is still the same run.
     */
    private static void checkStreak() {
        int run = current.lossStreak();
        if (run == 0) {
            alertedAt = 0;
            return;
        }
        if (run >= alertAfter && run > alertedAt) {
            alertedAt = run;
            lastAlert = run + " payments out in a row, " + Tracker.money(current.net())
                    + " on the session.";
            ClientDispensers.notice(lastAlert);
        }
    }

    /**
     * Every session added together, the one running included.
     *
     * @return in, out, wins, losses
     */
    public static long[] allTime() {
        long[] total = new long[4];
        List<Tracker.Session> all = new ArrayList<>(past);
        if (current != null) all.add(current);

        for (Tracker.Session session : all) {
            total[0] += session.in();
            total[1] += session.out();
            total[2] += session.wins();
            total[3] += session.losses();
        }
        return total;
    }

    // ----------------------------------------------------------------- persistence

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("mirage-sessions.json");
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("tracking", tracking);
        root.addProperty("hud", hud);
        root.addProperty("alertAfter", alertAfter);
        root.addProperty("rakebackBps", rakebackBps);
        if (current != null) root.add("current", write(current));

        JsonArray old = new JsonArray();
        for (Tracker.Session session : past) old.add(write(session));
        root.add("past", old);

        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), root.toString());
        } catch (IOException failure) {
            Mirage.LOGGER.warn("Mirage could not write the sessions", failure);
        }
    }

    private static JsonObject write(Tracker.Session session) {
        JsonObject json = new JsonObject();
        json.addProperty("started", session.started);
        json.addProperty("ended", session.ended);

        JsonArray payments = new JsonArray();
        for (Tracker.Payment payment : session.payments) {
            JsonObject row = new JsonObject();
            row.addProperty("player", payment.player);
            row.addProperty("cents", payment.cents);
            row.addProperty("in", payment.incoming);
            row.addProperty("at", payment.at);
            payments.add(row);
        }
        json.add("payments", payments);
        return json;
    }

    public static void load() {
        past.clear();
        recent.clear();
        current = null;
        if (!Files.exists(file())) return;

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file()));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();

            tracking = root.has("tracking") && root.get("tracking").getAsBoolean();
            hud = root.has("hud") && root.get("hud").getAsBoolean();
            if (root.has("alertAfter")) setAlertAfterQuietly(root.get("alertAfter").getAsInt());
            if (root.has("rakebackBps")) {
                rakebackBps = Math.max(0, Math.min(5000, root.get("rakebackBps").getAsInt()));
            }

            if (root.has("current")) current = read(root.getAsJsonObject("current"));
            if (root.has("past")) {
                for (JsonElement element : root.getAsJsonArray("past")) {
                    Tracker.Session session = read(element.getAsJsonObject());
                    if (session != null) past.add(session);
                }
            }
        } catch (IOException | RuntimeException failure) {
            Mirage.LOGGER.warn("Mirage could not read the sessions", failure);
        }
    }

    private static void setAlertAfterQuietly(int count) {
        alertAfter = Math.max(2, Math.min(20, count));
    }

    private static Tracker.Session read(JsonObject json) {
        if (json == null || !json.has("started")) return null;

        Tracker.Session session = new Tracker.Session(json.get("started").getAsLong());
        session.ended = json.has("ended") ? json.get("ended").getAsLong() : 0L;

        if (json.has("payments")) {
            for (JsonElement element : json.getAsJsonArray("payments")) {
                JsonObject row = element.getAsJsonObject();
                if (!row.has("cents") || !row.has("player")) continue;
                session.payments.add(new Tracker.Payment(
                        row.get("player").getAsString(),
                        row.get("cents").getAsLong(),
                        row.has("in") && row.get("in").getAsBoolean(),
                        row.has("at") ? row.get("at").getAsLong() : 0L));
            }
        }
        return session;
    }
}
