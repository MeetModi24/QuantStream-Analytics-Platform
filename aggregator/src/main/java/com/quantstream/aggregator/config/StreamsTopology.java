package com.quantstream.aggregator.config;

import com.quantstream.aggregator.model.Candle;
import com.quantstream.aggregator.model.Tick;
import com.quantstream.aggregator.serdes.JsonSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Streams topology for OHLC candle aggregation.
 *
 * Flow:
 * 1. Read ticks from market-data topic
 * 2. Group by symbol
 * 3. Window into 1-minute tumbling windows
 * 4. Aggregate into OHLC candles
 * 5. Write candles to candles-1m topic
 */
@Configuration
@EnableKafkaStreams
public class StreamsTopology {

    private static final Logger log = LoggerFactory.getLogger(StreamsTopology.class);

    private static final String INPUT_TOPIC = "market-data";
    private static final String OUTPUT_TOPIC = "candles-1m";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.streams.application-id}")
    private String applicationId;

    @Value("${spring.kafka.streams.state-dir:/tmp/kafka-streams}")
    private String stateDir;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration defaultKafkaStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        // At-least-once semantics (suitable for single-broker dev setup)
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public KStream<String, Candle> kStream(StreamsBuilder builder) {
        
        // Define Serdes
        Serde<String> stringSerde = Serdes.String();
        Serde<Tick> tickSerde = JsonSerde.of(Tick.class);
        Serde<Candle> candleSerde = JsonSerde.of(Candle.class);

        // 1. Read ticks from input topic
        KStream<String, Tick> tickStream = builder.stream(
            INPUT_TOPIC,
            Consumed.with(stringSerde, tickSerde)
                .withTimestampExtractor(new TickTimestampExtractor())
        );

        // 2. Group by symbol
        KGroupedStream<String, Tick> groupedBySymbol = tickStream
            .groupBy(
                (key, tick) -> tick.getSymbol(),
                Grouped.with(stringSerde, tickSerde)
            );

        // 3. Window into 1-minute tumbling windows
        TimeWindowedKStream<String, Tick> windowedStream = groupedBySymbol
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)));

        // 4. Aggregate into OHLC candles
        KTable<Windowed<String>, Candle> candleTable = windowedStream
            .aggregate(
                // Initializer: Create empty candle
                () -> null,
                
                // Aggregator: Update candle with each tick
                (key, tick, candle) -> {
                    if (candle == null) {
                        // First tick in window - initialize candle
                        return new Candle(
                            tick.getSymbol(),
                            tick.getPrice(),     // open = first price
                            tick.getPrice(),     // high = first price
                            tick.getPrice(),     // low = first price
                            tick.getPrice(),     // close = first price
                            tick.getVolume(),    // volume = first volume
                            tick.getTimestamp()  // timestamp = first tick time
                        );
                    } else {
                        // Update existing candle
                        candle.setHigh(Math.max(candle.getHigh(), tick.getPrice()));
                        candle.setLow(Math.min(candle.getLow(), tick.getPrice()));
                        candle.setClose(tick.getPrice());  // Last tick price
                        candle.setVolume(candle.getVolume() + tick.getVolume());
                        return candle;
                    }
                },
                
                // Materialized: State store config
                Materialized.with(stringSerde, candleSerde)
            );

        // 5. Convert windowed KTable to KStream and write to output
        KStream<String, Candle> candleStream = candleTable
            .toStream()
            .map((windowedKey, candle) -> {
                // Extract symbol from windowed key
                String symbol = windowedKey.key();
                
                // Set timestamp to window start time
                candle.setTimestamp(windowedKey.window().startTime());
                
                log.info("Emitting candle: {} @ {} (O:{} H:{} L:{} C:{} V:{})",
                    symbol, 
                    candle.getTimestamp(),
                    candle.getOpen(),
                    candle.getHigh(),
                    candle.getLow(),
                    candle.getClose(),
                    candle.getVolume()
                );
                
                return KeyValue.pair(symbol, candle);
            });

        // Write to output topic
        candleStream.to(OUTPUT_TOPIC, Produced.with(stringSerde, candleSerde));

        return candleStream;
    }

    /**
     * Custom timestamp extractor - use tick's event time, not processing time.
     * 
     * Why: Windowing should be based on when tick occurred, not when we process it.
     */
    private static class TickTimestampExtractor implements TimestampExtractor {
        @Override
        public long extract(org.apache.kafka.clients.consumer.ConsumerRecord<Object, Object> record, long partitionTime) {
            Tick tick = (Tick) record.value();
            if (tick != null && tick.getTimestamp() != null) {
                return tick.getTimestamp().toEpochMilli();
            }
            // Fallback to record timestamp if tick timestamp missing
            return record.timestamp();
        }
    }
}