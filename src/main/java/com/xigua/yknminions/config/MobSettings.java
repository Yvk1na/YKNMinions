package com.xigua.yknminions.config;

import com.xigua.yknminions.util.RandomRange;
import org.bukkit.entity.EntityType;

public record MobSettings(EntityType entityType, RandomRange dropPerKill, long turnDelayTicks) {
    public MobSettings {
        if (entityType == null || !entityType.isAlive() || !entityType.isSpawnable()) {
            throw new IllegalArgumentException("Target entity must be a spawnable living entity");
        }
        turnDelayTicks = Math.max(1L, Math.min(200L, turnDelayTicks));
    }
}
