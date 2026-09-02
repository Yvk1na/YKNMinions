package com.xigua.yknminions.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OfflineProductionDefaultsTest {
    @Test
    void bundledDefaultsEnableBoundedCatchUpWithoutDurationCap() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertTrue(yaml.getBoolean("offline-production.enabled"));
        assertEquals(60L, yaml.getLong("offline-production.settle-interval-seconds"));
        assertEquals(2000, yaml.getInt("offline-production.max-actions-per-tick"));
        assertEquals(4L, yaml.getLong("offline-production.max-millis-per-tick"));
        assertFalse(yaml.contains("offline-production.max-duration"));
    }
}
