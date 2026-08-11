package com.xigua.yknminions.util;

import java.util.concurrent.ThreadLocalRandom;

public record RandomRange(int min, int max) {
    public RandomRange {
        if (min < 0 || max < min) throw new IllegalArgumentException("Invalid range: " + min + "~" + max);
    }

    public static RandomRange parse(Object raw, int fallback) {
        if (raw instanceof Number number) {
            int value = Math.max(0, number.intValue());
            return new RandomRange(value, value);
        }
        String value = String.valueOf(raw == null ? fallback : raw).trim();
        try {
            String[] split = value.split("~", 2);
            int min = Integer.parseInt(split[0].trim());
            int max = split.length == 1 ? min : Integer.parseInt(split[1].trim());
            return new RandomRange(min, max);
        } catch (RuntimeException ignored) {
            return new RandomRange(fallback, fallback);
        }
    }

    public int roll() {
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
