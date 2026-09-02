package com.xigua.yknminions.model;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** YAML compatibility boundary for recoverable Minion delivery claims. */
public final class PreparedClaimCodec {
    private PreparedClaimCodec() { }

    public static void save(ConfigurationSection minionSection, PreparedClaim claim,
                            boolean blocked) {
        Objects.requireNonNull(minionSection, "minionSection");
        minionSection.set("prepared-claim", null);
        if (claim == null) return;
        minionSection.set("prepared-claim.state", blocked ? "BLOCKED" : "PREPARED");
        minionSection.set("prepared-claim.id", claim.id().toString());
        minionSection.set("prepared-claim.recipient", claim.recipientId().toString());
        minionSection.set("prepared-claim.operation-id", claim.operationId());
        // Kept for safe downgrade inspection and compatibility with existing data.
        minionSection.set("prepared-claim.items", claim.items());
        List<Map<String, Object>> savedLines = new ArrayList<>();
        for (PreparedClaimLine line : claim.lines()) {
            Map<String, Object> savedLine = new LinkedHashMap<>();
            savedLine.put("item", line.item());
            line.skillProviderOptional().ifPresent(provider -> {
                savedLine.put("skill-provider", provider);
                savedLine.put("skill-id", line.skillId());
                savedLine.put("xp-per-base-unit", line.xpPerBaseUnit());
                savedLine.put("equivalent", line.equivalent());
            });
            savedLines.add(savedLine);
        }
        minionSection.set("prepared-claim.lines", savedLines);
    }

    public static Optional<LoadedClaim> load(ConfigurationSection minionSection,
                                             UUID minionId) {
        Objects.requireNonNull(minionSection, "minionSection");
        Objects.requireNonNull(minionId, "minionId");
        ConfigurationSection section = minionSection.getConfigurationSection("prepared-claim");
        if (section == null) return Optional.empty();
        String state = section.getString("state", "PREPARED");
        boolean blocked;
        if ("PREPARED".equalsIgnoreCase(state)) {
            blocked = false;
        } else if ("BLOCKED".equalsIgnoreCase(state)) {
            blocked = true;
        } else {
            throw new IllegalArgumentException("unknown prepared claim state: " + state);
        }
        UUID claimId = UUID.fromString(Objects.requireNonNull(section.getString("id"), "id"));
        UUID recipientId = UUID.fromString(Objects.requireNonNull(
                section.getString("recipient"), "recipient"));
        String operationId = section.getString("operation-id",
                "minion:" + minionId + ":claim:" + claimId);
        List<ItemStack> legacyItems = section.getList("items", List.of()).stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .map(ItemStack::clone)
                .toList();
        List<PreparedClaimLine> lines = readLines(section);
        if (lines.isEmpty()) {
            // Legacy claims intentionally receive no retroactive XP rule.
            lines = legacyItems.stream().map(PreparedClaimLine::withoutSkill).toList();
        } else if (!legacyItems.isEmpty()) {
            requireSameItems(legacyItems, lines);
        }
        return Optional.of(new LoadedClaim(
                new PreparedClaim(claimId, recipientId, operationId, lines), blocked));
    }

    private static List<PreparedClaimLine> readLines(ConfigurationSection section) {
        List<PreparedClaimLine> lines = new ArrayList<>();
        for (Map<?, ?> savedLine : section.getMapList("lines")) {
            Object rawItem = savedLine.get("item");
            if (!(rawItem instanceof ItemStack item)) {
                throw new IllegalArgumentException("prepared claim line is missing ItemStack");
            }
            Object rawProvider = savedLine.get("skill-provider");
            if (rawProvider == null) {
                lines.add(PreparedClaimLine.withoutSkill(item));
                continue;
            }
            Object rawSkillId = savedLine.get("skill-id");
            Object rawXp = savedLine.get("xp-per-base-unit");
            Object rawEquivalent = savedLine.get("equivalent");
            if (rawSkillId == null || !(rawXp instanceof Number xp)
                    || !(rawEquivalent instanceof Number equivalent)) {
                throw new IllegalArgumentException("prepared claim Skill XP snapshot is incomplete");
            }
            lines.add(new PreparedClaimLine(item, String.valueOf(rawProvider),
                    String.valueOf(rawSkillId), xp.doubleValue(), equivalent.doubleValue()));
        }
        return List.copyOf(lines);
    }

    private static void requireSameItems(List<ItemStack> legacyItems,
                                         List<PreparedClaimLine> lines) {
        if (legacyItems.size() != lines.size()) {
            throw new IllegalArgumentException("prepared claim items and lines have different sizes");
        }
        for (int index = 0; index < legacyItems.size(); index++) {
            ItemStack item = legacyItems.get(index);
            ItemStack lineItem = lines.get(index).item();
            if (item.getAmount() != lineItem.getAmount() || !item.isSimilar(lineItem)) {
                throw new IllegalArgumentException("prepared claim items and lines differ");
            }
        }
    }

    public record LoadedClaim(PreparedClaim claim, boolean blocked) {
        public LoadedClaim {
            Objects.requireNonNull(claim, "claim");
        }
    }
}
