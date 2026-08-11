package com.xigua.yknminions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void bundledConfigContainsEveryMobAndElevenLevels() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("minions.yml");
        assertNotNull(stream);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
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
}
