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

class KalmanTrendStrategyTest {

    private static final Instant TS = Instant.parse("2026-07-24T10:30:00Z");

    private static Features atPrice(double microprice) {
        // Only microprice matters to this strategy; other fields are placeholders.
        return new Features("AAPL", TS, 0.0, microprice, microprice, 0.01, 0.55);
    }

    /** Small warmup so tests converge quickly; reactive q/r ratio; entry/exit in bps. */
    private KalmanTrendStrategy strategy() {
        return new KalmanTrendStrategy("AAPL", 10, 0.5, 1.0, 3.0, 0.5);
    }

    /** Feed n observations at a constant price to warm the filter without triggering. */
    private static void warmFlat(KalmanTrendStrategy s, double price, int n) {
        for (int i = 0; i < n; i++) {
            s.onFeatures(atPrice(price));
        }
    }

    @Test
    void rejectsInvalidWarmup() {
        assertThrows(IllegalArgumentException.class,
                () -> new KalmanTrendStrategy("AAPL", 0, 0.5, 1.0, 3.0, 0.5));
    }

    @Test
    void rejectsNonPositiveNoise() {
        assertThrows(IllegalArgumentException.class,
                () -> new KalmanTrendStrategy("AAPL", 10, 0.0, 1.0, 3.0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new KalmanTrendStrategy("AAPL", 10, 0.5, 0.0, 3.0, 0.5));
    }

    @Test
    void rejectsBandsWithoutHysteresis() {
        assertThrows(IllegalArgumentException.class,
                () -> new KalmanTrendStrategy("AAPL", 10, 0.5, 1.0, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new KalmanTrendStrategy("AAPL", 10, 0.5, 1.0, 0.5, 3.0));
    }

    @Test
    void warmupSuppressesSignals() {
        KalmanTrendStrategy s = strategy();
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
        KalmanTrendStrategy s = strategy();
        warmFlat(s, 100.0, 30);
        // Estimated velocity ~0 → no trend.
        assertTrue(s.onFeatures(atPrice(100.0)).isEmpty());
    }

    @Test
    void risingTrendEmitsBuy() {
        KalmanTrendStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // A steady ramp should drive the estimated velocity above the entry band.
        Optional<Signal> fired = Optional.empty();
        double price = 100.0;
        for (int i = 0; i < 20 && fired.isEmpty(); i++) {
            price += 0.5;
            fired = s.onFeatures(atPrice(price));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.BUY, fired.get().action());
        assertTrue(fired.get().confidence() >= 0.0 && fired.get().confidence() <= 1.0);
    }

    @Test
    void fallingTrendEmitsSell() {
        KalmanTrendStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        Optional<Signal> fired = Optional.empty();
        double price = 100.0;
        for (int i = 0; i < 20 && fired.isEmpty(); i++) {
            price -= 0.5;
            fired = s.onFeatures(atPrice(price));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.SELL, fired.get().action());
    }

    @Test
    void doesNotReEmitWhileTrendPersists() {
        KalmanTrendStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        Optional<Signal> first = Optional.empty();
        double price = 100.0;
        for (int i = 0; i < 20 && first.isEmpty(); i++) {
            price += 0.5;
            first = s.onFeatures(atPrice(price));
        }
        assertTrue(first.isPresent());
        assertEquals(Signal.Action.BUY, first.get().action());
        // Continued uptrend must NOT emit a second BUY.
        for (int i = 0; i < 5; i++) {
            price += 0.5;
            assertTrue(s.onFeatures(atPrice(price)).isEmpty());
        }
    }

    @Test
    void closesWhenTrendFlattens() {
        KalmanTrendStrategy s = strategy();
        warmFlat(s, 100.0, 10);
        // Enter long on a ramp.
        Optional<Signal> entered = Optional.empty();
        double price = 100.0;
        for (int i = 0; i < 20 && entered.isEmpty(); i++) {
            price += 0.5;
            entered = s.onFeatures(atPrice(price));
        }
        assertTrue(entered.isPresent());
        assertEquals(Signal.Action.BUY, entered.get().action());
        // Hold price flat long enough for the estimated velocity to decay back into the exit band.
        Optional<Signal> closed = Optional.empty();
        for (int i = 0; i < 50 && closed.isEmpty(); i++) {
            closed = s.onFeatures(atPrice(price));
        }
        assertTrue(closed.isPresent());
        assertEquals(Signal.Action.CLOSE, closed.get().action());
    }
}
