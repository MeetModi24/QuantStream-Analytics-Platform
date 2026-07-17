package com.quantstream.strategy.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * QuestDB-specific DataSource configuration.
 * 
 * QuestDB limitations:
 * - No transaction support (no BEGIN/COMMIT/ROLLBACK)
 * - No transaction isolation levels
 * - No savepoints
 * 
 * This config bypasses HikariCP's transaction checks to prevent errors.
 */
@Configuration
public class QuestDBConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    
    @Value("${spring.datasource.username}")
    private String username;
    
    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        
        // Connection details (from application.yml)
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Bypass transaction support checks
        config.setAutoCommit(true);
        config.setConnectionInitSql("SELECT 1");
        config.setConnectionTestQuery("SELECT 1");
        config.setTransactionIsolation(null);  // CRITICAL: QuestDB doesn't support isolation

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}