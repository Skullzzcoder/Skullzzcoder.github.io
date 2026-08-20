package dev.skullzz.donutflipper.daemon;

import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.api.RateLimiter;
import dev.skullzz.donutflipper.backtest.Replay;
import dev.skullzz.donutflipper.config.FlipperConfig;
import dev.skullzz.donutflipper.config.Profile;
import dev.skullzz.donutflipper.ingest.AuctionPoller;
import dev.skullzz.donutflipper.pricing.Valuator;
import dev.skullzz.donutflipper.scan.FlipCandidate;
import dev.skullzz.donutflipper.scan.FlipScanner;
import dev.skullzz.donutflipper.service.FlipService;
import dev.skullzz.donutflipper.service.ValuationService;
import dev.skullzz.donutflipper.store.Database;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Entry point for the headless collector.
 *
 * <pre>
 *   java -jar daemon-all.jar set-key    store your API key and verify it works
 *   java -jar daemon-all.jar where      print where the config file lives
 *   java -jar daemon-all.jar probe      inspect the API and lock the schema
 *   java -jar daemon-all.jar doctor     diagnose a live setup and say what to fix
 *   java -jar daemon-all.jar collect    run the collector (the normal mode)
 *   java -jar daemon-all.jar scan       print current flips and exit
 *   java -jar daemon-all.jar backtest   replay history and report whether this works
 *   java -jar daemon-all.jar demo       run the whole pipeline on simulated data
 * </pre>
 *
 * <p>{@code collect} is the mode that matters and the one that has to run
 * unattended for days. Price history is the foundation of every valuation, and
 * it can only be gathered by watching continuously -- including the hours you
 * are not playing. Starting the collector days before you start trading is not
 * impatience to be worked around; it is the actual prerequisite.
 */
public final class DaemonMain {

    private static final Logger LOG = Logger.getLogger(DaemonMain.class.getName());

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0].toLowerCase() : "collect";

        switch (command) {
            case "set-key", "setkey" -> SetKey.run(args);
            case "where", "config" -> SetKey.where();
            case "probe" -> Probe.run();
            case "doctor" -> Doctor.run();
            case "collect" -> collect();
            case "scan" -> scanOnce();
            case "backtest" -> backtest();
            case "demo" -> Demo.run();
            default -> {
                System.err.println("Unknown command: " + command);
                System.err.println("Use one of: set-key, where, probe, doctor, "
                        + "collect, scan, backtest, demo");
                System.exit(2);
            }
        }
    }

    /** Long-running collection loop plus the local API the mod reads. */
    private static void collect() throws Exception {
        FlipperConfig config = FlipperConfig.load();
        requireApiKey(config);

        Database db = Database.open(FlipperConfig.databaseFile());
        RateLimiter limiter = new RateLimiter(250 * config.rateLimitUtilisation());
        DonutApiClient client = new DonutApiClient(config.apiKey(), limiter, config.apiBaseUrl());
        AuctionPoller poller = new AuctionPoller(client, db);

        ValuationService valuations = new ValuationService(db, new Valuator());
        FlipService flips = new FlipService(db, valuations,
                new FlipScanner(config.auctionTaxRate()),
                Profile.byName(config.activeProfile()));

        LocalServer server = new LocalServer(flips, config.localPort());
        server.start();

        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);

        pool.scheduleAtFixedRate(() -> guard("transaction sweep", () -> {
            // Transactions first and more often than listings: sale history is the
            // scarcer, more valuable signal, and a missed listing sweep costs one
            // minute of visibility while a missed sale is gone for good.
            AuctionPoller.SweepResult r = poller.sweepTransactions();
            LOG.info("sales: " + r.stored() + " new across " + r.pages() + " pages");
        }), 0, config.transactionPollSeconds(), TimeUnit.SECONDS);

        pool.scheduleAtFixedRate(() -> guard("listing sweep", () -> {
            AuctionPoller.SweepResult r = poller.sweepListings();
            LOG.info("listings: " + r.records() + " across " + r.pages() + " pages");
        }), 5, config.listingPollSeconds(), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down");
            server.stop();
            pool.shutdownNow();
            try {
                db.close();
            } catch (Exception ignored) {
                // Process is ending; a failure to close cleanly changes nothing.
            }
        }));

        System.out.println("""
                Collector running. Leave this process up.

                  database : %s
                  local API: http://127.0.0.1:%d/flips
                  profile  : %s

                Valuations need sale history to exist. Expect roughly nothing useful
                for the first day, and do not trade on it until `backtest` says the
                numbers hold up.
                """.formatted(FlipperConfig.databaseFile(), config.localPort(),
                config.activeProfile()));

        Thread.currentThread().join();
    }

    /** One-shot scan against already-collected data. */
    private static void scanOnce() throws Exception {
        FlipperConfig config = FlipperConfig.load();
        try (Database db = Database.open(FlipperConfig.databaseFile())) {
            ValuationService valuations = new ValuationService(db, new Valuator());
            int valued = valuations.refresh(Instant.now());

            FlipService flips = new FlipService(db, valuations,
                    new FlipScanner(config.auctionTaxRate()),
                    Profile.byName(config.activeProfile()));

            System.out.printf("%,d listings active, %,d items valued, profile=%s%n%n",
                    db.activeListings().size(), valued, config.activeProfile());
            printFlips(flips.currentFlips());
        }
    }

    private static void backtest() throws Exception {
        FlipperConfig config = FlipperConfig.load();
        try (Database db = Database.open(FlipperConfig.databaseFile())) {
            Replay replay = new Replay(db, new Valuator(),
                    new FlipScanner(config.auctionTaxRate()));

            // Rewind far enough that the horizon after it is also in the past,
            // otherwise every flip would be scored as "no outcome yet".
            Instant at = Instant.now().minus(Duration.ofDays(2));
            System.out.println(replay.run(at, Duration.ofDays(1),
                    Profile.byName(config.activeProfile())));
        }
    }

    static void printFlips(List<FlipCandidate> flips) {
        if (flips.isEmpty()) {
            System.out.println("No flips found. Usually this means not enough sale history yet.");
            return;
        }
        System.out.printf("%-30s %12s %12s %12s %8s %7s %6s%n",
                "ITEM", "BUY", "VALUE", "PROFIT", "ROI", "SALES/D", "CONF");
        System.out.println("-".repeat(95));
        for (FlipCandidate c : flips.subList(0, Math.min(25, flips.size()))) {
            System.out.printf("%-30.30s %,12d %,12.0f %,12d %7.0f%% %7.1f %6s%n",
                    c.itemName().isBlank() ? c.listing().item().materialId() : c.itemName(),
                    c.buyPrice(),
                    c.grossResale(),
                    c.netProfit(),
                    c.roi() * 100,
                    c.valuation().salesPerDay(),
                    c.valuation().confidence().name());
        }
    }

    private static void requireApiKey(FlipperConfig config) {
        if (!config.hasApiKey()) {
            System.err.println("""
                    No DonutSMP API key configured.

                    Run /api in game (a linked Discord account is required), then either:
                      export DONUTSMP_API_KEY=your_key
                    or set apiKey in %s

                    To try the pipeline without a key: java -jar daemon-all.jar demo
                    """.formatted(FlipperConfig.configFile()));
            System.exit(1);
        }
    }

    /** Keeps one failed sweep from killing the scheduled task permanently. */
    private static void guard(String label, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            // A scheduled task that throws is silently cancelled by the executor,
            // which would stop collection without any obvious symptom.
            LOG.warning(label + " failed: " + e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private DaemonMain() {
    }
}
