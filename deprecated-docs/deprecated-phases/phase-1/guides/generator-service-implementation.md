# Market Data Generator Service Implementation Guide

## What You're Building

**MarketDataGenerator** is a Spring service that demonstrates **production-ready, scalable architecture**:

1. **Token Registry** - Loads token configurations from CSV (scalable to database)
2. **Dynamic Simulator Management** - Creates simulators for active tokens only
3. **Rate Budget Aware** - Respects free tier limits (10,000 msg/day)
4. **Configuration-Driven** - Change tokens without code changes

**Key Design Principle:** Built to handle 10,000+ tokens, but runs only what free tier allows.

---

## Architecture Evolution

### ❌ Naive Approach (Toy Project)

```java
// Hardcoded array - doesn't scale
private static final Object[][] TOKENS = {
    {"AAPL", 180.00, 0.08, 0.25},
    {"BTC", 50000.00, 0.20, 0.50}
};
```

**Problems:**
- Need recompile to add tokens
- No way to handle 1000+ tokens
- Not production-ready

### ✅ Our Approach (Production-Ready)

```
┌──────────────────────────────────┐
│  Token Registry (CSV/Database)   │
│  - 100+ tokens available          │
│  - Metadata per token             │
│  - Priority levels                │
└──────────────────────────────────┘
            ↓ (load on startup)
┌──────────────────────────────────┐
│   TokenRegistryService           │
│   - Parse CSV                     │
│   - Filter active tokens          │
│   - Create simulators             │
└──────────────────────────────────┘
            ↓ (inject)
┌──────────────────────────────────┐
│   MarketDataGenerator            │
│   - Get active tokens             │
│   - Generate ticks (1/sec)        │
│   - Send to Kafka                 │
└──────────────────────────────────┘
```

**Benefits:**
- ✅ Add 1000 tokens by editing CSV (no code change)
- ✅ Control which tokens are active via config
- ✅ Easy to migrate to database later
- ✅ Demonstrates real-world architecture

---

## Step 1: Create Token Configuration Model

### Create TokenConfig Record

**File:** `src/main/java/com/quantstream/generator/model/TokenConfig.java`

```java
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
```

**Why a record?**
- Immutable by default (thread-safe)
- Automatic equals/hashCode/toString
- Compact syntax
- Java 14+ feature (we're on Java 21)

---

## Step 1.5: Add Web Dependency

To support URL loading (production flexibility), add Spring Web:

**Add to `pom.xml`:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

**What this enables:**
- Load CSV from HTTP/HTTPS URLs (GitHub, S3, etc.)
- REST API support (needed in Phase 3)

**Note:** This starts an embedded Tomcat server on port 8081. We'll use it in Phase 3 for the API.

---

## Step 2: Create Token Registry CSV

### Create tokens.csv

**File:** `src/main/resources/tokens.csv`

```csv
symbol,name,initialPrice,drift,volatility,baseVolume,category,priority
# Large Cap Tech Stocks (Low Volatility, Steady Growth)
AAPL,Apple Inc.,180.00,0.08,0.25,5000.0,stock,1
MSFT,Microsoft Corp.,380.00,0.08,0.25,4000.0,stock,1
GOOGL,Alphabet Inc.,140.00,0.08,0.25,3000.0,stock,1
META,Meta Platforms,350.00,0.10,0.30,4500.0,stock,2
NVDA,NVIDIA Corp.,450.00,0.20,0.45,7000.0,stock,1
# Growth Stocks (Higher Volatility)
TSLA,Tesla Inc.,250.00,0.15,0.40,8000.0,stock,2
AMZN,Amazon.com,175.00,0.10,0.30,6000.0,stock,1
NFLX,Netflix Inc.,420.00,0.12,0.35,3500.0,stock,2
# Financial Sector
JPM,JPMorgan Chase,150.00,0.06,0.20,4000.0,stock,2
BAC,Bank of America,35.00,0.05,0.22,10000.0,stock,3
# Energy Sector
XOM,Exxon Mobil,105.00,0.07,0.28,5000.0,stock,3
CVX,Chevron Corp.,155.00,0.07,0.28,4000.0,stock,3
# Major Cryptocurrencies (High Volatility)
BTC,Bitcoin,50000.00,0.20,0.50,100.0,crypto,1
ETH,Ethereum,3000.00,0.20,0.50,500.0,crypto,1
# Altcoins (Very High Volatility)
SOL,Solana,150.00,0.30,0.80,2000.0,crypto,2
AVAX,Avalanche,40.00,0.30,0.80,3000.0,crypto,2
MATIC,Polygon,1.20,0.30,0.80,10000.0,crypto,2
ADA,Cardano,0.50,0.25,0.70,20000.0,crypto,3
DOT,Polkadot,8.00,0.25,0.70,5000.0,crypto,3
LINK,Chainlink,15.00,0.25,0.75,4000.0,crypto,3
# Stablecoins (Low Volatility)
USDT,Tether,1.00,0.00,0.01,50000.0,crypto,3
USDC,USD Coin,1.00,0.00,0.01,50000.0,crypto,3
```

**Why CSV?**
- ✅ Easy to edit (any text editor)
- ✅ Can add 1000+ tokens without code change
- ✅ Version control friendly (can see token changes in git diff)
- ✅ Supports loading from URLs for production flexibility

**Priority System:**
- **1 (High):** Always included (major stocks/crypto)
- **2 (Medium):** Included when budget allows
- **3 (Low):** Included only with excess budget

---

## Step 3: Create Token Registry Service

### Create TokenRegistryService

**File:** `src/main/java/com/quantstream/generator/service/TokenRegistryService.java`

```java
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
import java.net.URL;
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
}
```

**Key Design Points:**

**Separation of Concerns:**
- Registry = all available tokens (100+)
- Active = only what we're currently using (10)

**Why this matters:**
- Can have 10,000 tokens in CSV
- Run only 10 via config
- No code change to switch tokens

**Multiple Source Support:**
- **Classpath:** `classpath:tokens.csv` - Bundled in JAR
- **HTTP/HTTPS:** `https://...` - Load from web (GitHub, S3, etc.)
- **File System:** `file:/path` - Load from disk

**URL Loading Benefits:**
- ✅ Central config repository (one CSV for all instances)
- ✅ Update tokens without redeployment
- ✅ Version control (GitHub raw URLs with commit hash)
- ✅ A/B testing (different URLs for different environments)

**How URL Loading Works:**
```java
// 1. Check if source is HTTP/HTTPS
if (tokenSourcePath.startsWith("http://") || tokenSourcePath.startsWith("https://")) {
    // Use RestTemplate to fetch content
    RestTemplate restTemplate = new RestTemplate();
    String csvContent = restTemplate.getForObject(url, String.class);
}
// 2. Otherwise, use Spring Resource (classpath or file system)
else {
    InputStream stream = tokensResource.getInputStream();
    // ... read from stream
}
```

**Error Handling:**
- Invalid CSV line? Log error, skip line, continue
- Unknown symbol in config? Log warning, skip symbol, continue
- Empty registry? Throw exception (can't run without tokens)
- URL unreachable? IOException with clear error message (app won't start)

**Security Note:**
- URL loading uses RestTemplate (no authentication by default)
- For private repos/S3, use:
  - GitHub: Personal Access Token in URL
  - S3: Pre-signed URLs with temporary credentials
  - Or: Load from file system mounted with secrets

---

## Step 4: Update application.yml

**File:** `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: data-generator
  
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3

market:
  data:
    # Token source - supports multiple protocols:
    # - classpath:tokens.csv (default - loads from src/main/resources/)
    # - https://raw.githubusercontent.com/user/repo/main/tokens.csv (GitHub raw URL)
    # - https://mybucket.s3.amazonaws.com/tokens.csv (S3 pre-signed URL)
    # - file:/absolute/path/to/tokens.csv (local file system)
    token-source: classpath:tokens.csv
    
    # Comma-separated list of enabled token symbols
    # These are the only tokens that will generate ticks
    # Change this list without recompiling - just restart the app
    enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
    
    # Free tier limit: 10,000 messages per day
    # With 10 tokens at 1 msg/sec: 10 × 86,400 = 864,000 msg/day
    # We're WAY over for production, so we'll reduce frequency later
    max-messages-per-day: 10000

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
```

### Configuration Examples

#### Option 1: Local CSV (Development)
```yaml
market:
  data:
    token-source: classpath:tokens.csv
    enabled-symbols: AAPL,BTC,ETH  # Only 3 tokens for testing
```

#### Option 2: GitHub Raw URL (Production)
```yaml
market:
  data:
    token-source: https://raw.githubusercontent.com/YourOrg/quantstream-config/main/tokens.csv
    enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
```

**Benefits:**
- ✅ Update tokens without redeploying app
- ✅ Central token registry for multiple instances
- ✅ Version control in separate repo
- ✅ Easy rollback (change URL to previous commit)

#### Option 3: S3 Bucket (Cloud Deployment)
```yaml
market:
  data:
    token-source: https://quantstream-config.s3.us-east-1.amazonaws.com/tokens.csv
    enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
```

**For pre-signed URLs (temporary access):**
```yaml
market:
  data:
    token-source: https://quantstream-config.s3.amazonaws.com/tokens.csv?X-Amz-Signature=...
```

#### Option 4: File System (Custom Deployment)
```yaml
market:
  data:
    token-source: file:/etc/quantstream/tokens.csv
    enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
```

### Environment Variable Overrides

**Override token source:**
```bash
java -jar data-generator.jar \
  --market.data.token-source=https://example.com/tokens.csv
```

**Override enabled symbols:**
```bash
java -jar data-generator.jar \
  --market.data.enabled-symbols=AAPL,BTC
```

**Both:**
```bash
java -jar data-generator.jar \
  --market.data.token-source=https://example.com/tokens.csv \
  --market.data.enabled-symbols=AAPL,MSFT,BTC,ETH
```

### GitHub Integration Example

**1. Create separate config repo:**
```
quantstream-config/
├── tokens.csv         # Token registry (100+ tokens)
├── dev-enabled.txt    # AAPL,BTC,ETH
├── prod-enabled.txt   # AAPL,MSFT,GOOGL,...
└── README.md
```

**2. Configure application.yml:**
```yaml
market:
  data:
    token-source: https://raw.githubusercontent.com/YourOrg/quantstream-config/main/tokens.csv
    enabled-symbols: ${ENABLED_TOKENS:AAPL,BTC,ETH}
```

**3. Deploy with different tokens:**
```bash
# Development
export ENABLED_TOKENS=AAPL,BTC,ETH
java -jar data-generator.jar

# Production
export ENABLED_TOKENS=$(curl -s https://raw.githubusercontent.com/YourOrg/quantstream-config/main/prod-enabled.txt)
java -jar data-generator.jar
```

**Benefits:**
- ✅ Add new tokens: Git commit to config repo
- ✅ No app redeployment needed
- ✅ Token changes visible in git history
- ✅ Easy A/B testing (different branches)

---

## Step 5: Create Market Data Generator

**File:** `src/main/java/com/quantstream/generator/service/MarketDataGenerator.java`

```java
package com.quantstream.generator.service;

import com.quantstream.generator.model.Tick;
import com.quantstream.generator.model.TokenConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Market data generator that produces realistic tick data for configured tokens.
 * <p>
 * Design Philosophy:
 * - Loads token configuration from TokenRegistryService (data-driven)
 * - Creates simulators only for ACTIVE tokens (efficient)
 * - Runs at 1 msg/sec for development (will reduce for production)
 * - Demonstrates production-ready, scalable architecture
 * <p>
 * Scalability:
 * - Can handle 10,000+ tokens in registry
 * - Generates ticks only for enabled tokens
 * - Change token list via config (no recompile)
 */
@Service
@RequiredArgsConstructor
public class MarketDataGenerator {
    
    private static final Logger log = LoggerFactory.getLogger(MarketDataGenerator.class);
    
    // Kafka topic name
    private static final String TOPIC = "market-data";
    
    // Injected dependencies
    private final KafkaTemplate<String, Tick> kafkaTemplate;
    private final TokenRegistryService tokenRegistryService;
    
    // Map: symbol → price simulator (only for active tokens)
    private final Map<String, PriceSimulator> simulators = new HashMap<>();
    
    /**
     * Initializes price simulators for active tokens.
     * Called automatically by Spring after bean creation.
     */
    @PostConstruct
    public void init() {
        var activeTokens = tokenRegistryService.getActiveTokens();
        
        log.info("Initializing Market Data Generator");
        log.info("Total tokens in registry: {}", tokenRegistryService.getTotalTokenCount());
        log.info("Active tokens: {}", activeTokens.size());
        
        // Create simulator for each active token
        for (TokenConfig token : activeTokens) {
            PriceSimulator simulator = new PriceSimulator(
                token.initialPrice(),
                token.drift(),
                token.volatility()
            );
            
            simulators.put(token.symbol(), simulator);
            
            log.info("Initialized {} ({}): price=${}, drift={}%, volatility={}%, volume={}, priority={}",
                     token.symbol(),
                     token.name(),
                     token.initialPrice(),
                     token.drift() * 100,
                     token.volatility() * 100,
                     token.baseVolume(),
                     token.priority());
        }
        
        log.info("Market Data Generator ready. Will generate {} ticks per second.", activeTokens.size());
        
        // Log scalability info
        int tokensNotUsed = tokenRegistryService.getTotalTokenCount() - activeTokens.size();
        if (tokensNotUsed > 0) {
            log.info("Note: {} tokens available in registry but not enabled", tokensNotUsed);
            log.info("To enable more tokens, update 'market.data.enabled-symbols' in application.yml");
        }
    }
    
    /**
     * Generates and sends tick data for all active tokens.
     * Runs every 1000ms (1 second).
     * <p>
     * Note: This frequency is for development. In production, we'll reduce
     * to stay within free tier limits (10,000 messages/day).
     */
    @Scheduled(fixedRate = 1000)
    public void generateTicks() {
        Instant timestamp = Instant.now();
        var activeTokens = tokenRegistryService.getActiveTokens();
        
        for (TokenConfig tokenConfig : activeTokens) {
            String symbol = tokenConfig.symbol();
            
            try {
                // Get simulator for this token
                PriceSimulator simulator = simulators.get(symbol);
                if (simulator == null) {
                    log.error("No simulator found for active token: {}", symbol);
                    continue;
                }
                
                // Generate next price and volume
                double price = simulator.generateNextPrice();
                double volume = simulator.generateVolume(tokenConfig.baseVolume());
                
                // Create tick
                Tick tick = new Tick(symbol, price, volume, timestamp);
                
                // Send to Kafka (async)
                kafkaTemplate.send(TOPIC, symbol, tick)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tick for {}: {}", symbol, ex.getMessage());
                        } else {
                            log.debug("Tick sent: {} -> ${} (volume: {})", 
                                     symbol, String.format("%.2f", price), String.format("%.0f", volume));
                        }
                    });
                
            } catch (Exception e) {
                log.error("Error generating tick for {}: {}", symbol, e.getMessage(), e);
            }
        }
    }
}
```

---

## Understanding the Architecture

### Why This Design Works

**1. Separation of Data and Code**

```
┌─────────────────┐
│   tokens.csv    │  ← Data (22 tokens available)
└─────────────────┘
        ↓ (load on startup)
┌─────────────────┐
│ Registry Service│  ← Parse and filter
└─────────────────┘
        ↓ (inject)
┌─────────────────┐
│   Generator     │  ← Generate ticks (10 active tokens)
└─────────────────┘
        ↓ (every 1 second)
┌─────────────────┐
│     Kafka       │  ← Publish to market-data topic
└─────────────────┘
```

**Add tokens?** Edit CSV, restart app (no code change)

**2. Configuration-Driven**

```yaml
# Development: Test with 3 tokens
enabled-symbols: AAPL,BTC,ETH

# Full demo: 10 tokens
enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
```

Same code, different configs for different environments.

### Priority System

**Priority 1 (High) - Always Active:**
- Major stocks: AAPL, MSFT, GOOGL, NVDA, AMZN
- Major crypto: BTC, ETH

**Priority 2 (Medium) - Active if Budget Allows:**
- Growth stocks: TSLA, NFLX, META
- Mid-cap altcoins: SOL, AVAX, MATIC

**Priority 3 (Low) - Filler:**
- Banks, energy stocks
- Small altcoins, stablecoins

In Phase 6, we'll add `RateBudgetManager` that uses priority to decide which tokens get updates when budget is tight.

---

## Design Decisions & Tradeoffs

### Why CSV instead of Database (Phase 1)?

**✅ Benefits:**
- **Simple:** No database setup, schema migrations
- **Version control:** Git tracks token config changes
- **Portable:** Works anywhere, no infrastructure needed
- **Fast startup:** No connection pool, no queries
- **URL loading:** Can load from GitHub, S3, or local file

**❌ Limitations:**
- **Scale limit:** Works for 100s of tokens, not 10,000+
- **No dynamic updates:** Must restart to reload
- **No audit trail:** Can't see who changed what when
- **No complex queries:** Can't filter "top 10 by volume"

### Why Simulate Prices instead of Real APIs?

**✅ Benefits:**
- **Learning focus:** Understand GBM, drift, volatility concepts
- **No rate limits:** Generate 864k ticks/day (real APIs: 500/day free tier)
- **No cost:** Free tier APIs cannot handle our volume
- **Reproducible:** Control randomness for testing
- **Instant startup:** No API keys, no authentication

**❌ Limitations:**
- **Not real data:** Cannot analyze actual market behavior
- **No market events:** No capturing crashes, rallies, news impacts
- **No correlation:** Tokens move independently (real markets have correlation)
- **No order book:** Only price/volume, not bid/ask spreads

### When to Migrate to Database?

**Migrate when you have:**
- **1,000+ tokens** - CSV parsing becomes slow (>1 second startup)
- **Admin UI needed** - Want web interface to manage tokens
- **Audit requirements** - Need to track who changed what when
- **Complex queries** - "Top 10 by volume", "Filter by category", etc.
- **Dynamic enable/disable** - Change active tokens without restart

**Until then, CSV is perfect for learning and prototyping.**

---

## Testing the Complete System

### Step 1: Verify CSV Loads from Classpath

```bash
mvn clean compile
mvn spring-boot:run
```

**Expected output:**
```
INFO  c.q.g.s.TokenRegistryService     : Loading token registry from: classpath:tokens.csv
INFO  c.q.g.s.TokenRegistryService     : Loading from classpath/file: tokens.csv
DEBUG c.q.g.s.TokenRegistryService     : Loaded token: AAPL - Apple Inc.
DEBUG c.q.g.s.TokenRegistryService     : Loaded token: MSFT - Microsoft Corp.
...
INFO  c.q.g.s.TokenRegistryService     : Loaded 22 tokens from registry
INFO  c.q.g.s.TokenRegistryService     : Enabled symbols from config: [AAPL, MSFT, GOOGL, TSLA, AMZN, BTC, ETH, SOL, AVAX, MATIC]
INFO  c.q.g.s.TokenRegistryService     : Active tokens: 10 out of 22 available
INFO  c.q.g.s.TokenRegistryService     :   Active: AAPL (Apple Inc.) - priority=1, drift=8.0%, volatility=25.0%
INFO  c.q.g.s.TokenRegistryService     :   Active: MSFT (Microsoft Corp.) - priority=1, drift=8.0%, volatility=25.0%
...
```

### Step 2: Verify Generator Starts

```
INFO  c.q.g.s.MarketDataGenerator      : Initializing Market Data Generator
INFO  c.q.g.s.MarketDataGenerator      : Total tokens in registry: 22
INFO  c.q.g.s.MarketDataGenerator      : Active tokens: 10
INFO  c.q.g.s.MarketDataGenerator      : Initialized AAPL (Apple Inc.): price=$180.0, drift=8.0%, volatility=25.0%, volume=5000.0, priority=1
...
INFO  c.q.g.s.MarketDataGenerator      : Market Data Generator ready. Will generate 10 ticks per second.
INFO  c.q.g.s.MarketDataGenerator      : Note: 12 tokens available in registry but not enabled
INFO  c.q.g.s.MarketDataGenerator      : To enable more tokens, update 'market.data.enabled-symbols' in application.yml
```

### Step 3: Verify Ticks Generated

```
DEBUG c.q.g.s.MarketDataGenerator      : Tick sent: AAPL -> $180.05 (volume: 5200)
DEBUG c.q.g.s.MarketDataGenerator      : Tick sent: MSFT -> $380.12 (volume: 3800)
...
```

### Step 4: Test Token Changes

**Stop application (Ctrl+C)**

**Edit application.yml:**
```yaml
enabled-symbols: AAPL,BTC,ETH  # Only 3 tokens
```

**Restart:**
```bash
mvn spring-boot:run
```

**Verify:**
```
INFO  c.q.g.s.TokenRegistryService     : Active tokens: 3 out of 22 available
INFO  c.q.g.s.MarketDataGenerator      : Market Data Generator ready. Will generate 3 ticks per second.
```

**No code change, just config!** ✅

### Step 5: Verify in Kafka UI

1. Open http://localhost:8080
2. Navigate to Topics → market-data
3. Should see 10 messages per second (or 3 if you changed config)

---

## Optional: Testing URL Loading

The TokenRegistryService supports loading from URLs for production flexibility. This is optional but demonstrates scalability.

### Option A: Test with Local File URL

```bash
# Copy tokens.csv to test external loading
cp src/main/resources/tokens.csv /tmp/tokens-external.csv
```

**Update `application.yml`:**
```yaml
market:
  data:
    token-source: file:///tmp/tokens-external.csv
    enabled-symbols: AAPL,MSFT,GOOGL
```

**Run:**
```bash
mvn spring-boot:run
```

**Expected logs:**
```
INFO  c.q.g.s.TokenRegistryService     : Loading token registry from: file:///tmp/tokens-external.csv
INFO  c.q.g.s.TokenRegistryService     : Loading from URL: file:///tmp/tokens-external.csv
INFO  c.q.g.s.TokenRegistryService     : Successfully loaded 1234 bytes from URL
INFO  c.q.g.s.TokenRegistryService     : Loaded 22 tokens from registry
INFO  c.q.g.s.TokenRegistryService     : Active tokens: 3 out of 22 available
```

### Option B: Test with GitHub Gist

1. Create a GitHub Gist with `tokens.csv` content
2. Get raw URL: `https://gist.githubusercontent.com/<user>/<gist-id>/raw/<commit>/tokens.csv`
3. Update `application.yml`:
   ```yaml
   token-source: https://gist.githubusercontent.com/<user>/<gist-id>/raw/<commit>/tokens.csv
   ```
4. Run and verify URL loading works

**After testing, revert to classpath:**
```yaml
market:
  data:
    token-source: classpath:tokens.csv
    enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
```

---

## Adding New Tokens

### Example: Add NVIDIA (NVDA)

**1. Edit tokens.csv:**
```csv
NVDA,NVIDIA Corp.,450.00,0.20,0.45,7000.0,stock,1
```

**2. Add to enabled-symbols (application.yml):**
```yaml
enabled-symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC,NVDA
```

**3. Restart application**

**4. Verify:**
```
INFO : Loaded 23 tokens from registry
INFO : Active tokens: 11 out of 23 available
INFO : Initialized NVDA (NVIDIA Corp.): price=$450.0, drift=20.0%, volatility=45.0%
```

**That's it!** No Java code change.

---

## Summary

### What We Built

**✅ Data-Driven Architecture:**
- Token configs in CSV (not hardcoded)
- Can have 10,000+ tokens in registry
- Use only what config specifies

**✅ Production-Ready Design:**
- Separation of data and code
- Configuration-driven (no recompile for changes)
- Clear migration path to database
- Priority system for rate limiting

**✅ Scalable:**
- Add 1000 tokens: Edit CSV
- Change active set: Edit config
- Same code for dev/staging/prod

### Current Scale
- Registry: 22 tokens
- Active: 10 tokens
- Frequency: 1 msg/sec (for development)

### Production Scale (Phase 6)
- Registry: 100+ tokens
- Active: 10 tokens (free tier limit)
- Frequency: 1 msg/2min (7,200 msg/day, under 10,000 limit)

### Flow
```
CSV (classpath) → TokenRegistryService → In-Memory Map → Active Tokens → GBM Simulation → Kafka
```

**Next:** Task 5 - Create Database Consumer to read from Kafka and write to QuestDB.

---

## Future Enhancements (Phase 6+)

### 1. Database Token Registry

**When to migrate:**
- You have 1,000+ tokens (CSV parsing becomes slow)
- You need admin UI to manage tokens
- You need audit trail (who changed what when)
- You need complex queries (top 10 by volume, filter by category)

**Implementation:**

**Create table:**
```sql
CREATE TABLE token_registry (
    symbol SYMBOL,
    name STRING,
    initial_price DOUBLE,
    drift DOUBLE,
    volatility DOUBLE,
    base_volume DOUBLE,
    category SYMBOL,
    priority INT,
    is_active BOOLEAN,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
) timestamp(created_at);
```

**Add database loading to TokenRegistryService:**
```java
@Value("${market.data.token-source:csv}")
private String tokenSource;

@Autowired(required = false)
private JdbcTemplate jdbcTemplate;

@PostConstruct
public void loadTokenRegistry() {
    if ("database".equals(tokenSource) && jdbcTemplate != null) {
        loadFromDatabase();
    } else {
        loadFromCsv();
    }
}

private void loadFromDatabase() {
    String sql = "SELECT * FROM token_registry WHERE is_active = true";
    List<TokenConfig> tokens = jdbcTemplate.query(sql, 
        (rs, rowNum) -> new TokenConfig(
            rs.getString("symbol"),
            rs.getString("name"),
            rs.getDouble("initial_price"),
            rs.getDouble("drift"),
            rs.getDouble("volatility"),
            rs.getDouble("base_volume"),
            rs.getString("category"),
            rs.getInt("priority")
        )
    );
    
    tokens.forEach(token -> tokenRegistry.put(token.symbol(), token));
    log.info("Loaded {} tokens from database", tokens.size());
}
```

**Switch config:**
```yaml
market:
  data:
    token-source: database
```

**Benefits:**
- ✅ REST API for token CRUD operations
- ✅ Admin UI for token management
- ✅ Audit trail (created_at, updated_at)
- ✅ Complex queries (top N by volume, filter by category)
- ✅ Dynamic enable/disable without restart

---

### 2. Real Market Data Integration

**Free Tier APIs:**
- **Alpha Vantage:** 500 calls/day for stocks (25 calls/hour)
- **CoinGecko:** 50 calls/minute for crypto (10,000/month free)
- **Yahoo Finance:** Unofficial API, rate limits vary

**Hybrid Approach:**
```java
public double getPrice(String symbol, TokenConfig config) {
    // Priority 1: Try real API first
    if (config.priority() == 1 && apiCallBudget.hasQuota()) {
        try {
            double realPrice = marketDataApi.getPrice(symbol);
            return realPrice;
        } catch (RateLimitException e) {
            log.warn("API rate limit hit for {}, falling back to simulation", symbol);
        }
    }
    
    // Priority 2/3 or API unavailable: Use simulator
    return priceSimulator.generateNextPrice();
}
```

**Benefits:**
- ✅ Real data for high-priority tokens (AAPL, BTC)
- ✅ Simulated data for low-priority tokens (reduces API calls)
- ✅ Graceful fallback when API fails
- ✅ Cache recent prices to reduce API calls

**When to implement:**
- After Phase 1-5 are complete and working
- When you want to analyze real market behavior
- When you have API keys and understand rate limits

---

### 3. Advanced Configuration Features

**Dynamic Token Management:**
```java
@RestController
@RequestMapping("/api/tokens")
public class TokenManagementController {
    
    @PostMapping("/{symbol}/enable")
    public ResponseEntity<?> enableToken(@PathVariable String symbol) {
        tokenRegistryService.enableToken(symbol);
        // Hot-reload simulators without restart
        marketDataGenerator.addSimulator(symbol);
        return ResponseEntity.ok("Token enabled");
    }
    
    @PostMapping("/{symbol}/disable")
    public ResponseEntity<?> disableToken(@PathVariable String symbol) {
        tokenRegistryService.disableToken(symbol);
        marketDataGenerator.removeSimulator(symbol);
        return ResponseEntity.ok("Token disabled");
    }
}
```

**Configuration Hot-Reload:**
```java
@Scheduled(fixedRate = 60000) // Check every minute
public void reloadConfiguration() {
    if (configurationChanged()) {
        log.info("Configuration changed, reloading...");
        tokenRegistryService.reload();
        marketDataGenerator.reinitialize();
    }
}
```

**Multiple Data Sources per Token:**
```yaml
tokens:
  - symbol: AAPL
    sources:
      - type: api
        provider: alpha_vantage
        priority: 1
      - type: simulator
        priority: 2  # Fallback
```

**When to implement:**
- When you need zero-downtime token changes
- When you want to A/B test different configurations
- When you need failover between data sources

---

### 4. Production Deployment Features

**Load from GitHub/S3 for centralized config:**
```yaml
market:
  data:
    token-source: https://raw.githubusercontent.com/YourOrg/quantstream-config/main/tokens.csv
    # OR: https://s3.amazonaws.com/quantstream-config/tokens.csv
```

**Benefits:**
- ✅ One CSV for all instances
- ✅ Update tokens without redeployment
- ✅ Version control (git commit history)
- ✅ A/B testing (different branches/commits)

**Security:**
- GitHub: Use Personal Access Token in URL
- S3: Use pre-signed URLs or IAM roles
- Or: Mount file system with secrets manager

**Production Config Management:**
```bash
# Development
export TOKEN_SOURCE=classpath:tokens.csv
export ENABLED_TOKENS=AAPL,BTC,ETH

# Staging
export TOKEN_SOURCE=https://raw.githubusercontent.com/.../tokens-staging.csv
export ENABLED_TOKENS=$(curl -s https://.../staging-enabled.txt)

# Production
export TOKEN_SOURCE=https://s3.amazonaws.com/quantstream/tokens.csv
export ENABLED_TOKENS=$(aws ssm get-parameter --name /quantstream/enabled-tokens --query 'Parameter.Value' --output text)
```

---
