package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObiMarketMakingStrategyTest {

    private static Features withObi(double obi) {
        return new Features("AAPL", Instant.parse("2026-07-24T10:30:00Z"),
                obi, 180.50, 180.50, 0.01, 0.55);
    }

    private ObiMarketMakingStrategy strategy() {
        return new ObiMarketMakingStrategy("AAPL", 0.5);
    }

    @Test
    void stalelessStrategyIsImmediatelyWarm() {
        ObiMarketMakingStrategy s = strategy();
        assertEquals(0, s.warmupObservations());
        assertTrue(s.isWarm());
    }

    @Test
    void noSignalInsideNeutralBand() {
        assertTrue(strategy().onFeatures(withObi(0.1)).isEmpty());
        assertTrue(strategy().onFeatures(withObi(-0.3)).isEmpty());
    }

    @Test
    void emitsBuyWhenObiCrossesUpperThreshold() {
        Optional<Signal> signal = strategy().onFeatures(withObi(0.7));
        assertTrue(signal.isPresent());
        assertEquals(Signal.Action.BUY, signal.get().action());
        assertEquals("obi_market_making", signal.get().strategy());
        assertEquals("AAPL", signal.get().token());
    }

    @Test
    void emitsSellWhenObiCrossesLowerThreshold() {
        Optional<Signal> signal = strategy().onFeatures(withObi(-0.8));
        assertTrue(signal.isPresent());
        assertEquals(Signal.Action.SELL, signal.get().action());
    }

    @Test
    void edgeTriggeredDoesNotRepeatWhileStaysAboveThreshold() {
        ObiMarketMakingStrategy s = strategy();
        assertTrue(s.onFeatures(withObi(0.7)).isPresent(), "first crossing fires");
        assertTrue(s.onFeatures(withObi(0.8)).isEmpty(), "still elevated, no repeat");
        assertTrue(s.onFeatures(withObi(0.9)).isEmpty(), "still elevated, no repeat");
    }

    @Test
    void firesAgainAfterReturningToNeutral() {
        ObiMarketMakingStrategy s = strategy();
        assertTrue(s.onFeatures(withObi(0.7)).isPresent());
        assertTrue(s.onFeatures(withObi(0.1)).isEmpty(), "back to neutral resets");
        assertTrue(s.onFeatures(withObi(0.7)).isPresent(), "new crossing fires again");
    }

    @Test
    void flipsFromBuyToSellDirectly() {
        ObiMarketMakingStrategy s = strategy();
        assertEquals(Signal.Action.BUY, s.onFeatures(withObi(0.7)).orElseThrow().action());
        assertEquals(Signal.Action.SELL, s.onFeatures(withObi(-0.7)).orElseThrow().action());
    }

    @Test
    void confidenceIncreasesWithImbalanceMagnitude() {
        double lowConf = strategy().onFeatures(withObi(0.6)).orElseThrow().confidence();
        double highConf = strategy().onFeatures(withObi(0.95)).orElseThrow().confidence();
        assertTrue(highConf > lowConf);
        assertTrue(lowConf >= 0.0 && highConf <= 1.0);
    }
}
