package com.xigua.yknminions.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmingSettingsTest {
    @Test
    void centerWaterIsLimitedToRequestedCrops() {
        Set<FarmingSettings.Crop> actual = Arrays.stream(FarmingSettings.Crop.values())
                .filter(FarmingSettings.Crop::needsCenterWater).collect(Collectors.toSet());
        assertEquals(Set.of(FarmingSettings.Crop.CARROT, FarmingSettings.Crop.POTATO,
                FarmingSettings.Crop.WHEAT, FarmingSettings.Crop.MELON, FarmingSettings.Crop.PUMPKIN), actual);
    }
}
