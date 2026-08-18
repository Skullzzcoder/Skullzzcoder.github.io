package dev.skullzz.donutflipper.backtest;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.pricing.Statistics;
import dev.skullzz.donutflipper.pricing.Valuation;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.scan.FlipCandidate;
import dev.skullzz.donutflipper.scan.FlipScanner;
import dev.skullzz.donutflipper.store.Database;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the only question that matters before risking coins: would this have
 * worked?
 *
 * <p>Rewinds to a past moment, values every item using <em>only</em> sales from
 * before that moment, runs the real scanner over the listings that were live
 * then, and checks each recommendation against what the market actually did
 * afterwards.
 *
 * <p>The strict before/after split is the whole point. It is trivially easy to
 * build a backtest that looks spectacular by letting future sales leak into the
 * valuation -- the tool then "predicts" prices it was shown. Every valuation
 * here is cut off at the replay instant, so a good result means the signal was
 * genuinely available at the time you would have had to act on it.
 */
public final class Replay {

    private final Database db;
    private final Valuator valuator;
    private final FlipScanner scanner;

    public Replay(Database db, Valuator valuator, FlipScanner scanner) {
        this.db = db;
        this.valuator = valuator;
        this.scanner = scanner;
    }

    /**
     * @param evaluated     flips the scanner proposed
     * @param resolved      flips whose item traded again, so the outcome is known
     * @param wins          resolved flips that would have cleared a profit
     * @param hitRate       wins / resolved
     * @param predictedRoi  mean ROI the scanner claimed
     * @param realisedRoi   mean ROI using the price the market actually paid
     * @param medianHours   median time until the item next traded
     */
    public record Report(
            int evaluated, int resolved, int wins,
            double hitRate, double predictedRoi, double realisedRoi, double medianHours
    ) {
        /**
         * Whether the numbers justify trusting the tool with money. Deliberately
         * strict: a hit rate below 60% means more than a third of the flips it is
         * confident about lose, and realised ROI must actually be positive --
         * a high hit rate on tiny wins that a few large losses erase is not a
         * working strategy.
         */
        public boolean trustworthy() {
            return resolved >= 20 && hitRate >= 0.60 && realisedRoi > 0.05;
        }

        @Override
        public String toString() {
            return """
                    Backtest report
                      flips proposed     : %d
                      outcome known      : %d
                      profitable         : %d
                      hit rate           : %.1f%%
                      predicted mean ROI : %.1f%%
                      realised mean ROI  : %.1f%%
                      median time to sell: %.1f h
                      verdict            : %s
                    """.formatted(evaluated, resolved, wins, hitRate * 100,
                    predictedRoi * 100, realisedRoi * 100, medianHours,
                    trustworthy() ? "usable" : "NOT yet trustworthy - collect more data or tighten the profile");
        }
    }

    /**
     * @param at      the instant to rewind to
     * @param horizon how long after {@code at} to look for the outcome
     */
    public Report run(Instant at, Duration horizon, Profile profile) throws SQLException {
        List<Listing> live = db.listingsActiveAt(at);
        Map<String, Valuation> asOf = valuationsAsOf(live, at);

        List<FlipCandidate> proposed = scanner.scan(live, asOf::get, profile);

        List<Double> realisedRois = new ArrayList<>();
        List<Double> predictedRois = new ArrayList<>();
        List<Double> hoursToSell = new ArrayList<>();
        int wins = 0;
        int resolved = 0;

        for (FlipCandidate c : proposed) {
            String key = c.listing().key().exact();
            // What the item actually fetched after the replay instant.
            List<Sale> after = db.salesForKeyBetween(key, at, at.plus(horizon));
            if (after.isEmpty()) {
                // No trade inside the horizon. Not a loss exactly, but capital
                // that stayed tied up -- counted as unresolved, not as a win.
                continue;
            }
            resolved++;

            double actualUnit = Statistics.median(
                    after.stream().mapToDouble(Sale::unitPrice).toArray());
            double gross = actualUnit * c.listing().item().count();
            double net = gross * (1.0 - scanner.taxRate()) - c.buyPrice();
            double roi = net / c.buyPrice();

            realisedRois.add(roi);
            predictedRois.add(c.roi());
            if (net > 0) {
                wins++;
            }

            Instant firstAfter = after.stream().map(Sale::soldAt)
                    .min(Instant::compareTo).orElse(at);
            hoursToSell.add(Duration.between(at, firstAfter).toMinutes() / 60.0);
        }

        return new Report(
                proposed.size(),
                resolved,
                wins,
                resolved == 0 ? 0 : (double) wins / resolved,
                mean(predictedRois),
                mean(realisedRois),
                Statistics.median(hoursToSell.stream().mapToDouble(Double::doubleValue).toArray()));
    }

    /** Values every key present in the live listings, using only prior sales. */
    private Map<String, Valuation> valuationsAsOf(List<Listing> live, Instant at)
            throws SQLException {
        Map<String, Valuation> out = new HashMap<>();
        for (Listing l : live) {
            String key = l.key().exact();
            if (out.containsKey(key)) {
                continue;
            }
            List<Sale> priorOnly =
                    db.salesForKeyBetween(key, valuator.windowStart(at), at);
            out.put(key, valuator.valuate(key, priorOnly, at));
        }
        return out;
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }
}
