package com.xigua.yknminions.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/** Immutable item and Skill XP rule captured when a delivery claim is prepared. */
public record PreparedClaimLine(
        ItemStack item,
        String skillProvider,
        String skillId,
        double xpPerBaseUnit,
        double equivalent
) {
    public PreparedClaimLine {
        Objects.requireNonNull(item, "item");
        if (item.getType() == Material.AIR || item.getAmount() <= 0) {
            throw new IllegalArgumentException("claim line item must not be empty");
        }
        item = item.clone();
        boolean hasSkill = skillProvider != null || skillId != null;
        if (hasSkill) {
            if (skillProvider == null || skillProvider.isBlank()
                    || skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("claim line skill identity is incomplete");
            }
            if (!Double.isFinite(xpPerBaseUnit) || xpPerBaseUnit < 0
                    || !Double.isFinite(equivalent) || equivalent <= 0) {
                throw new IllegalArgumentException("claim line skill rate is invalid");
            }
            if (!Double.isFinite(item.getAmount() * equivalent * xpPerBaseUnit)) {
                throw new IllegalArgumentException("claim line skill experience overflows");
            }
        } else if (xpPerBaseUnit != 0 || equivalent != 1) {
            throw new IllegalArgumentException("claim line without a skill must use neutral rates");
        }
    }

    public static PreparedClaimLine withoutSkill(ItemStack item) {
        return new PreparedClaimLine(item, null, null, 0, 1);
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    public Optional<String> skillProviderOptional() {
        return Optional.ofNullable(skillProvider);
    }

    public double experience(long deliveredAmount) {
        if (deliveredAmount < 0 || deliveredAmount > item.getAmount()) {
            throw new IllegalArgumentException("deliveredAmount is outside the claim line");
        }
        double result = deliveredAmount * equivalent * xpPerBaseUnit;
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("skill experience overflow");
        }
        return result;
    }
}
