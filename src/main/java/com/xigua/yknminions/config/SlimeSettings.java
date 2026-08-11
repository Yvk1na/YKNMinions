package com.xigua.yknminions.config;

import com.xigua.yknminions.util.RandomRange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public record SlimeSettings(Map<Integer, Double> sizeChances, RandomRange dropPerKill, long turnDelayTicks) {
    public SlimeSettings {
        LinkedHashMap<Integer, Double> valid = new LinkedHashMap<>();
        sizeChances.forEach((size, chance) -> {
            if (size != null && chance != null && size > 0 && size <= 127 && chance > 0.0 && Double.isFinite(chance)) {
                valid.put(size, chance);
            }
        });
        if (valid.isEmpty()) valid.put(1, 100.0);
        sizeChances = Map.copyOf(valid);
        turnDelayTicks = Math.max(1L, Math.min(200L, turnDelayTicks));
    }

    public int rollSize() {
        double total = sizeChances.values().stream().mapToDouble(Double::doubleValue).sum();
        double selected = ThreadLocalRandom.current().nextDouble(total);
        for (Map.Entry<Integer, Double> entry : sizeChances.entrySet()) {
            selected -= entry.getValue();
            if (selected < 0.0) return entry.getKey();
        }
        return sizeChances.keySet().iterator().next();
    }
}
