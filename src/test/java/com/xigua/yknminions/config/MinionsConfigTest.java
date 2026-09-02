package com.xigua.yknminions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionsConfigTest {
    private static final Set<String> TYPES = Set.of(
            "slime", "zombie", "spider", "skeleton", "blaze", "cave_spider",
            "chicken", "cow", "creeper", "pig", "rabbit", "sheep", "magma_cube", "enderman",
            "coal", "cobblestone", "diamond", "emerald", "end_stone", "gold", "iron",
            "oak_log", "obsidian", "quartz", "redstone", "sand", "snow", "clay", "lapis",
            "cactus", "carrot", "red_mushroom", "brown_mushroom", "nether_wart", "potato",
            "sugar_cane", "wheat", "melon", "pumpkin");
    private static final Set<String> MINING_TYPES = Set.of(
            "coal", "cobblestone", "diamond", "emerald", "end_stone", "gold", "iron",
            "oak_log", "obsidian", "quartz", "redstone", "sand", "snow", "clay", "lapis");
    private static final Set<String> FARMING_TYPES = Set.of(
            "cactus", "carrot", "red_mushroom", "brown_mushroom", "nether_wart", "potato",
            "sugar_cane", "wheat", "melon", "pumpkin");
    private static final Set<String> FIGHTING_SKILL_TYPES = Set.of(
            "slime", "zombie", "spider", "skeleton", "blaze", "cave_spider",
            "creeper", "magma_cube", "enderman");
    private static final Set<String> FARMING_SKILL_TYPES = Set.of(
            "chicken", "cow", "pig", "rabbit", "sheep", "cactus", "carrot",
            "red_mushroom", "brown_mushroom", "nether_wart", "potato", "sugar_cane",
            "wheat", "melon", "pumpkin");
    private static final Set<String> MINING_SKILL_TYPES = Set.of(
            "coal", "cobblestone", "diamond", "emerald", "end_stone", "gold", "iron",
            "obsidian", "quartz", "redstone", "sand", "snow", "lapis");
    private static final Map<String, Double> BASE_XP = Map.ofEntries(
            Map.entry("slime", 0.2), Map.entry("zombie", 0.3), Map.entry("spider", 0.2),
            Map.entry("skeleton", 0.2), Map.entry("blaze", 0.3), Map.entry("cave_spider", 0.3),
            Map.entry("chicken", 0.1), Map.entry("cow", 0.1), Map.entry("creeper", 0.3),
            Map.entry("pig", 0.2), Map.entry("rabbit", 0.1), Map.entry("sheep", 0.1),
            Map.entry("magma_cube", 0.2), Map.entry("enderman", 0.3), Map.entry("coal", 0.3),
            Map.entry("cobblestone", 0.1), Map.entry("diamond", 0.4), Map.entry("emerald", 0.4),
            Map.entry("end_stone", 0.4), Map.entry("gold", 0.4), Map.entry("iron", 0.3),
            Map.entry("oak_log", 0.1), Map.entry("obsidian", 0.4), Map.entry("quartz", 0.3),
            Map.entry("redstone", 0.2), Map.entry("sand", 0.2), Map.entry("snow", 0.1),
            Map.entry("clay", 0.1), Map.entry("lapis", 0.1), Map.entry("cactus", 0.2),
            Map.entry("carrot", 0.1), Map.entry("red_mushroom", 0.3),
            Map.entry("brown_mushroom", 0.3), Map.entry("nether_wart", 0.2),
            Map.entry("potato", 0.1), Map.entry("sugar_cane", 0.1), Map.entry("wheat", 0.2),
            Map.entry("melon", 0.1), Map.entry("pumpkin", 0.3));
    private static final Map<String, String> COLLECTIONS = Map.ofEntries(
            Map.entry("slime", "slime"), Map.entry("zombie", "zombie"),
            Map.entry("spider", "spider"), Map.entry("skeleton", "skeleton"),
            Map.entry("blaze", "blaze"), Map.entry("cave_spider", "spider"),
            Map.entry("creeper", "creeper"), Map.entry("magma_cube", "magma_cube"),
            Map.entry("enderman", "enderman"), Map.entry("coal", "coal"),
            Map.entry("cobblestone", "cobblestone"), Map.entry("diamond", "diamond"),
            Map.entry("emerald", "emerald"), Map.entry("end_stone", "end_stone"),
            Map.entry("gold", "gold"), Map.entry("iron", "iron"), Map.entry("oak_log", "oak"),
            Map.entry("obsidian", "obsidian"), Map.entry("quartz", "quartz"),
            Map.entry("redstone", "redstone"), Map.entry("lapis", "lapis"),
            Map.entry("cactus", "cactus"), Map.entry("carrot", "carrot"),
            Map.entry("red_mushroom", "mushroom"), Map.entry("brown_mushroom", "mushroom"),
            Map.entry("nether_wart", "nether_wart"), Map.entry("potato", "potato"),
            Map.entry("sugar_cane", "sugar_cane"), Map.entry("wheat", "wheat"),
            Map.entry("melon", "melon"), Map.entry("pumpkin", "pumpkin"));

    @Test
    void bundledConfigContainsEveryMobAndElevenLevels() {
        YamlConfiguration yaml = loadBundledConfig();
        ConfigurationSection types = yaml.getConfigurationSection("types");
        assertNotNull(types);
        assertEquals(TYPES, types.getKeys(false));
        for (String id : TYPES) {
            ConfigurationSection levels = yaml.getConfigurationSection("types." + id + ".levels");
            assertNotNull(levels, id + " levels");
            assertEquals(11, levels.getKeys(false).size(), id + " level count");
            if (MINING_TYPES.contains(id)) {
                ConfigurationSection mining = yaml.getConfigurationSection("types." + id + ".mining-settings");
                assertNotNull(mining, id + " mining settings");
                assertNotNull(Material.matchMaterial(mining.getString("block", ""), false), id + " mining block");
            } else if (FARMING_TYPES.contains(id)) {
                ConfigurationSection farming = yaml.getConfigurationSection("types." + id + ".farming-settings");
                assertNotNull(farming, id + " farming settings");
                FarmingSettings.Crop.valueOf(farming.getString("crop", ""));
            } else if (!id.equals("slime")) {
                assertNotNull(yaml.getConfigurationSection("types." + id + ".mob-settings"), id + " mob settings");
            }
        }
    }

    @Test
    void bundledConfigContainsHypixelSkillXpAndAvailableCollections() {
        YamlConfiguration yaml = loadBundledConfig();
        assertEquals(TYPES, BASE_XP.keySet());
        assertEquals(31, COLLECTIONS.size());

        for (String id : TYPES) {
            String typePath = "types." + id;
            ConfigurationSection skill = yaml.getConfigurationSection(typePath + ".skill");
            assertNotNull(skill, id + " skill");
            assertEquals("auraskills", skill.getString("provider"), id + " skill provider");
            assertEquals(expectedSkill(id), skill.getString("id"), id + " skill id");

            List<Map<?, ?>> rewards = skill.getMapList("rewards");
            assertFalse(rewards.isEmpty(), id + " rewards");
            String baseDrop = yaml.getString(typePath + ".drop");
            Map<?, ?> baseReward = rewards.stream()
                    .filter(reward -> baseDrop.equals(String.valueOf(reward.get("item"))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(id + " missing base drop reward"));
            assertEquals(BASE_XP.get(id), ((Number) baseReward.get("xp-per-base-unit")).doubleValue(),
                    id + " base xp");
            assertEquals(1.0, ((Number) baseReward.get("equivalent")).doubleValue(),
                    id + " base equivalent");
            for (Map<?, ?> reward : rewards) {
                String item = String.valueOf(reward.get("item"));
                assertTrue(item.startsWith("minecraft:"), id + " reward namespace");
                assertNotNull(Material.matchMaterial(item.substring("minecraft:".length()), false),
                        id + " reward material " + item);
                assertEquals(BASE_XP.get(id), ((Number) reward.get("xp-per-base-unit")).doubleValue(),
                        id + " reward xp " + item);
                assertTrue(((Number) reward.get("equivalent")).doubleValue() > 0,
                        id + " reward equivalent " + item);
            }

            ConfigurationSection collection = yaml.getConfigurationSection(typePath + ".collection");
            String expectedCollection = COLLECTIONS.get(id);
            if (expectedCollection == null) {
                assertNull(collection, id + " must not reference a missing EcoCollections id");
            } else {
                assertNotNull(collection, id + " collection");
                assertEquals("ecocollections", collection.getString("provider"),
                        id + " collection provider");
                assertEquals(expectedCollection, collection.getString("id"), id + " collection id");
            }
        }
    }

    private String expectedSkill(String id) {
        if (FIGHTING_SKILL_TYPES.contains(id)) return "fighting";
        if (FARMING_SKILL_TYPES.contains(id)) return "farming";
        if (MINING_SKILL_TYPES.contains(id)) return "mining";
        if (id.equals("oak_log")) return "foraging";
        if (id.equals("clay")) return "fishing";
        throw new AssertionError("No expected skill for " + id);
    }

    private YamlConfiguration loadBundledConfig() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("minions.yml");
        assertNotNull(stream);
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
