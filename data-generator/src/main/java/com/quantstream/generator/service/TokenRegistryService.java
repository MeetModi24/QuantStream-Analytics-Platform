package com.quantstream.generator.service;

import com.quantstream.generator.model.TokenConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that manages the token registry.
 * <p>
 * Loads token configurations from CSV file and provides access to active tokens
 * based on application configuration.
 * <p>
 * Supports loading from:
 * - Classpath resource (classpath:tokens.csv)
 * - HTTP/HTTPS URL (https://example.com/tokens.csv)
 * - File system (file:/path/to/tokens.csv)
 * <p>
 * Design: This service loads ALL available tokens but only returns those
 * specified in the enabled-symbols configuration. This allows us to:
 * - Have a large pool (100+ tokens) in CSV
 * - Run only what free tier allows (10 tokens)
 * - Change active tokens without code changes
 * - Load tokens from external sources (GitHub, S3, etc.)
 */
@Service
public class TokenRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TokenRegistryService.class);

    // CSV file location - supports classpath:, http://, https://, file:
    @Value("${market.data.token-source:classpath:tokens.csv}")
    private String tokenSourcePath;

    // Classpath resource (used when tokenSourcePath is classpath:)
    @Value("${market.data.token-source:classpath:tokens.csv}")
    private Resource tokensResource;

    // Comma-separated list of enabled symbols from application.yml
    @Value("${market.data.enabled-symbols}")
    private String enabledSymbolsConfig;

    // All available tokens (loaded from CSV)
    private final Map<String, TokenConfig> tokenRegistry = new HashMap<>();

    // Only enabled tokens (subset of registry)
    private List<TokenConfig> activeTokens = new ArrayList<>();

    /**
     * Loads token registry from configured source on application startup.
     */
    @PostConstruct
    public void loadTokenRegistry() {
        log.info("Loading token registry from: {}", tokenSourcePath);

        try {
            String csvContent = loadCsvContent();
            parseAndLoadTokens(csvContent);

            log.info("Loaded {} tokens from registry", tokenRegistry.size());

            // Filter to active tokens based on configuration
            filterActiveTokens();

        } catch (IOException e) {
            log.error("Failed to load token registry: {}", e.getMessage(), e);
            throw new RuntimeException("Cannot start without token registry", e);
        }
    }

    /**
     * Loads CSV content from configured source (classpath, URL, or file).
     */
    private String loadCsvContent() throws IOException {
        // Check if source is HTTP/HTTPS URL
        if (tokenSourcePath.startsWith("http://") || tokenSourcePath.startsWith("https://")) {
            return loadFromUrl(tokenSourcePath);
        }
        // Otherwise, use Spring Resource (supports classpath:, file:, etc.)
        else {
            return loadFromResource();
        }
    }

    /**
     * Loads CSV from HTTP/HTTPS URL.
     * Useful for loading from GitHub raw URLs, S3 pre-signed URLs, etc.
     */
    private String loadFromUrl(String urlString) throws IOException {
        log.info("Loading token registry from URL: {}", urlString);

        try {
            RestTemplate restTemplate = new RestTemplate();
            String content = restTemplate.getForObject(urlString, String.class);

            if (content == null || content.isEmpty()) {
                throw new IOException("Empty response from URL: " + urlString);
            }

            log.info("Successfully loaded {} bytes from URL", content.length());
            return content;

        } catch (Exception e) {
            throw new IOException("Failed to load from URL: " + urlString, e);
        }
    }

    /**
     * Loads CSV from Spring Resource (classpath or file system).
     */
    private String loadFromResource() throws IOException {
        log.info("Loading token registry from resource: {}", tokensResource.getFilename());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(tokensResource.getInputStream()))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            return content.toString();
        }
    }

    /**
     * Parses CSV content and loads tokens into registry.
     */
    private void parseAndLoadTokens(String csvContent) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            boolean isHeader = true;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip header
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                // Skip empty lines and comments
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }

                try {
                    TokenConfig token = parseCsvLine(line);
                    tokenRegistry.put(token.symbol(), token);
                    log.debug("Loaded token: {} - {}", token.symbol(), token.name());
                } catch (Exception e) {
                    log.error("Failed to parse line {}: {} - Error: {}",
                             lineNumber, line, e.getMessage());
                }
            }
        }
    }

    /**
     * Parses a single CSV line into TokenConfig.
     */
    private TokenConfig parseCsvLine(String line) {
        String[] parts = line.split(",");
        if (parts.length != 8) {
            throw new IllegalArgumentException(
                "Expected 8 fields, got " + parts.length + ": " + line);
        }

        return new TokenConfig(
            parts[0].trim(),                    // symbol
            parts[1].trim(),                    // name
            Double.parseDouble(parts[2].trim()), // initialPrice
            Double.parseDouble(parts[3].trim()), // drift
            Double.parseDouble(parts[4].trim()), // volatility
            Double.parseDouble(parts[5].trim()), // baseVolume
            parts[6].trim(),                    // category
            Integer.parseInt(parts[7].trim())    // priority
        );
    }

    /**
     * Filters active tokens based on enabled-symbols configuration.
     */
    private void filterActiveTokens() {
        Set<String> enabledSymbols = Arrays.stream(enabledSymbolsConfig.split(","))
            .map(String::trim)
            .collect(Collectors.toSet());

        log.info("Enabled symbols from config: {}", enabledSymbols);

        activeTokens = enabledSymbols.stream()
            .map(symbol -> {
                TokenConfig token = tokenRegistry.get(symbol);
                if (token == null) {
                    log.warn("Enabled symbol '{}' not found in registry - skipping", symbol);
                }
                return token;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(TokenConfig::priority)
                             .thenComparing(TokenConfig::symbol))
            .toList();

        log.info("Active tokens: {} out of {} available",
                 activeTokens.size(), tokenRegistry.size());

        activeTokens.forEach(token ->
            log.info("  Active: {} ({}) - priority={}, drift={}%, volatility={}%",
                     token.symbol(), token.name(), token.priority(),
                     token.drift() * 100, token.volatility() * 100)
        );
    }

    /**
     * Returns list of active tokens (only those enabled in configuration).
     */
    public List<TokenConfig> getActiveTokens() {
        return Collections.unmodifiableList(activeTokens);
    }

    /**
     * Returns total number of tokens in registry (for monitoring).
     */
    public int getTotalTokenCount() {
        return tokenRegistry.size();
    }

    /**
     * Returns number of active tokens (for monitoring).
     */
    public int getActiveTokenCount() {
        return activeTokens.size();
    }

    /**
     * Returns token configuration by symbol.
     * @param symbol The symbol to look up
     * @return TokenConfig if found, null otherwise
     */
    public TokenConfig getTokenBySymbol(String symbol) {
        return tokenRegistry.get(symbol);
    }
}
