package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.util.RandomRange;

public record SpreadSettings(String specialItemId, ItemSpec reward, double chance, RandomRange amount) {
    public SpreadSettings {
        chance = Math.max(0.0, Math.min(1.0, chance));
    }
}
