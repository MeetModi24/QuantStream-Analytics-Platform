package com.quantstream.strategy.config;

import com.quantstream.strategy.model.Signal;
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
 * Kafka producer configuration for sending trading signals.
 * 
 * Producer sends Signal objects as JSON to "trading-signals" topic.
 * Database-consumer service consumes and writes these signals to QuestDB.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Signal> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Kafka broker address (from application.yml)
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Serializers
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Reliability settings
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");  // Wait for leader ack
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3); // Retry 3 times
        configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000); // Wait 1s between retries
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Signal> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}