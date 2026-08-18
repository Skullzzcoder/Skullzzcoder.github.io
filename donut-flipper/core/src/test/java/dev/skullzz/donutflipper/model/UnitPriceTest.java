package dev.skullzz.donutflipper.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stack-size normalisation. Comparing raw prices across different stack sizes is
 * the easiest way to generate confident nonsense.
 */
class UnitPriceTest {

    @Test
    @DisplayName("a stack and a single are compared per unit, not per listing")
    void stackSizeIsNormalised() {
        Instant now = Instant.now();
        Listing stack = new Listing("a", "Alex", 64_000,
                AuctionItem.simple("minecraft:diamond", 64), now, now);
        Listing single = new Listing("b", "Steve", 1_500,
                AuctionItem.simple("minecraft:diamond", 1), now, now);

        assertEquals(1_000.0, stack.unitPrice(), 0.001);
        assertEquals(1_500.0, single.unitPrice(), 0.001);
        // Raw price says the stack costs 42x more. Per unit it is the better buy.
        assertTrue(stack.unitPrice() < single.unitPrice());
    }

    @Test
    @DisplayName("unit price never divides by zero on a malformed count")
    void zeroCountIsCoerced() {
        Instant now = Instant.now();
        AuctionItem weird = new AuctionItem("minecraft:diamond", "", 0,
                null, null, 0, 0, null);
        Listing l = new Listing("c", "Nova", 500, weird, now, now);

        assertEquals(1, weird.count());
        assertEquals(500.0, l.unitPrice(), 0.001);
        assertTrue(Double.isFinite(l.unitPrice()));
    }
}
