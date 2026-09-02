package com.xigua.yknminions.config;

import com.xigua.yknminions.item.MinionItemAccess;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record SkillSettings(
        String provider,
        String skillId,
        List<SkillRewardSettings> rewards
) {
    public SkillSettings {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("skill provider must not be blank");
        }
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId must not be blank");
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        skillId = skillId.trim().toLowerCase(Locale.ROOT);
        if (!skillId.matches("[a-z0-9._-]+(?::[a-z0-9/._-]+)?")) {
            throw new IllegalArgumentException("skillId has invalid characters");
        }
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("skill rewards must not be empty");
        }
    }

    public Optional<SkillRewardSettings> reward(ItemStack item, MinionItemAccess resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return rewards.stream().filter(reward -> resolver.matches(item, reward.item())).findFirst();
    }
}
