package com.quantstream.aggregator.serdes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Generic JSON Serde for Kafka Streams.
 * 
 * Handles serialization/deserialization of POJOs to/from JSON.
 * 
 * Usage:
 *   Serde<Tick> tickSerde = JsonSerde.of(Tick.class);
 */
public class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
        this.objectMapper = new ObjectMapper();
        // Register JSR310 module for Instant/LocalDateTime support
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Factory method for creating Serde.
     */
    public static <T> Serde<T> of(Class<T> type) {
        return new JsonSerde<>(type);
    }

    @Override
    public Serializer<T> serializer() {
        return new JsonSerializer();
    }

    @Override
    public Deserializer<T> deserializer() {
        return new JsonDeserializer();
    }

    /**
     * JSON Serializer
     */
    private class JsonSerializer implements Serializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No configuration needed
        }

        @Override
        public byte[] serialize(String topic, T data) {
            if (data == null) {
                return null;
            }
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Error serializing JSON", e);
            }
        }

        @Override
        public void close() {
            // No resources to close
        }
    }

    /**
     * JSON Deserializer
     */
    private class JsonDeserializer implements Deserializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No configuration needed
        }

        @Override
        public T deserialize(String topic, byte[] data) {
            if (data == null) {
                return null;
            }
            try {
                return objectMapper.readValue(data, type);
            } catch (Exception e) {
                throw new RuntimeException("Error deserializing JSON", e);
            }
        }

        @Override
        public void close() {
            // No resources to close
        }
    }
}