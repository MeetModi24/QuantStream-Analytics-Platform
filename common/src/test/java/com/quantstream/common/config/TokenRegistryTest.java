package com.quantstream.common.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenRegistryTest {

    private static TokenRegistry parse(String yaml) {
        return TokenRegistry.fromInputStream(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void loadsDefaultClasspathConfig() {
        TokenRegistry registry = TokenRegistry.fromClasspath();
        assertTrue(registry.enabledCount() >= 1, "at least AAPL should be enabled");
        assertTrue(registry.get("AAPL").isPresent());
    }

    @Test
    void enabledFilterExcludesDisabledTokens() {
        TokenRegistry registry = parse("""
                tokens:
                  - symbol: AAA
                    initialPrice: 100
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                    enabled: true
                  - symbol: BBB
                    initialPrice: 50
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                    enabled: false
                """);
        assertEquals(2, registry.allTokens().size());
        List<TokenConfig> enabled = registry.enabledTokens();
        assertEquals(1, enabled.size());
        assertEquals("AAA", enabled.getFirst().symbol());
    }

    @Test
    void enabledDefaultsToTrueWhenOmitted() {
        TokenRegistry registry = parse("""
                tokens:
                  - symbol: AAA
                    initialPrice: 100
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                """);
        assertTrue(registry.get("AAA").orElseThrow().enabled());
    }

    @Test
    void rejectsDuplicateSymbols() {
        assertThrows(IllegalStateException.class, () -> parse("""
                tokens:
                  - symbol: AAA
                    initialPrice: 100
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                  - symbol: AAA
                    initialPrice: 200
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                """));
    }

    @Test
    void rejectsMissingRequiredField() {
        assertThrows(IllegalStateException.class, () -> parse("""
                tokens:
                  - symbol: AAA
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                """));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                tokens:
                  - symbol: AAA
                    initialPrice: -5
                    tickSize: 0.01
                    spread: 0.01
                    baseVolume: 1000
                    volatility: 0.001
                """));
    }

    @Test
    void rejectsEmptyTokenList() {
        assertThrows(IllegalStateException.class, () -> parse("tokens: []\n"));
    }
}
