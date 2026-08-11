package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.util.RandomRange;

import java.util.Map;

public record MinionType(String id, String displayName, ItemSpec drop, RandomRange dropAmount,
                         String workSound, ModelSettings model, MobSettings mobSettings,
                         SlimeSettings slimeSettings, MiningSettings miningSettings,
                         FarmingSettings farmingSettings,
                         Map<Integer, LevelSettings> levels) {
    public MinionType {
        levels = Map.copyOf(levels);
    }

    public LevelSettings level(int level) {
        LevelSettings exact = levels.get(level);
        if (exact != null) return exact;
        return levels.entrySet().stream()
                .filter(entry -> entry.getKey() <= level)
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new IllegalStateException("Minion type " + id + " has no level settings"));
    }
}
