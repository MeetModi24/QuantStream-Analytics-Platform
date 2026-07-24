package com.quantstream.strategy.spi;

/**
 * Fixed-capacity ring buffer of {@code double} observations with O(1) mean and
 * sample-standard-deviation, for stateful strategies that keep a lookback window.
 *
 * <p>This is the in-memory "lookback" from the retention design: it is filled forward
 * from the live stream (never backfilled), and once full the oldest value is evicted as
 * each new one arrives. Mean and variance are maintained incrementally via running sums
 * of {@code x} and {@code x²}, so a strategy can recompute its z-score every tick without
 * rescanning the window.
 *
 * <p>Not thread-safe — a strategy instance is only ever touched by one thread at a time
 * (per-token Kafka partition ordering), which the {@code Strategy} contract guarantees.
 *
 * <p><b>Numerical note:</b> running sums can accumulate floating-point error over a very
 * long stream. Because the window is bounded (old contributions are subtracted back out)
 * and prices are O(10²–10⁵), drift is negligible for this use; a variance clamped at 0
 * guards the one case where rounding could produce a tiny negative.
 */
public final class RollingWindow {

    private final double[] buffer;
    private int size;
    private int head;      // index of the next write (oldest element once full)
    private double sum;
    private double sumSq;

    public RollingWindow(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.buffer = new double[capacity];
    }

    /** Adds an observation, evicting the oldest once the window is full. */
    public void add(double value) {
        if (size == buffer.length) {
            double evicted = buffer[head];
            sum -= evicted;
            sumSq -= evicted * evicted;
        } else {
            size++;
        }
        buffer[head] = value;
        sum += value;
        sumSq += value * value;
        head = (head + 1) % buffer.length;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return buffer.length;
    }

    public boolean isFull() {
        return size == buffer.length;
    }

    public double mean() {
        return size == 0 ? 0.0 : sum / size;
    }

    /** Sample standard deviation (n−1 denominator); 0 while fewer than 2 observations. */
    public double stdDev() {
        if (size < 2) {
            return 0.0;
        }
        double mean = sum / size;
        double variance = (sumSq - size * mean * mean) / (size - 1);
        return variance <= 0.0 ? 0.0 : Math.sqrt(variance);
    }
}
