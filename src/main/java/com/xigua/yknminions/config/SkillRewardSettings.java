package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;

import java.util.Objects;

public record SkillRewardSettings(
        ItemSpec item,
        double xpPerBaseUnit,
        double equivalent
) {
    public SkillRewardSettings {
        Objects.requireNonNull(item, "item");
        if (!Double.isFinite(xpPerBaseUnit) || xpPerBaseUnit < 0) {
            throw new IllegalArgumentException("xpPerBaseUnit must be finite and non-negative");
        }
        if (!Double.isFinite(equivalent) || equivalent <= 0) {
            throw new IllegalArgumentException("equivalent must be finite and positive");
        }
        if (!Double.isFinite(Integer.MAX_VALUE * equivalent * xpPerBaseUnit)) {
            throw new IllegalArgumentException("configured skill experience can overflow");
        }
    }

    public double experience(long deliveredAmount) {
        if (deliveredAmount < 0) {
            throw new IllegalArgumentException("deliveredAmount must not be negative");
        }
        double result = deliveredAmount * equivalent * xpPerBaseUnit;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("skill experience overflow");
        }
        return result;
    }
}
