package com.xigua.yknminions.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FuelDefaultsTest {
    @Test
    void bundledConfigContainsOnlySpecialFuels() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        ConfigurationSection fuels = yaml.getConfigurationSection("fuels");
        assertNotNull(fuels);

        assertEquals(Set.of("infinite_energy", "small_fuel", "medium_fuel", "super_fuel"),
                fuels.getKeys(false));
        assertFalse(yaml.contains("fuels.infinite_energy.burn-time-seconds"));
        assertEquals(0.10, yaml.getDouble("fuels.small_fuel.efficiency"), 0.0001);
        assertEquals(3600L, yaml.getLong("fuels.small_fuel.burn-time-seconds"));
        assertEquals(0.25, yaml.getDouble("fuels.medium_fuel.efficiency"), 0.0001);
        assertEquals(10800L, yaml.getLong("fuels.medium_fuel.burn-time-seconds"));
        assertEquals(0.50, yaml.getDouble("fuels.super_fuel.efficiency"), 0.0001);
        assertEquals(43200L, yaml.getLong("fuels.super_fuel.burn-time-seconds"));
    }
}
