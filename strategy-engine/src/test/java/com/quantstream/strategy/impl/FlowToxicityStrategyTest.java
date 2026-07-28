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

class FlowToxicityStrategyTest {

    private static final Instant TS = Instant.parse("2026-07-24T10:30:00Z");

    private static Features atObi(double obi) {
        // Only obi matters to this strategy; microprice used only as the signal's price field.
        return new Features("AAPL", TS, obi, 100.0, 100.0, 0.01, 0.55);
    }

    /** Small window so tests warm up quickly; entry/exit as mean-OBI levels. */
    private FlowToxicityStrategy strategy() {
        return new FlowToxicityStrategy("AAPL", 10, 0.3, 0.1);
    }

    /** Feed n observations at a constant OBI to warm the window. */
    private static void warm(FlowToxicityStrategy s, double obi, int n) {
        for (int i = 0; i < n; i++) {
            s.onFeatures(atObi(obi));
        }
    }

    @Test
    void rejectsBandsWithoutHysteresis() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlowToxicityStrategy("AAPL", 10, 0.3, 0.3));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowToxicityStrategy("AAPL", 10, 0.1, 0.3));
    }

    @Test
    void rejectsEntryOutOfObiRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlowToxicityStrategy("AAPL", 10, 1.5, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowToxicityStrategy("AAPL", 10, 0.0, -0.1));
    }

    @Test
    void warmupEqualsWindowAndSuppressesSignals() {
        FlowToxicityStrategy s = strategy();
        assertEquals(10, s.warmupObservations());
        // Even strong one-sided flow during warmup must not emit.
        for (int i = 0; i < 9; i++) {
            assertTrue(s.onFeatures(atObi(0.9)).isEmpty());
            assertFalse(s.isWarm());
        }
        s.onFeatures(atObi(0.9));
        assertTrue(s.isWarm());
    }

    @Test
    void balancedFlowProducesNoSignal() {
        FlowToxicityStrategy s = strategy();
        warm(s, 0.0, 10);
        // Mean OBI ~0 → no sustained pressure.
        assertTrue(s.onFeatures(atObi(0.0)).isEmpty());
    }

    @Test
    void sustainedBuyPressureEmitsBuy() {
        FlowToxicityStrategy s = strategy();
        // Fill the whole window with strongly positive OBI so its mean clears the entry band.
        Optional<Signal> fired = Optional.empty();
        for (int i = 0; i < 15 && fired.isEmpty(); i++) {
            fired = s.onFeatures(atObi(0.6));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.BUY, fired.get().action());
        assertTrue(fired.get().confidence() >= 0.0 && fired.get().confidence() <= 1.0);
    }

    @Test
    void sustainedSellPressureEmitsSell() {
        FlowToxicityStrategy s = strategy();
        Optional<Signal> fired = Optional.empty();
        for (int i = 0; i < 15 && fired.isEmpty(); i++) {
            fired = s.onFeatures(atObi(-0.6));
        }
        assertTrue(fired.isPresent());
        assertEquals(Signal.Action.SELL, fired.get().action());
    }

    @Test
    void transientSpikeDoesNotTrip() {
        // Distinguishes this from the instantaneous OBI strategy: a single big spike in an
        // otherwise balanced window must not clear the entry band on the mean.
        FlowToxicityStrategy s = strategy();
        warm(s, 0.0, 10);
        // One extreme tick — mean over the window is ~0.1, below entry 0.3.
        assertTrue(s.onFeatures(atObi(1.0)).isEmpty());
    }

    @Test
    void doesNotReEmitWhilePressurePersists() {
        FlowToxicityStrategy s = strategy();
        Optional<Signal> first = Optional.empty();
        for (int i = 0; i < 15 && first.isEmpty(); i++) {
            first = s.onFeatures(atObi(0.6));
        }
        assertTrue(first.isPresent());
        assertEquals(Signal.Action.BUY, first.get().action());
        // Continued buy pressure must NOT emit a second BUY.
        assertTrue(s.onFeatures(atObi(0.6)).isEmpty());
    }

    @Test
    void closesWhenFlowBalances() {
        FlowToxicityStrategy s = strategy();
        Optional<Signal> entered = Optional.empty();
        for (int i = 0; i < 15 && entered.isEmpty(); i++) {
            entered = s.onFeatures(atObi(0.6));
        }
        assertTrue(entered.isPresent());
        // Feed balanced flow until the rolling mean decays back into the exit band.
        Optional<Signal> closed = Optional.empty();
        for (int i = 0; i < 15 && closed.isEmpty(); i++) {
            closed = s.onFeatures(atObi(0.0));
        }
        assertTrue(closed.isPresent());
        assertEquals(Signal.Action.CLOSE, closed.get().action());
    }
}
