package com.quantstream.common.config;

/**
 * Microstructure configuration for a single token, loaded from {@code tokens.yml}.
 *
 * <p>These parameters drive the order book simulation and let each token behave
 * differently (a crypto token has a wider spread and higher volatility than a
 * utility stock). Adding tokens is a config change only — see {@link TokenRegistry}.
 *
 * @param symbol       ticker, unique within the universe
 * @param category     sector bucket (tech, crypto, financials, ...)
 * @param initialPrice seed price for the simulation
 * @param tickSize     minimum price increment
 * @param spread       typical bid-ask spread in price units
 * @param baseVolume   top-of-book volume scale
 * @param volatility   per-tick fractional price volatility
 * @param enabled      whether this token participates in the running system
 */
public record TokenConfig(
        String symbol,
        String category,
        double initialPrice,
        double tickSize,
        double spread,
        double baseVolume,
        double volatility,
        boolean enabled
) {
    public TokenConfig {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Token symbol must not be blank");
        }
        if (initialPrice <= 0) {
            throw new IllegalArgumentException("initialPrice must be > 0 for " + symbol);
        }
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be > 0 for " + symbol);
        }
        if (spread < 0) {
            throw new IllegalArgumentException("spread must be >= 0 for " + symbol);
        }
        if (baseVolume <= 0) {
            throw new IllegalArgumentException("baseVolume must be > 0 for " + symbol);
        }
        if (volatility < 0) {
            throw new IllegalArgumentException("volatility must be >= 0 for " + symbol);
        }
    }
}
