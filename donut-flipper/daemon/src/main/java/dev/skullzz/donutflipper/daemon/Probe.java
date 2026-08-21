package dev.skullzz.donutflipper.daemon;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skullzz.donutflipper.api.ApiMapper;
import dev.skullzz.donutflipper.api.DonutApiClient;
import dev.skullzz.donutflipper.api.RateLimiter;
import dev.skullzz.donutflipper.config.FlipperConfig;
import dev.skullzz.donutflipper.model.Listing;
import dev.skullzz.donutflipper.model.Sale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema discovery. Run this once, the moment the API key is available, before
 * trusting anything else.
 *
 * <p>It fetches one page from each endpoint, saves the raw JSON, prints the
 * field inventory, and then reports whether {@link ApiMapper}'s alias lists
 * actually matched. That last check is the point of the whole tool: a mapper
 * that silently maps nothing produces an empty database that looks exactly like
 * "the market is quiet", and you would not notice for days.
 *
 * <pre>{@code ./gradlew :daemon:run --args="probe"}</pre>
 */
public final class Probe {

    private static final com.google.gson.Gson PRETTY =
            new GsonBuilder().setPrettyPrinting().create();

    public static void run() throws Exception {
        FlipperConfig config = FlipperConfig.load();

        if (!config.hasApiKey()) {
            System.err.println("""
                    No API key found.

                    Get one by running /api in game on DonutSMP (you need a linked
                    Discord account), then either:
                      export DONUTSMP_API_KEY=your_key_here
                    or put it in the apiKey field of:
                      %s
                    """.formatted(FlipperConfig.configFile()));
            return;
        }

        Path outDir = FlipperConfig.configDir().resolve("probe");
        Files.createDirectories(outDir);

        RateLimiter limiter = new RateLimiter(250 * config.rateLimitUtilisation());
        DonutApiClient client = new DonutApiClient(config.apiKey(), limiter, config.apiBaseUrl(),
                    config.requestTimeoutSeconds());

        System.out.println("Probing DonutSMP API -> " + outDir);
        System.out.println();

        JsonElement listings = probeEndpoint(client, "auction/list", outDir, "/auction/list/1");
        JsonElement sales = probeEndpoint(client, "auction/transactions", outDir, "/auction/transactions/1");

        System.out.println("=".repeat(70));
        System.out.println("MAPPER CHECK -- does ApiMapper actually understand these payloads?");
        System.out.println("=".repeat(70));

        if (listings != null) {
            ApiMapper.Result<Listing> r = ApiMapper.parseListings(listings, Instant.now());
            report("auction/list", r.records().size(), r.skipped());
            r.records().stream().limit(3).forEach(l -> System.out.printf(
                    "    %-28s x%-3d %,12d coins  (unit %,.0f)  key=%s%n",
                    l.item().materialId(), l.item().count(), l.price(),
                    l.unitPrice(), l.key().exact()));
        }

        if (sales != null) {
            ApiMapper.Result<Sale> r = ApiMapper.parseSales(sales, Instant.now());
            report("auction/transactions", r.records().size(), r.skipped());
            long withBuyer = r.records().stream().filter(Sale::hasKnownBuyer).count();
            // Whether buyers are exposed decides if wash-trade detection can work
            // on counterparty pairs or has to fall back to seller-side signals alone.
            System.out.printf("    buyer identity present on %d/%d sales%n",
                    withBuyer, r.records().size());
            r.records().stream().limit(3).forEach(s -> System.out.printf(
                    "    %-28s x%-3d %,12d coins  sold %s%n",
                    s.item().materialId(), s.item().count(), s.price(), s.soldAt()));
        }

        writeSchemaReport(outDir, listings, sales);

        System.out.println();
        System.out.println("Raw payloads saved. If the counts above are 0 or mostly skipped,");
        System.out.println("open the saved JSON and correct the alias arrays in ApiMapper.java.");
        System.out.println();
        System.out.println("A paste-ready summary is at " + outDir.resolve("schema-report.md"));
        System.out.println("It contains field names and one sample record. It does NOT contain");
        System.out.println("your API key -- safe to share when asking for help.");
    }

    /**
     * Writes a compact, shareable description of the wire format.
     *
     * <p>The raw dumps are large and awkward to read. This is the version worth
     * sending to someone else: structure and field names, one representative
     * record, and the mapper's verdict.
     *
     * <p>Contains no credentials. The key is never read into this file, and the
     * payloads themselves are public auction data.
     */
    private static void writeSchemaReport(Path outDir, JsonElement listings, JsonElement sales)
            throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# DonutSMP API schema report\n\n");
        md.append("Generated ").append(Instant.now()).append("\n\n");
        md.append("No credentials in this file.\n\n");

        appendEndpointSection(md, "auction/list", listings);
        appendEndpointSection(md, "auction/transactions", sales);

        md.append("## Mapper verdict\n\n");
        if (listings != null) {
            ApiMapper.Result<Listing> r = ApiMapper.parseListings(listings, Instant.now());
            md.append("- listings: parsed ").append(r.records().size())
                    .append(", skipped ").append(r.skipped()).append("\n");
        }
        if (sales != null) {
            ApiMapper.Result<Sale> r = ApiMapper.parseSales(sales, Instant.now());
            long withBuyer = r.records().stream().filter(Sale::hasKnownBuyer).count();
            md.append("- sales: parsed ").append(r.records().size())
                    .append(", skipped ").append(r.skipped()).append("\n");
            md.append("- buyer identity present: ").append(withBuyer)
                    .append("/").append(r.records().size()).append("\n");
        }

        Files.writeString(outDir.resolve("schema-report.md"), md.toString());
    }

    private static void appendEndpointSection(StringBuilder md, String label, JsonElement root) {
        md.append("## ").append(label).append("\n\n");
        if (root == null) {
            md.append("Request failed.\n\n");
            return;
        }

        List<JsonElement> records = new ArrayList<>();
        if (root.isJsonArray()) {
            md.append("Envelope: bare array\n\n");
            root.getAsJsonArray().forEach(records::add);
        } else if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            md.append("Envelope keys: `").append(o.keySet()).append("`\n\n");
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonArray()) {
                    md.append("Records live under: `").append(e.getKey()).append("`\n\n");
                    e.getValue().getAsJsonArray().forEach(records::add);
                    break;
                }
            }
        }

        md.append("Record count on page 1: ").append(records.size()).append("\n\n");
        if (records.isEmpty()) {
            md.append("_No records to describe._\n\n");
            return;
        }

        JsonObject first = Json0.asObject(records.get(0));
        if (first != null) {
            md.append("Record fields: `").append(first.keySet()).append("`\n\n");
            for (Map.Entry<String, JsonElement> e : first.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    md.append("- `").append(e.getKey()).append("` nested: `")
                            .append(e.getValue().getAsJsonObject().keySet()).append("`\n");
                }
            }
            md.append("\nSample record:\n\n```json\n")
                    .append(PRETTY.toJson(first)).append("\n```\n\n");
        }
    }

    /** Minimal local helper so this file does not depend on core's package-private Json. */
    private static final class Json0 {
        static JsonObject asObject(JsonElement el) {
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        }
    }

    private static void report(String endpoint, int parsed, int skipped) {
        String verdict = parsed == 0
                ? "  <-- MAPPER FAILED, fix the aliases in ApiMapper"
                : (skipped > parsed ? "  <-- mostly unmapped, aliases likely wrong" : "  ok");
        System.out.printf("%-24s parsed=%-5d skipped=%-5d%s%n", endpoint, parsed, skipped, verdict);
    }

    private static JsonElement probeEndpoint(DonutApiClient client, String label,
                                             Path outDir, String path) {
        try {
            JsonElement root = client.get(path);
            Path file = outDir.resolve(label.replace('/', '_') + ".json");
            Files.writeString(file, PRETTY.toJson(root));
            System.out.println("--- " + path + " ---");
            System.out.println("  saved: " + file);
            describe(root);
            System.out.println();
            return root;
        } catch (Exception e) {
            System.out.println("--- " + path + " ---");
            System.out.println("  FAILED: " + e.getMessage());
            System.out.println();
            return null;
        }
    }

    /** Prints the envelope shape and the field names of the first record. */
    private static void describe(JsonElement root) {
        if (root == null) {
            return;
        }
        if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            System.out.println("  envelope keys: " + o.keySet());
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue().isJsonArray()) {
                    JsonArray arr = e.getValue().getAsJsonArray();
                    System.out.printf("  '%s' is an array of %d%n", e.getKey(), arr.size());
                    if (!arr.isEmpty()) {
                        describeRecord(arr.get(0));
                    }
                    return;
                }
            }
        } else if (root.isJsonArray()) {
            JsonArray arr = root.getAsJsonArray();
            System.out.println("  bare array of " + arr.size());
            if (!arr.isEmpty()) {
                describeRecord(arr.get(0));
            }
        }
    }

    private static void describeRecord(JsonElement rec) {
        if (!rec.isJsonObject()) {
            return;
        }
        JsonObject o = rec.getAsJsonObject();
        System.out.println("  record fields: " + o.keySet());
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonObject()) {
                System.out.printf("    '%s' nested fields: %s%n",
                        e.getKey(), e.getValue().getAsJsonObject().keySet());
            }
        }
        System.out.println("  first record:");
        PRETTY.toJson(o).lines().limit(40)
                .forEach(line -> System.out.println("    " + line));
    }

    private Probe() {
    }

    /** Endpoints worth probing beyond the two the collector depends on. */
    static List<String> extraEndpoints() {
        return List.of("/auction/list/2", "/auction/transactions/2");
    }
}
