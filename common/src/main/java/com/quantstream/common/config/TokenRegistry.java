package com.quantstream.common.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and exposes the token universe from {@code tokens.yml}.
 *
 * <p>This is the single source of truth for which tokens the system runs. Every
 * service builds its per-token state from this registry, so scaling from 1 to
 * 100 (or 30,000) tokens is purely a config change.
 *
 * <p>The registry is immutable after construction and safe to share across threads.
 * Only tokens with {@code enabled: true} are returned by {@link #enabledTokens()}.
 */
public final class TokenRegistry {

    private static final String DEFAULT_RESOURCE = "tokens.yml";

    private final Map<String, TokenConfig> tokensBySymbol;

    private TokenRegistry(List<TokenConfig> tokens) {
        Map<String, TokenConfig> map = new LinkedHashMap<>();
        for (TokenConfig t : tokens) {
            if (map.putIfAbsent(t.symbol(), t) != null) {
                throw new IllegalStateException("Duplicate token symbol in config: " + t.symbol());
            }
        }
        this.tokensBySymbol = Collections.unmodifiableMap(map);
    }

    /** Loads the registry from the default classpath resource {@code tokens.yml}. */
    public static TokenRegistry fromClasspath() {
        return fromClasspath(DEFAULT_RESOURCE);
    }

    /** Loads the registry from a named classpath resource. */
    public static TokenRegistry fromClasspath(String resource) {
        try (InputStream in = TokenRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Token config not found on classpath: " + resource);
            }
            return fromInputStream(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load token config: " + resource, e);
        }
    }

    /** Parses the registry from an arbitrary YAML input stream (used by tests). */
    @SuppressWarnings("unchecked")
    public static TokenRegistry fromInputStream(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);
        if (root == null || !root.containsKey("tokens")) {
            throw new IllegalStateException("Token config missing top-level 'tokens' list");
        }
        List<Map<String, Object>> rawTokens = (List<Map<String, Object>>) root.get("tokens");
        if (rawTokens == null || rawTokens.isEmpty()) {
            throw new IllegalStateException("Token config 'tokens' list is empty");
        }

        List<TokenConfig> tokens = new ArrayList<>(rawTokens.size());
        for (Map<String, Object> raw : rawTokens) {
            tokens.add(new TokenConfig(
                    str(raw, "symbol"),
                    strOrDefault(raw, "category", "unknown"),
                    dbl(raw, "initialPrice"),
                    dbl(raw, "tickSize"),
                    dbl(raw, "spread"),
                    dbl(raw, "baseVolume"),
                    dbl(raw, "volatility"),
                    boolOrDefault(raw, "enabled", true)
            ));
        }
        return new TokenRegistry(tokens);
    }

    /** All tokens with {@code enabled: true}, in config order. */
    public List<TokenConfig> enabledTokens() {
        return tokensBySymbol.values().stream()
                .filter(TokenConfig::enabled)
                .toList();
    }

    /** Every token defined in config, regardless of enabled state. */
    public List<TokenConfig> allTokens() {
        return List.copyOf(tokensBySymbol.values());
    }

    /** Looks up a single token's config by symbol. */
    public Optional<TokenConfig> get(String symbol) {
        return Optional.ofNullable(tokensBySymbol.get(symbol));
    }

    /** Number of enabled tokens. */
    public int enabledCount() {
        return (int) tokensBySymbol.values().stream().filter(TokenConfig::enabled).count();
    }

    // --- typed accessors with clear error messages ---

    private static String str(Map<String, Object> m, String key) {
        Object v = require(m, key);
        return v.toString();
    }

    private static String strOrDefault(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private static double dbl(Map<String, Object> m, String key) {
        Object v = require(m, key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Expected numeric '" + key + "' but got: " + v);
        }
    }

    private static boolean boolOrDefault(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static Object require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalStateException("Token config entry missing required field: " + key);
        }
        return v;
    }
}
