package com.xigua.yknminions.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MinionManagerDataTest {
    @Test
    void deferredRecordCopyPreservesNestedValues() {
        YamlConfiguration sourceYaml = new YamlConfiguration();
        ConfigurationSection source = sourceYaml.createSection("minions.test-id");
        source.set("world", "island_world");
        source.set("level", 7);
        source.set("farming-original-ground.1", "DIRT");
        source.set("farming-plants", List.of(1, 4, 8));

        YamlConfiguration targetYaml = new YamlConfiguration();
        ConfigurationSection target = targetYaml.createSection("minions.test-id");
        MinionManager.copySection(source, target);

        assertEquals("island_world", target.getString("world"));
        assertEquals(7, target.getInt("level"));
        assertNotNull(target.getConfigurationSection("farming-original-ground"));
        assertEquals("DIRT", target.getString("farming-original-ground.1"));
        assertEquals(List.of(1, 4, 8), target.getIntegerList("farming-plants"));
    }
}
