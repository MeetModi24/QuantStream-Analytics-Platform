package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrnsteinUhlenbeckStrategyTest {

    private static final Instant TS = Instant.parse("2026-07-24T10:30:00Z");

    private static Features atPrice(double microprice) {
        // Only microprice matters to this strategy; other fields are placeholders.
        return new Features("AAPL", TS, 0.0, microprice, microprice, 0.01, 0.55);
    }

    /** Small window so tests warm up quickly; tight bands for deterministic crossings. */
    private OrnsteinUhlenbeckStrategy strategy() {
        return new OrnsteinUhlenbeckStrategy("AAPL", 10, 2.0, 0.5);
    }

    /** Feed n observations at a constant price to warm the window without triggering. */
    private static void warmFlat(OrnsteinUhlenbeckStrategy s, double price, int n) {
        for (int i = 0; i < n; i++) {
            s.onFeatures(atPrice(price));
        }
    }

    @Test
    void rejectsBandsWithoutHysteresis() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrnsteinUhlenbeckStrategy("AAPL", 10, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new OrnsteinUhlenbeckStrategy("AAPL", 10, 0.5, 2.0));
    }

    @Test
    void warmupEqualsWindowAndSuppressesSignals() {
        OrnsteinUhlenbeckStrategy s = strategy();
        assertEquals(10, s.warmupObservations());
        // Even a wild price during warmup must not emit.
        for (int i = 0; i < 9; i++) {
            assertTrue(s.onFeatures(atPrice(100.0 + i)).isEmpty());
            assertFalse(s.isWarm());
        }
        s.onFeatures(atPrice(100.0));
        assertTrue(s.isWarm());
    }

    @Test
    void noSignalWhenWindowIsFlat() {
        OrnsteinUhlenbeckStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // stdDev == 0 -> z undefined -> no signal even though price sits on the mean.
        assertTrue(s.onFeatures(atPrice(100.0)).isEmpty());
    }

    @Test
    void buysWhenPriceIsUnusuallyCheap() {
        OrnsteinUhlenbeckStrategy s = strategy();
        // Build dispersion around ~100, then drop sharply so z <= -2.
        double[] warm = {100, 101, 99, 100, 101, 99, 100, 101, 99, 100};
        for (double p : warm) s.onFeatures(atPrice(p));
        Optional<Signal> sig = s.onFeatures(atPrice(90.0));
        assertTrue(sig.isPresent());
        assertEquals(Signal.Action.BUY, sig.get().action());
        assertEquals("ornstein_uhlenbeck", sig.get().strategy());
    }

    @Test
    void sellsWhenPriceIsUnusuallyRich() {
        OrnsteinUhlenbeckStrategy s = strategy();
        double[] warm = {100, 101, 99, 100, 101, 99, 100, 101, 99, 100};
        for (double p : warm) s.onFeatures(atPrice(p));
        Optional<Signal> sig = s.onFeatures(atPrice(110.0));
        assertTrue(sig.isPresent());
        assertEquals(Signal.Action.SELL, sig.get().action());
    }

    @Test
    void closesWhenPriceRevertsToMean() {
        OrnsteinUhlenbeckStrategy s = strategy();
        double[] warm = {100, 101, 99, 100, 101, 99, 100, 101, 99, 100};
        for (double p : warm) s.onFeatures(atPrice(p));
        assertEquals(Signal.Action.BUY, s.onFeatures(atPrice(90.0)).orElseThrow().action());
        // Price snaps back near the rolling mean -> CLOSE.
        Optional<Signal> close = s.onFeatures(atPrice(100.0));
        assertTrue(close.isPresent());
        assertEquals(Signal.Action.CLOSE, close.get().action());
    }

    @Test
    void edgeTriggeredDoesNotRepeatWhileHeld() {
        OrnsteinUhlenbeckStrategy s = strategy();
        double[] warm = {100, 101, 99, 100, 101, 99, 100, 101, 99, 100};
        for (double p : warm) s.onFeatures(atPrice(p));
        assertTrue(s.onFeatures(atPrice(90.0)).isPresent(), "first cheap crossing buys");
        // Still cheap: no repeat BUY while already LONG.
        assertTrue(s.onFeatures(atPrice(89.0)).isEmpty());
    }
}
