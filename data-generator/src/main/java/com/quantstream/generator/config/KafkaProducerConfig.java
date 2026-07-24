package com.quantstream.generator.config;

import com.quantstream.generator.model.Candle;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer configuration for sending Candle messages.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Creates ProducerFactory with configuration for String keys and Candle values.
     */
    @Bean
    public ProducerFactory<String, Candle> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Kafka broker address
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Key serializer (String → bytes)
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Value serializer (Candle → JSON → bytes)
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability: wait for all replicas to acknowledge
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");

        // Retry failed sends up to 3 times
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);

        // Compression (saves bandwidth)
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        // Batch settings for efficiency
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Creates KafkaTemplate for sending messages.
     * This is the high-level API you'll use in your services.
     */
    @Bean
    public KafkaTemplate<String, Candle> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}