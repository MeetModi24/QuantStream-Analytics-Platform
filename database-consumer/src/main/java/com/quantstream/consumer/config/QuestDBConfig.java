package com.quantstream.consumer.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * QuestDB-specific configuration.
 * <p>
 * QuestDB has limited PostgreSQL compatibility - it doesn't support:
 * - Transaction isolation levels
 * - BEGIN/COMMIT/ROLLBACK (no transactions)
 * - Savepoints
 * <p>
 * This configuration bypasses HikariCP's transaction checks.
 */
@Configuration
public class QuestDBConfig {

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:8812/qdb");
        config.setUsername("admin");
        config.setPassword("quest");
        config.setDriverClassName("org.postgresql.Driver");

        // Bypass transaction isolation level detection (QuestDB doesn't support it)
        config.setAutoCommit(true);
        config.setConnectionInitSql("SELECT 1");
        config.setConnectionTestQuery("SELECT 1");

        // Don't set transaction isolation (causes errors with QuestDB)
        config.setTransactionIsolation(null);

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);

        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
