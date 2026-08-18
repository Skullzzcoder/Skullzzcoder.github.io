package dev.skullzz.donutflipper.fixture;

import dev.skullzz.donutflipper.model.AuctionItem;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a synthetic auction house.
 *
 * <p>This exists so the entire analysis pipeline can be built and tested before
 * an API key is available, and -- more importantly -- so correctness can be
 * checked against a market whose true prices are known. Against live data there
 * is no ground truth: if the scanner flags something, you cannot tell whether it
 * found a real mispricing or simply mis-valued the item. Here the true value is
 * an input, so a test can assert that a planted 60%-off listing is found and
 * that a planted wash-trading scheme is <em>not</em> believed.
 *
 * <p>Seeded, so failures reproduce.
 */
public final class MarketSimulator {

    /**
     * One tradeable item with a known true price.
     *
     * @param item        the item itself
     * @param trueUnit    true clearing price per unit
     * @param salesPerDay how often it trades
     * @param spread      price dispersion as a fraction of trueUnit
     */
    public record Archetype(AuctionItem item, double trueUnit, double salesPerDay, double spread) {
    }

    private static final String[] SELLERS = {
            "Alex", "Steve", "Nova", "Kai", "Rhea", "Milo", "Juno", "Pax",
            "Wren", "Zephyr", "Iris", "Odin", "Vale", "Sage", "Onyx"
    };

    private final Random random;

    public MarketSimulator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * A representative slice of a DonutSMP-like market: dense cheap commodities,
     * sparse expensive gear, and the enchanted/bare pair that keying has to
     * distinguish.
     */
    public static List<Archetype> defaultArchetypes() {
        AuctionItem bareSword = new AuctionItem(
                "minecraft:netherite_sword", "Netherite Sword", 1,
                Map.of(), null, 0, 2031, List.of());

        AuctionItem godSword = new AuctionItem(
                "minecraft:netherite_sword", "Netherite Sword", 1,
                Map.of("sharpness", 5, "mending", 1, "unbreaking", 3),
                null, 0, 2031, List.of());

        return List.of(
                // High-volume commodities: deep history, tight spreads.
                new Archetype(AuctionItem.simple("minecraft:diamond", 64), 1_200, 40.0, 0.10),
                new Archetype(AuctionItem.simple("minecraft:ancient_debris", 16), 9_500, 12.0, 0.14),
                new Archetype(AuctionItem.simple("minecraft:emerald", 64), 800, 25.0, 0.12),

                // The pair that breaks naive keying. Same material, ~9x apart.
                new Archetype(bareSword, 45_000, 3.0, 0.18),
                new Archetype(godSword, 400_000, 1.5, 0.22),

                // Illiquid whale item: real margins, but it barely moves.
                new Archetype(AuctionItem.simple("minecraft:elytra", 1), 1_800_000, 0.25, 0.30));
    }

    /** Sale history over the window, with prices scattered around each true value. */
    public List<Sale> generateSales(List<Archetype> archetypes, Instant now, Duration window) {
        List<Sale> sales = new ArrayList<>();
        double windowDays = window.toSeconds() / 86400.0;
        int saleId = 0;

        for (Archetype a : archetypes) {
            int count = (int) Math.round(a.salesPerDay() * windowDays);
            for (int i = 0; i < count; i++) {
                double unit = jitter(a.trueUnit(), a.spread());
                long total = Math.max(1, Math.round(unit * a.item().count()));
                // Spread uniformly across the window rather than clustering, so
                // trend detection has a flat baseline to measure against.
                Instant soldAt = now.minusSeconds((long) (random.nextDouble() * window.toSeconds()));
                sales.add(new Sale(
                        "sale-" + (saleId++),
                        pickSeller(),
                        pickSeller(),
                        total,
                        a.item(),
                        soldAt));
            }
        }
        return sales;
    }

    /**
     * Live listings: mostly priced at or above fair value, plus a controlled
     * number of genuine bargains.
     *
     * @param bargainsPerArchetype how many underpriced listings to plant
     * @param bargainDiscount      how far below fair value to plant them (0.4 = 40% off)
     */
    public List<Listing> generateListings(List<Archetype> archetypes, Instant now,
                                          int normalPerArchetype, int bargainsPerArchetype,
                                          double bargainDiscount) {
        List<Listing> listings = new ArrayList<>();
        int id = 0;

        for (Archetype a : archetypes) {
            for (int i = 0; i < normalPerArchetype; i++) {
                // Sellers ask at or above fair value; the listing feed always
                // skews high because the cheap ones keep getting bought.
                double unit = a.trueUnit() * (1.0 + Math.abs(random.nextGaussian()) * 0.15);
                listings.add(listing("listing-" + (id++), a, unit, now));
            }
            for (int i = 0; i < bargainsPerArchetype; i++) {
                double unit = a.trueUnit() * (1.0 - bargainDiscount);
                listings.add(listing("bargain-" + a.item().materialId() + "-" + (id++), a, unit, now));
            }
        }
        return listings;
    }

    /**
     * Plants a wash-trading scheme: one seller repeatedly selling to one buyer at
     * a wildly inflated price, to make an item look far more valuable than it is.
     *
     * <p>This is the attack the filter has to survive. A test that plants this and
     * then asserts the valuation stayed near the true value is the only real proof
     * the defence works.
     */
    public List<Sale> plantWashTrades(Archetype archetype, Instant now, int trades,
                                      double inflationMultiple) {
        List<Sale> fake = new ArrayList<>();
        String attacker = "Scammer";
        String alt = "ScammerAlt";
        for (int i = 0; i < trades; i++) {
            double unit = archetype.trueUnit() * inflationMultiple;
            fake.add(new Sale(
                    "wash-" + i,
                    attacker,
                    alt,
                    Math.round(unit * archetype.item().count()),
                    archetype.item(),
                    now.minusSeconds(600L * i)));
        }
        return fake;
    }

    private Listing listing(String id, Archetype a, double unitPrice, Instant now) {
        long total = Math.max(1, Math.round(unitPrice * a.item().count()));
        return new Listing(
                id,
                pickSeller(),
                total,
                a.item(),
                now.minusSeconds(random.nextInt(3600)),
                now);
    }

    /** Log-normal-ish scatter: prices cannot go negative and the tail leans high. */
    private double jitter(double base, double spread) {
        return Math.max(1.0, base * Math.exp(random.nextGaussian() * spread));
    }

    private String pickSeller() {
        return SELLERS[random.nextInt(SELLERS.length)];
    }
}
