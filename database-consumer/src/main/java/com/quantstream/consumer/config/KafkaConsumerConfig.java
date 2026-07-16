package com.quantstream.consumer.config;

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
 * Kafka Consumer configuration for receiving Tick messages.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:questdb-consumer-group}")
    private String groupId;

    /**
     * Creates ConsumerFactory with configuration for String keys and Tick values.
     */
    @Bean
    public ConsumerFactory<String, Tick> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Kafka broker address
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Consumer group ID (CRITICAL - identifies this consumer group)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        
        // Key deserializer (bytes → String)
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // Value deserializer (bytes → JSON → Tick)
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        
        // JsonDeserializer specific: trust our Tick class
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        
        // Start reading from earliest message if no previous offset
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // MANUAL commit (acknowledgment.acknowledge() in listener)
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // How much data to fetch in one request
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Tick.class, false)  // false = don't use type headers
        );
    }

    /**
     * Creates KafkaListenerContainerFactory for @KafkaListener annotations.
     * This manages consumer threads and message delivery.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // Number of concurrent consumer threads (parallelism)
        factory.setConcurrency(3);

        // Enable manual acknowledgment mode (required for Acknowledgment parameter)
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );

        return factory;
    }
}