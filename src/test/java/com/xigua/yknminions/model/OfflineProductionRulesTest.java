package com.xigua.yknminions.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class OfflineProductionRulesTest {
    @Test
    void miningAndFarmingProduceEverySecondAction() {
        boolean harvestNext = true;
        int produced = 0;
        for (int action = 0; action < 100; action++) {
            if (OfflineProductionRules.producesPrimary(harvestNext)) produced++;
            harvestNext = OfflineProductionRules.nextHarvestPhase(harvestNext);
        }

        assertEquals(50, produced);
        assertTrue(harvestNext);
    }

    @Test
    void speedMultiplierAndMinimumIntervalAreApplied() {
        assertEquals(8_000L, OfflineProductionRules.intervalMillis(12.0, 1.5));
        assertEquals(250L, OfflineProductionRules.intervalMillis(0.1, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> OfflineProductionRules.intervalMillis(12.0, 0.0));
    }

    @Test
    void slimeChildrenAreBoundedAndRecursiveStateCanBePersisted() {
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        OfflineProductionRules.appendSplitChildren(pending, 4, 3);
        assertEquals(3, pending.size());
        assertTrue(pending.stream().allMatch(size -> size == 2));

        int child = pending.removeFirst();
        OfflineProductionRules.appendSplitChildren(pending, child, 4);
        assertEquals(6, pending.size());
        assertEquals(1, pending.getLast());
    }

    @Test
    void timestampOverflowSaturatesInsteadOfWrappingIntoThePast() {
        assertEquals(Long.MAX_VALUE,
                OfflineProductionRules.safeAdd(Long.MAX_VALUE - 5, 10));
    }
}
