package com.quantstream.consumer.config;

import com.quantstream.consumer.model.Candle;
import com.quantstream.consumer.model.Signal;
import com.quantstream.consumer.model.Tick;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer configuration for three types of messages:
 * 1. Tick - raw market data
 * 2. Candle - aggregated OHLC data
 * 3. Signal - trading signals
 * 
 * Each type has:
 * - Its own ConsumerFactory (for deserialization)
 * - Its own KafkaListenerContainerFactory (for threading/batching)
 * - Its own consumer group ID (for independent offset tracking)
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ============================================================
    // TICK CONSUMER (existing, unchanged)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Tick> tickConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-consumer-group");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Tick.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tickConsumerFactory());
        // Enable batch mode
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        return factory;
    }

    // ============================================================
    // CANDLE CONSUMER (NEW - batch processing)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Candle> candleConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // SEPARATE consumer group (independent offset tracking)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-candles-consumer-group");
        
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // BATCH PROCESSING: Fetch up to 500 candles at once
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Candle.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Candle> candleKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Candle> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(candleConsumerFactory());
        
        // BATCH MODE: Deliver messages in batches (not one-by-one)
        factory.setBatchListener(true);
        
        // 3 concurrent threads (matches Kafka topic partitions)
        factory.setConcurrency(3);
        
        // Manual acknowledgment (commit after successful batch insert)
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        
        return factory;
    }

    // ============================================================
    // SIGNAL CONSUMER (NEW - real-time processing)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Signal> signalConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // SEPARATE consumer group (independent offset tracking)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-signals-consumer-group");
        
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // INDIVIDUAL PROCESSING: Small batch size for low latency
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Signal.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Signal> signalKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Signal> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(signalConsumerFactory());
        
        // SINGLE MESSAGE MODE (not batch)
        factory.setBatchListener(false);
        
        // Single thread (signals are low-volume)
        factory.setConcurrency(1);
        
        // Manual acknowledgment
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        
        return factory;
    }
}