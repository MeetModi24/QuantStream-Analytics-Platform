package com.quantstream.strategy.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingWindowTest {

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingWindow(-5));
    }

    @Test
    void fillsThenReportsFull() {
        RollingWindow w = new RollingWindow(3);
        assertFalse(w.isFull());
        w.add(1);
        w.add(2);
        assertEquals(2, w.size());
        assertFalse(w.isFull());
        w.add(3);
        assertTrue(w.isFull());
        assertEquals(3, w.size());
    }

    @Test
    void meanAndStdDevOverPartialWindow() {
        RollingWindow w = new RollingWindow(10);
        w.add(2);
        w.add(4);
        w.add(4);
        w.add(4);
        w.add(5);
        w.add(5);
        w.add(7);
        w.add(9);
        // Classic dataset: mean 5, sample stdev 2.138... (n-1 = 7 denominator).
        assertEquals(5.0, w.mean(), 1e-9);
        assertEquals(Math.sqrt(32.0 / 7.0), w.stdDev(), 1e-9);
    }

    @Test
    void evictsOldestOnceFull() {
        RollingWindow w = new RollingWindow(3);
        w.add(1);
        w.add(2);
        w.add(3);          // [1,2,3] mean 2
        assertEquals(2.0, w.mean(), 1e-9);
        w.add(6);          // evict 1 -> [2,3,6] mean 3.6667
        assertEquals((2 + 3 + 6) / 3.0, w.mean(), 1e-9);
        assertEquals(3, w.size());
    }

    @Test
    void stdDevIsZeroForConstantOrTinyWindow() {
        RollingWindow w = new RollingWindow(5);
        assertEquals(0.0, w.stdDev());   // empty
        w.add(42);
        assertEquals(0.0, w.stdDev());   // one obs
        w.add(42);
        w.add(42);
        assertEquals(0.0, w.stdDev());   // constant -> no dispersion
    }
}
