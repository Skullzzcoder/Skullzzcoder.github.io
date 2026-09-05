package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What you have won and lost, worked out from chat.
 *
 * <p>Accounting, not advantage: every line this reads is one already on your screen. It
 * adds up what came in and what went out, and says whether you are up.
 *
 * <p>No Minecraft in here at all, deliberately. Money parsing is the part that goes wrong
 * quietly -- "$1.5M" and "$1,500" and "$1.5" are three different numbers and two of them
 * look alike -- so it is kept where it can be run against real lines on a machine with no
 * game on it. The chat event is somebody else's problem, in {@link ChatHook}.
 *
 * <p>Amounts are held in cents. A balance parsed as dollars loses half of "$1.50" and
 * rounds every rakeback share, and being out by a cent a trade is how a tally stops
 * matching the one in your head.
 */
public final class Tracker {

    /** One payment, in or out. */
    public static final class Payment {
        public final String player;
        public final long cents;
        public final boolean incoming;
        public final long at;

        public Payment(String player, long cents, boolean incoming, long at) {
            this.player = player;
            this.cents = cents;
            this.incoming = incoming;
            this.at = at;
        }

        /** Signed: what this did to your balance. */
        public long signed() {
            return this.incoming ? this.cents : -this.cents;
        }

        @Override
        public String toString() {
            return (this.incoming ? "+" : "-") + money(this.cents) + " " + this.player;
        }
    }

    /**
     * The lines a payment can arrive as.
     *
     * <p>Two shapes, because that is what the server sends, and both anchored to the start
     * of the line. Chat is written by other people: without the anchor, anybody typing
     * "you paid Bob $10000000" in public chat would land in your tally, and a tally that
     * can be written to by strangers is worse than no tally. A leading [tag] the server
     * adds is dropped first, since that is a prefix and not a sentence.
     */
    private static final Pattern IN = Pattern.compile(
            "^([A-Za-z0-9_]{3,16})\\s+(?:has\\s+)?paid\\s+you\\s+\\$?([0-9][0-9,.]*)\\s*([kmbtKMBT]?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OUT = Pattern.compile(
            "^you\\s+(?:have\\s+)?paid\\s+([A-Za-z0-9_]{3,16})\\s+\\$?([0-9][0-9,.]*)\\s*([kmbtKMBT]?)",
            Pattern.CASE_INSENSITIVE);

    /** A leading [tag] or (tag) the server puts in front, and nothing else. */
    private static final Pattern LEADING_TAG = Pattern.compile("^(?:\\[[^\\]]{0,24}\\]|\\([^)]{0,24}\\))\\s*");

    private Tracker() {
    }

    /**
     * Reads one chat line, or gives back nothing.
     *
     * <p>"You paid" is tried first. A line containing both would otherwise be read as
     * money coming in when it went out, and being wrong about the direction is worse than
     * missing the line entirely.
     */
    public static Payment read(String line, long at) {
        if (line == null || line.isEmpty()) return null;
        String clean = strip(line);

        Matcher out = OUT.matcher(clean);
        if (out.find()) {
            Long cents = amount(out.group(2), out.group(3));
            return cents == null ? null : new Payment(out.group(1), cents, false, at);
        }

        Matcher in = IN.matcher(clean);
        if (in.find()) {
            Long cents = amount(in.group(2), in.group(3));
            return cents == null ? null : new Payment(in.group(1), cents, true, at);
        }
        return null;
    }

    /**
     * Colour codes off, and a leading server tag off, and nothing else.
     *
     * <p>Only the outermost tag, and only from the front: stripping anywhere would let
     * "[x] you paid" be smuggled into the middle of somebody's chat message.
     */
    static String strip(String line) {
        String clean = line.replaceAll("\u00a7.", "").trim();
        Matcher tag = LEADING_TAG.matcher(clean);
        return tag.find() ? clean.substring(tag.end()).trim() : clean;
    }

    /**
     * "$1.5M", "1,500", "2b" -- in cents.
     *
     * <p>Returns nothing rather than guessing when the text is not a number this
     * understands. A tally that silently counts a misread line is worse than one with a
     * gap in it, because only one of them is visible.
     */
    static Long amount(String digits, String suffix) {
        String text = digits.replace(",", "");
        // Anything else malformed -- two decimal points, a stray character -- is left to
        // BigDecimal below, which refuses it. A second guard here only reads as though it
        // were doing something.
        if (text.isEmpty()) return null;

        long multiplier = switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            case "t" -> 1_000_000_000_000L;
            default -> 1L;
        };

        try {
            java.math.BigDecimal value = new java.math.BigDecimal(text)
                    .multiply(java.math.BigDecimal.valueOf(multiplier))
                    .multiply(java.math.BigDecimal.valueOf(100));
            // Half-up, and only ever at the cent: a fraction of a cent cannot be paid.
            java.math.BigDecimal cents = value.setScale(0, java.math.RoundingMode.HALF_UP);
            if (cents.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return null;
            long result = cents.longValueExact();
            return result <= 0 ? null : result;
        } catch (ArithmeticException | NumberFormatException notANumber) {
            return null;
        }
    }

    /** Cents back into something to read. */
    public static String money(long cents) {
        long whole = Math.abs(cents) / 100;
        long part = Math.abs(cents) % 100;
        String sign = cents < 0 ? "-" : "";
        String body = String.format("%,d", whole);
        return sign + "$" + body + (part == 0 ? "" : String.format(".%02d", part));
    }

    // ------------------------------------------------------------------- a session

    /** One sitting: everything since you pressed start. */
    public static final class Session {
        public final long started;
        public long ended;
        public final List<Payment> payments = new ArrayList<>();

        public Session(long started) {
            this.started = started;
        }

        public long in() {
            long total = 0;
            for (Payment payment : this.payments) if (payment.incoming) total += payment.cents;
            return total;
        }

        public long out() {
            long total = 0;
            for (Payment payment : this.payments) if (!payment.incoming) total += payment.cents;
            return total;
        }

        public long net() {
            return in() - out();
        }

        public int wins() {
            int count = 0;
            for (Payment payment : this.payments) if (payment.incoming) count++;
            return count;
        }

        public int losses() {
            return this.payments.size() - wins();
        }

        /**
         * How many payments out in a row, right now.
         *
         * <p>The number the alert watches. Counted from the end, because what matters is
         * what is happening rather than what happened.
         */
        public int lossStreak() {
            int run = 0;
            for (int i = this.payments.size() - 1; i >= 0; i--) {
                if (this.payments.get(i).incoming) break;
                run++;
            }
            return run;
        }

        public int winStreak() {
            int run = 0;
            for (int i = this.payments.size() - 1; i >= 0; i--) {
                if (!this.payments.get(i).incoming) break;
                run++;
            }
            return run;
        }

        /** The longest run either way this session, for the record rather than the alert. */
        public int worstLossRun() {
            return longestRun(false);
        }

        public int bestWinRun() {
            return longestRun(true);
        }

        private int longestRun(boolean incoming) {
            int best = 0;
            int run = 0;
            for (Payment payment : this.payments) {
                if (payment.incoming == incoming) {
                    run++;
                    best = Math.max(best, run);
                } else {
                    run = 0;
                }
            }
            return best;
        }

        /**
         * What each player has sent you, and what a rake of this many basis points owes
         * them back.
         *
         * <p>Basis points rather than a percentage, so 12.5% is a whole number here and
         * not a rounding decision taken twice.
         */
        public Map<String, long[]> rakeback(int basisPoints) {
            Map<String, long[]> owed = new LinkedHashMap<>();
            for (Payment payment : this.payments) {
                if (!payment.incoming) continue;
                long[] row = owed.computeIfAbsent(payment.player, key -> new long[2]);
                row[0] += payment.cents;
            }
            for (long[] row : owed.values()) {
                row[1] = Math.round(row[0] * (basisPoints / 10000.0));
            }
            return owed;
        }
    }
}
