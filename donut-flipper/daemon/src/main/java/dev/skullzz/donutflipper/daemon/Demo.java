package dev.skullzz.donutflipper.daemon;

import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.fixture.MarketSimulator;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.pricing.Valuation;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.scan.FlipScanner;
import dev.skullzz.donutflipper.service.FlipService;
import dev.skullzz.donutflipper.service.ValuationService;
import dev.skullzz.donutflipper.store.Database;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Runs the full pipeline against a simulated auction house.
 *
 * <p>Two jobs. First, it lets the whole system be exercised before an API key
 * exists. Second -- and this is the part that keeps mattering afterwards -- the
 * simulated market has known true prices, so the output can be checked against
 * ground truth. Against live data you can never tell a correct valuation from a
 * confidently wrong one; here you can.
 */
final class Demo {

    static void run() throws Exception {
        Instant now = Instant.now();
        MarketSimulator sim = new MarketSimulator(20260818L);
        List<MarketSimulator.Archetype> archetypes = MarketSimulator.defaultArchetypes();

        System.out.println("Simulated market, true prices per unit:");
        for (MarketSimulator.Archetype a : archetypes) {
            System.out.printf("  %-32s %,12.0f  (%.1f sales/day)%n",
                    a.item().materialId(), a.trueUnit(), a.salesPerDay());
        }
        System.out.println();

        try (Database db = Database.openInMemory()) {
            List<Sale> sales = sim.generateSales(archetypes, now, Duration.ofDays(7));

            // Plant the attack on the item a real attacker would target: the one
            // with thin liquidity and a high price, where few honest sales exist
            // to dilute the fake ones.
            MarketSimulator.Archetype target = archetypes.get(4);
            List<Sale> wash = sim.plantWashTrades(target, now, 5, 4.0);
            sales = new java.util.ArrayList<>(sales);
            sales.addAll(wash);

            db.insertSales(sales);
            db.upsertListings(sim.generateListings(archetypes, now, 6, 1, 0.45));

            System.out.printf("Seeded %,d sales (including %d planted wash trades) "
                            + "and %,d listings%n%n",
                    sales.size(), wash.size(), db.activeListings().size());

            ValuationService valuations = new ValuationService(db, new Valuator());
            valuations.refresh(now);

            System.out.println("Valuations vs ground truth:");
            System.out.printf("  %-32s %12s %12s %8s %6s%n",
                    "ITEM", "TRUE", "ESTIMATED", "ERROR", "CONF");
            for (MarketSimulator.Archetype a : archetypes) {
                String key = a.item().displayName().isBlank()
                        ? a.item().materialId().replace("minecraft:", "")
                        : null;
                Valuation v = valuations.get(dev.skullzz.donutflipper.model.ItemKey
                        .of(a.item()).exact());
                double error = a.trueUnit() == 0 ? 0
                        : Math.abs(v.fairUnitPrice() - a.trueUnit()) / a.trueUnit() * 100;
                System.out.printf("  %-32s %,12.0f %,12.0f %7.1f%% %6s%n",
                        describe(a), a.trueUnit(), v.fairUnitPrice(), error,
                        v.confidence().name());
            }

            System.out.println();
            System.out.println("The wash-traded item above was pumped at 4x by a fake");
            System.out.println("counterparty pair. Its estimate should still sit near truth.");
            System.out.println();

            FlipService flips = new FlipService(db, valuations,
                    new FlipScanner(0.05), Profile.BALANCED);
            System.out.println("Flips found (balanced profile):");
            DaemonMain.printFlips(flips.currentFlips());
        }
    }

    /** Distinguishes the bare and enchanted sword, which share a material id. */
    private static String describe(MarketSimulator.Archetype a) {
        String base = a.item().materialId().replace("minecraft:", "");
        return a.item().enchantments().isEmpty() ? base : base + " (enchanted)";
    }

    private Demo() {
    }
}
