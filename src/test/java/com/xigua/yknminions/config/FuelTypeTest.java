package com.xigua.yknminions.config;

import com.xigua.yknminions.item.ItemSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FuelTypeTest {
    @Test
    void efficiencyIsAddedToBaseWorkSpeed() {
        FuelType fuel = new FuelType("small_fuel", new ItemSpec("yknminions:small_fuel"),
                0.10, 3600L, false);

        assertEquals(1.10, fuel.speedMultiplier(), 0.0001);
    }

    @Test
    void onlyInfiniteFuelMayHaveNoBurnTime() {
        FuelType infinite = new FuelType("infinite_energy", new ItemSpec("yknminions:infinite_energy"),
                0.0, 0L, true);

        assertEquals(1.0, infinite.speedMultiplier(), 0.0001);
        assertThrows(IllegalArgumentException.class, () -> new FuelType(
                "small_fuel", new ItemSpec("yknminions:small_fuel"), 0.10, 0L, false));
    }
}
