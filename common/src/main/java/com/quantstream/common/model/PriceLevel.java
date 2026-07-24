package com.quantstream.common.model;

/**
 * A single price level in an order book (one rung of the bid or ask ladder).
 *
 * @param price  the price at this level
 * @param volume total shares/units resting at this price
 * @param level  1-based depth position (1 = best/top of book)
 */
public record PriceLevel(double price, double volume, int level) {
}
