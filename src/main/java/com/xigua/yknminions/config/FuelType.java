package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;

public record FuelType(String id, ItemSpec item, double efficiency,
                       long burnTimeSeconds, boolean infinite) {
    public FuelType {
        if (!Double.isFinite(efficiency) || efficiency < 0
                || (!infinite && burnTimeSeconds <= 0)) {
            throw new IllegalArgumentException("Invalid fuel " + id);
        }
    }

    public double speedMultiplier() {
        return 1.0 + efficiency;
    }
}
