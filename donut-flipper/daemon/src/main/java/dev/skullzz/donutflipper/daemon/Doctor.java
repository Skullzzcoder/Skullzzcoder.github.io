package dev.skullzz.donutflipper.daemon;

import com.google.gson.JsonElement;
import dev.skullzz.donutflipper.api.ApiMapper;
import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.api.RateLimiter;
import dev.skullzz.donutflipper.config.FlipperConfig;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;
import dev.skullzz.donutflipper.pricing.Confidence;
import dev.skullzz.donutflipper.pricing.Valuation;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.service.ValuationService;
import dev.skullzz.donutflipper.store.Database;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Diagnoses a live setup and says what to do next.
 *
 * <p>Exists because every failure in this system looks the same from the
 * outside: an empty flip list. A wrong API key, mis-guessed field names, a
 * collector that has only been running an hour, and a genuinely quiet market all
 * present identically. Guessing between them wastes days. This tells you which
 * one you have.
 *
 * <pre>{@code java -jar daemon-all.jar doctor}</pre>
 */
final class Doctor {

    private static final String PASS = "[ ok ]";
    private static final String WARN = "[warn]";
    private static final String FAIL = "[FAIL]";

    private int failures = 0;
    private int warnings = 0;

    static void run() throws Exception {
        new Doctor().diagnose();
    }

    private void diagnose() throws Exception {
        System.out.println("Donut Flipper -- diagnostics");
        System.out.println("=".repeat(72));

        FlipperConfig config = checkConfig();
        boolean apiOk = config != null && checkApi(config);
        checkDatabase(config, apiOk);

        System.out.println("=".repeat(72));
        if (failures > 0) {
            System.out.println(failures + " problem(s) to fix before this can work.");
        } else if (warnings > 0) {
            System.out.println("Working, with " + warnings + " thing(s) worth knowing.");
        } else {
            System.out.println("Everything checks out.");
        }
    }

    // ------------------------------------------------------------------

    private FlipperConfig checkConfig() {
        System.out.println("\nCONFIGURATION");
        try {
            FlipperConfig config = FlipperConfig.load();

            if (Files.exists(FlipperConfig.configFile())) {
                pass("config file", FlipperConfig.configFile().toString());
            } else {
                pass("config file", "created a default at " + FlipperConfig.configFile());
            }

            if (config.hasApiKey()) {
                String key = config.apiKey();
                // Never print the key. Fingerprint enough to confirm which one is
                // loaded when there is more than one place it could come from.
                pass("api key", "present (" + key.length() + " chars, ends ..."
                        + key.substring(Math.max(0, key.length() - 4)) + ")");
            } else {
                fail("api key", "missing -- run /api in game, then set DONUTSMP_API_KEY "
                        + "or apiKey in the config file");
                return config;
            }

            pass("auction tax", String.format("%.1f%% (unverified assumption -- "
                    + "see docs/OPEN-QUESTIONS.md)", config.auctionTaxRate() * 100));
            pass("rate budget", Math.round(250 * config.rateLimitUtilisation())
                    + " req/min of the 250 ceiling");
            return config;

        } catch (Exception e) {
            fail("config", e.toString());
            return null;
        }
    }

    private boolean checkApi(FlipperConfig config) {
        System.out.println("\nAPI");
        if (!config.hasApiKey()) {
            fail("connectivity", "skipped, no key");
            return false;
        }

        RateLimiter limiter = new RateLimiter(250 * config.rateLimitUtilisation());
        DonutApiClient client = new DonutApiClient(config.apiKey(), limiter, config.apiBaseUrl(),
                    config.requestTimeoutSeconds());
        Instant now = Instant.now();

        JsonElement listingRoot;
        try {
            listingRoot = client.auctionList(1);
            pass("auction/list", "reachable");
        } catch (DonutApiClient.ApiException e) {
            fail("auction/list", e.getMessage());
            // statusCode 0 is our marker for "no reply at all", as opposed to a
            // real HTTP status. That distinction matters: a rejection is
            // something you fix here, silence is not.
            if (e.statusCode() == 0) {
                System.out.println("       The server took the request and never answered.");
                System.out.println("       Run `net-test`: if TLS completes and the control host");
                System.out.println("       responds, the API is down and nothing here is wrong.");
            }
            return false;
        } catch (java.net.http.HttpTimeoutException e) {
            fail("auction/list", "no response within the timeout");
            System.out.println("       Run `net-test` to confirm whether this is server-side.");
            return false;
        } catch (Exception e) {
            fail("auction/list", "unreachable: " + e);
            return false;
        }

        ApiMapper.Result<Listing> listings = ApiMapper.parseListings(listingRoot, now);
        if (listings.records().isEmpty()) {
            fail("listing mapping", "parsed 0 records -- the field aliases in "
                    + "ApiMapper are wrong. Run `probe` and check the saved JSON.");
        } else if (!listings.healthy()) {
            fail("listing mapping", "parsed " + listings.records().size()
                    + " but skipped " + listings.skipped() + " -- aliases likely wrong");
        } else {
            pass("listing mapping", listings.records().size() + " parsed, "
                    + listings.skipped() + " skipped");
            reportEnchantmentCoverage(listings.records());
        }

        try {
            JsonElement salesRoot = client.auctionTransactions(1);
            ApiMapper.Result<Sale> sales = ApiMapper.parseSales(salesRoot, now);

            if (sales.records().isEmpty()) {
                fail("sale mapping", "parsed 0 records -- without sale history "
                        + "nothing can ever be valued");
            } else {
                pass("sale mapping", sales.records().size() + " parsed, "
                        + sales.skipped() + " skipped");

                long withBuyer = sales.records().stream().filter(Sale::hasKnownBuyer).count();
                if (withBuyer == 0) {
                    warn("buyer identity", "not exposed -- wash-trade detection falls back "
                            + "to outlier trimming alone (weaker, still functional)");
                } else {
                    pass("buyer identity", withBuyer + "/" + sales.records().size()
                            + " -- counterparty analysis available");
                }
            }
        } catch (Exception e) {
            fail("auction/transactions", String.valueOf(e.getMessage()));
        }
        return true;
    }

    /**
     * The single most consequential unknown. If listings carry no enchantment
     * data, exact keying is impossible, everything collapses to material-level
     * pricing, and the workable strategy shifts away from gear entirely.
     */
    private void reportEnchantmentCoverage(List<Listing> listings) {
        long enchantable = listings.stream()
                .filter(l -> l.item().isDamageable() || l.item().materialId().matches(
                        ".*(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots|bow|trident|elytra).*"))
                .count();
        long withEnchants = listings.stream()
                .filter(l -> !l.item().enchantments().isEmpty())
                .count();

        if (enchantable == 0) {
            warn("enchantment data", "no gear on this page, cannot tell yet -- re-run later");
        } else if (withEnchants == 0) {
            fail("enchantment data", enchantable + " gear listings and none carry "
                    + "enchantments. Exact keying is impossible; pricing degrades to "
                    + "material level. See docs/OPEN-QUESTIONS.md item 4.");
        } else {
            pass("enchantment data", withEnchants + " listing(s) carry enchantments -- "
                    + "exact keying works");
        }
    }

    // ------------------------------------------------------------------

    private void checkDatabase(FlipperConfig config, boolean apiOk) throws Exception {
        System.out.println("\nCOLLECTED DATA");

        if (!Files.exists(FlipperConfig.databaseFile())) {
            warn("database", "not created yet -- run `collect` to start gathering history");
            return;
        }

        try (Database db = Database.open(FlipperConfig.databaseFile())) {
            long listings = db.countRows("listings");
            long sales = db.countRows("sales");

            pass("database", FlipperConfig.databaseFile() + " ("
                    + Files.size(FlipperConfig.databaseFile()) / 1024 + " KB)");
            pass("listings", String.format("%,d rows (%,d active)",
                    listings, db.activeListings().size()));

            if (sales == 0) {
                fail("sale history", "no sales recorded. Nothing can be valued without "
                        + "this -- it is the foundation everything else rests on.");
                return;
            }
            pass("sale history", String.format("%,d rows", sales));

            Instant now = Instant.now();
            ValuationService valuations = new ValuationService(db, new Valuator());
            int valued = valuations.refresh(now);

            long confident = 0;
            for (String key : db.keysWithSales(now.minus(Duration.ofDays(7)))) {
                Valuation v = valuations.get(key);
                if (v.confidence().atLeast(Confidence.HIGH)) {
                    confident++;
                }
            }

            pass("items valued", valued + " distinct item keys");

            if (confident == 0) {
                warn("confident valuations", "none yet. This is normal early on -- "
                        + "high confidence needs 8+ sales from 3+ distinct sellers.");
                System.out.println("       Keep the collector running. Expect roughly a day "
                        + "before commodities firm up.");
            } else {
                pass("confident valuations", confident + " item(s) ready to trade on");
            }

            if (confident < 5) {
                warn("readiness", "not enough confident valuations to trust yet. "
                        + "Run `backtest` once this reaches a few dozen.");
            }
        }
    }

    // ------------------------------------------------------------------

    private void pass(String label, String detail) {
        System.out.printf("  %s %-22s %s%n", PASS, label, detail);
    }

    private void warn(String label, String detail) {
        warnings++;
        System.out.printf("  %s %-22s %s%n", WARN, label, detail);
    }

    private void fail(String label, String detail) {
        failures++;
        System.out.printf("  %s %-22s %s%n", FAIL, label, detail);
    }

    private Doctor() {
    }
}
