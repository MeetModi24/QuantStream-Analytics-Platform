package com.quantstream.generator.model;

/**
 * Configuration for a single tradable token.
 * <p>
 * This record is immutable and represents the parameters needed
 * to simulate price movements for a specific asset.
 *
 * @param symbol Symbol identifier (e.g., "AAPL", "BTC")
 * @param name Human-readable name (e.g., "Apple Inc.")
 * @param initialPrice Starting price for simulation
 * @param drift Expected annual return (e.g., 0.08 = 8%)
 * @param volatility Annual volatility/standard deviation (e.g., 0.25 = 25%)
 * @param baseVolume Base trading volume for this token
 * @param category Asset category ("stock", "crypto", "forex", "commodity")
 * @param priority Priority level (1=high, 2=medium, 3=low) - used for rate limiting
 */
public record TokenConfig(
    String symbol,
    String name,
    double initialPrice,
    double drift,
    double volatility,
    double baseVolume,
    String category,
    int priority
) {
    /**
     * Validates token configuration parameters.
     */
    public TokenConfig {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }
        if (initialPrice <= 0) {
            throw new IllegalArgumentException("Initial price must be positive: " + initialPrice);
        }
        if (volatility < 0) {
            throw new IllegalArgumentException("Volatility cannot be negative: " + volatility);
        }
        if (baseVolume <= 0) {
            throw new IllegalArgumentException("Base volume must be positive: " + baseVolume);
        }
        if (priority < 1 || priority > 3) {
            throw new IllegalArgumentException("Priority must be 1 (high), 2 (medium), or 3 (low)");
        }
    }
}
