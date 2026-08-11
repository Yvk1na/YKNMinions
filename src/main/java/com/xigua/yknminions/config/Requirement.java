package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;

public record Requirement(ItemSpec item, int amount) {
    public Requirement {
        if (amount <= 0) throw new IllegalArgumentException("Requirement amount must be positive");
    }
}
