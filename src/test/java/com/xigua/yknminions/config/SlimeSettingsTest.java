package com.xigua.yknminions.config;

import com.xigua.yknminions.util.RandomRange;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SlimeSettingsTest {
    @Test
    void rollsOnlyConfiguredSizes() {
        SlimeSettings settings = new SlimeSettings(
                Map.of(1, 50.0, 2, 35.0, 4, 15.0), new RandomRange(1, 5), 10L);
        for (int i = 0; i < 500; i++) assertTrue(settings.sizeChances().containsKey(settings.rollSize()));
    }
}
