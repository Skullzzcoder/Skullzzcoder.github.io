package dev.skullzz.donutflipper.service;

import dev.skullzz.donutflipper.scan.FlipCandidate;

import java.util.List;

/**
 * Wire format between the daemon and the Minecraft mod.
 *
 * <p>Kept in core so both sides compile against the same definition -- the mod
 * bundles core, so there is exactly one description of this shape rather than a
 * producer and a consumer that drift apart over a JSON contract nobody checks.
 *
 * <p>Flattened and pre-formatted on purpose. The mod renders this on the game
 * thread, and doing number formatting per row per frame is wasted work in the
 * one place where frame time is actually visible to the user.
 */
public record FlipDto(
        String listingId,
        String itemName,
        String materialId,
        String itemKey,
        int count,
        String seller,
        long buyPrice,
        long estimatedValue,
        long netProfit,
        double roiPercent,
        double discountPercent,
        double salesPerDay,
        String confidence,
        int sampleCount,
        int distinctSellers,
        int rejectedSamples,
        double saleOddsPercent,
        double score,
        long ageSeconds
) {

    public static FlipDto from(FlipCandidate c, java.time.Instant now) {
        return new FlipDto(
                c.listing().listingId(),
                c.itemName(),
                c.listing().item().materialId(),
                c.listing().key().exact(),
                c.listing().item().count(),
                c.listing().seller(),
                c.buyPrice(),
                Math.round(c.grossResale()),
                c.netProfit(),
                c.roi() * 100.0,
                c.discountPercent(),
                c.valuation().salesPerDay(),
                c.valuation().confidence().name(),
                c.valuation().sampleCount(),
                c.valuation().distinctSellers(),
                c.valuation().rejectedSamples(),
                c.saleOdds() * 100.0,
                c.score(),
                c.listing().ageSeconds(now));
    }

    public static List<FlipDto> from(List<FlipCandidate> candidates, java.time.Instant now) {
        return candidates.stream().map(c -> from(c, now)).toList();
    }

    /** The auction house search string, so the UI can hand you something actionable. */
    public String searchTerm() {
        String name = materialId.contains(":")
                ? materialId.substring(materialId.indexOf(':') + 1)
                : materialId;
        return name.replace('_', ' ');
    }
}
