package com.xigua.yknminions.config;

import org.bukkit.Material;

public record MiningSettings(Material block) {
    public MiningSettings {
        if (block == null || !block.isBlock() || block.isAir()) {
            throw new IllegalArgumentException("Mining minion block must be a placeable block");
        }
    }
}
