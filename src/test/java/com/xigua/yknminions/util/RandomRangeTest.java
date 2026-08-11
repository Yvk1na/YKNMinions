package com.xigua.yknminions.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RandomRangeTest {
    @Test
    void parsesFixedAndRandomRanges() {
        assertEquals(new RandomRange(4, 4), RandomRange.parse("4", 1));
        assertEquals(new RandomRange(1, 5), RandomRange.parse("1~5", 1));
    }

    @Test
    void rollStaysInsideRange() {
        RandomRange range = new RandomRange(2, 4);
        for (int i = 0; i < 100; i++) {
            int value = range.roll();
            assertTrue(value >= 2 && value <= 4);
        }
    }
}
