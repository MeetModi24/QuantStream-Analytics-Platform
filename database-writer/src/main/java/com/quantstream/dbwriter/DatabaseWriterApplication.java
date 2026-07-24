package com.quantstream.dbwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Database Writer service.
 *
 * <p>Consumes the {@code order-book-data} and {@code features} Kafka topics and
 * persists them to QuestDB. This is the durable store for the dashboard/analysis
 * path — the live trading path never reads from it (see
 * {@code docs/planning/03-historical-data-and-retention.md}).
 */
@SpringBootApplication
public class DatabaseWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseWriterApplication.class, args);
    }
}
