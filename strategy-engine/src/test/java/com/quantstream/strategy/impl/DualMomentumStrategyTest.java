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

class DualMomentumStrategyTest {

    private static final Instant TS = Instant.parse("2026-07-24T10:30:00Z");

    private static Features atPrice(double microprice) {
        // Only microprice matters to this strategy; other fields are placeholders.
        return new Features("AAPL", TS, 0.0, microprice, microprice, 0.01, 0.55);
    }

    /** Small windows so tests warm up quickly; entry/exit in bps. */
    private DualMomentumStrategy strategy() {
        return new DualMomentumStrategy("AAPL", 3, 10, 5.0, 1.0);
    }

    /** Feed n observations at a constant price to warm both windows without triggering. */
    private static void warmFlat(DualMomentumStrategy s, double price, int n) {
        for (int i = 0; i < n; i++) {
            s.onFeatures(atPrice(price));
        }
    }

    @Test
    void rejectsInvalidWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> new DualMomentumStrategy("AAPL", 10, 10, 5.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DualMomentumStrategy("AAPL", 20, 10, 5.0, 1.0));
    }

    @Test
    void rejectsBandsWithoutHysteresis() {
        assertThrows(IllegalArgumentException.class,
                () -> new DualMomentumStrategy("AAPL", 3, 10, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DualMomentumStrategy("AAPL", 3, 10, 1.0, 5.0));
    }

    @Test
    void warmupEqualsSlowWindowAndSuppressesSignals() {
        DualMomentumStrategy s = strategy();
        assertEquals(10, s.warmupObservations());
        // Even a strong ramp during warmup must not emit.
        for (int i = 0; i < 9; i++) {
            assertTrue(s.onFeatures(atPrice(100.0 + i)).isEmpty());
            assertFalse(s.isWarm());
        }
        s.onFeatures(atPrice(109.0));
        assertTrue(s.isWarm());
    }

    @Test
    void flatPriceProducesNoSignal() {
        DualMomentumStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // Fast and slow means coincide → zero spread → no trend.
        assertTrue(s.onFeatures(atPrice(100.0)).isEmpty());
    }

    @Test
    void risingTrendEmitsBuy() {
        DualMomentumStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // Push the price up so the fast MA pulls decisively above the slow MA.
        Optional<Signal> fired = Optional.empty();
        for (int i = 1; i <= 5 && fired.isEmpty(); i++) {
            fired = s.onFeatures(atPrice(100.0 + i));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.BUY, fired.get().action());
        assertTrue(fired.get().confidence() >= 0.0 && fired.get().confidence() <= 1.0);
    }

    @Test
    void fallingTrendEmitsSell() {
        DualMomentumStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        Optional<Signal> fired = Optional.empty();
        for (int i = 1; i <= 5 && fired.isEmpty(); i++) {
            fired = s.onFeatures(atPrice(100.0 - i));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.SELL, fired.get().action());
    }

    @Test
    void doesNotReEmitWhileTrendPersists() {
        DualMomentumStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // First entry.
        Optional<Signal> first = Optional.empty();
        for (int i = 1; i <= 5 && first.isEmpty(); i++) {
            first = s.onFeatures(atPrice(100.0 + i));
        }
        assertTrue(first.isPresent());
        assertEquals(Signal.Action.BUY, first.get().action());
        // Continued uptrend at an even higher price must NOT emit a second BUY.
        assertTrue(s.onFeatures(atPrice(110.0)).isEmpty());
    }

    @Test
    void closesWhenTrendConverges() {
        DualMomentumStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // Enter long on a rise.
        Optional<Signal> entered = Optional.empty();
        for (int i = 1; i <= 5 && entered.isEmpty(); i++) {
            entered = s.onFeatures(atPrice(100.0 + i));
        }
        assertTrue(entered.isPresent());
        // Hold price flat long enough for the fast MA to converge back to the slow MA.
        Optional<Signal> closed = Optional.empty();
        for (int i = 0; i < 10 && closed.isEmpty(); i++) {
            closed = s.onFeatures(atPrice(105.0));
        }
        assertTrue(closed.isPresent());
        assertEquals(Signal.Action.CLOSE, closed.get().action());
    }
}
