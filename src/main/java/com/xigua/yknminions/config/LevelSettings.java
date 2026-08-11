package com.xigua.yknminions.config;

import java.util.List;

public record LevelSettings(int level, double workIntervalSeconds, List<Requirement> upgradeMaterials) {
    public LevelSettings {
        upgradeMaterials = List.copyOf(upgradeMaterials);
        if (level < 1 || workIntervalSeconds <= 0) throw new IllegalArgumentException("Invalid level settings");
    }
}
