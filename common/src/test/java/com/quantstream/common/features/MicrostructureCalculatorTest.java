package com.quantstream.common.features;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.OrderBookSnapshot;
import com.quantstream.common.model.PriceLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrostructureCalculatorTest {

    private static final double EPS = 1e-9;

    private static OrderBookSnapshot book(double bidPrice, double bidVol,
                                          double askPrice, double askVol) {
        return new OrderBookSnapshot(
                "TEST", Instant.parse("2026-07-24T10:30:00Z"),
                List.of(new PriceLevel(bidPrice, bidVol, 1)),
                List.of(new PriceLevel(askPrice, askVol, 1)));
    }

    @Test
    void obiIsZeroWhenBalanced() {
        assertEquals(0.0, MicrostructureCalculator.orderBookImbalance(5000, 5000), EPS);
    }

    @Test
    void obiPositiveWhenBidHeavy() {
        // (5000 - 4200) / 9200
        assertEquals(800.0 / 9200.0,
                MicrostructureCalculator.orderBookImbalance(5000, 4200), EPS);
    }

    @Test
    void obiBoundedByPlusMinusOne() {
        assertEquals(1.0, MicrostructureCalculator.orderBookImbalance(1000, 0), EPS);
        assertEquals(-1.0, MicrostructureCalculator.orderBookImbalance(0, 1000), EPS);
    }

    @Test
    void obiZeroForEmptyBook() {
        assertEquals(0.0, MicrostructureCalculator.orderBookImbalance(0, 0), EPS);
    }

    @Test
    void micropriceMatchesKnownExample() {
        // bid 180.50 @ 5000, ask 180.51 @ 4200
        // (180.50*4200 + 180.51*5000) / 9200 = 180.5054...
        double mp = MicrostructureCalculator.microprice(180.50, 5000, 180.51, 4200);
        assertEquals((180.50 * 4200 + 180.51 * 5000) / 9200.0, mp, EPS);
        // Bid-heavy => microprice sits above the simple mid (180.505).
        assertTrue(mp > 180.505);
    }

    @Test
    void micropriceFallsBackToMidWhenNoVolume() {
        assertEquals(180.505,
                MicrostructureCalculator.microprice(180.50, 0, 180.51, 0), EPS);
    }

    @Test
    void computeProducesAllFields() {
        Features f = MicrostructureCalculator.compute(book(180.50, 5000, 180.51, 4200));
        assertEquals("TEST", f.token());
        assertEquals(800.0 / 9200.0, f.obi(), EPS);
        assertEquals(180.505, f.midPrice(), EPS);
        assertEquals(0.01, f.spread(), 1e-6);
        // spread_bps = (0.01 / 180.505) * 10000 ≈ 0.554
        assertEquals((0.01 / 180.505) * 10_000.0, f.spreadBps(), 1e-6);
    }

    @Test
    void computeRejectsOneSidedBook() {
        OrderBookSnapshot oneSided = new OrderBookSnapshot(
                "TEST", Instant.now(),
                List.of(new PriceLevel(180.50, 5000, 1)),
                List.of());
        assertThrows(IllegalArgumentException.class,
                () -> MicrostructureCalculator.compute(oneSided));
    }
}
