package com.quantstream.generator;

import com.quantstream.common.config.TokenRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the shared {@link TokenRegistry} (loaded from {@code tokens.yml} on the
 * classpath) as a Spring bean so services can inject the token universe.
 */
@Configuration
public class TokenRegistryConfig {

    @Bean
    public TokenRegistry tokenRegistry() {
        return TokenRegistry.fromClasspath();
    }
}
