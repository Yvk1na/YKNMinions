package com.xigua.yknminions.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpreadDefaultsTest {
    @Test
    void everySpreadHasChanceAndAmountDefaults() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        Set<String> paths = Set.of("diamond-spread", "emerald-spread", "iron-spread",
                "gold-spread", "lapis-spread");
        for (String path : paths) {
            assertEquals(0.10, yaml.getDouble(path + ".chance"), 0.0001);
            assertEquals("1~5", yaml.getString(path + ".amount"));
        }
    }
}
