package com.xigua.yknminions.model;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineStateCodecTest {
    @Test
    void legacyRecordDefaultsToHistoricalCatchUpEnabled() {
        OfflineStateCodec.Loaded loaded = OfflineStateCodec.load(new YamlConfiguration());

        assertTrue(loaded.harvestNext());
        assertTrue(loaded.workable());
        assertTrue(loaded.pendingMobSizes().isEmpty());
        assertEquals(0L, loaded.pendingMobKillAt());
    }

    @Test
    void pendingMobCycleRoundTripsAndRejectsInvalidSizes() {
        YamlConfiguration yaml = new YamlConfiguration();
        OfflineStateCodec.save(yaml, false, false, List.of(4, 2, 1), 12345L);
        yaml.set("pending-mob-sizes", List.of(4, -1, 200, 2, 1));

        OfflineStateCodec.Loaded loaded = OfflineStateCodec.load(yaml);

        assertFalse(loaded.harvestNext());
        assertFalse(loaded.workable());
        assertEquals(List.of(4, 2, 1), loaded.pendingMobSizes());
        assertEquals(12345L, loaded.pendingMobKillAt());
    }
}
