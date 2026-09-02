package com.xigua.yknminions.model;

import com.xigua.yknminions.TestItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinionStorageTest {
    @Test
    void clampsAndUpdatesUnlockedCapacity() {
        MinionStorage storage = new MinionStorage(15, 5, List.of());
        assertEquals(5, storage.unlockedSlots());
        storage.unlockedSlots(7);
        assertEquals(7, storage.unlockedSlots());
        storage.unlockedSlots(99);
        assertEquals(15, storage.unlockedSlots());
    }

    @Test
    void partialSettlementKeepsRemaindersAndProductionSuffix() {
        MinionStorage storage = new MinionStorage(15, 15, List.of(
                new TestItemStack(Material.COBBLESTONE, 64),
                new TestItemStack(Material.DIRT, 20)));
        storage.reserveAll();
        assertEquals(0, storage.add(new TestItemStack(Material.COBBLESTONE, 5)));

        assertTrue(storage.settleReservation(List.of(32L, 20L)));

        assertFalse(storage.hasReservation());
        assertContents(storage.snapshot(),
                Material.COBBLESTONE, 32,
                Material.COBBLESTONE, 5);
    }

    @Test
    void zeroSettlementReleasesEveryReservedItem() {
        MinionStorage storage = new MinionStorage(15, 15, List.of(
                new TestItemStack(Material.IRON_INGOT, 16),
                new TestItemStack(Material.GOLD_INGOT, 8)));
        storage.reserveAll();

        assertTrue(storage.settleReservation(List.of(0L, 0L)));

        assertFalse(storage.hasReservation());
        assertContents(storage.snapshot(),
                Material.IRON_INGOT, 16,
                Material.GOLD_INGOT, 8);
    }

    @Test
    void fullSettlementRemovesOnlyReservedPrefix() {
        MinionStorage storage = new MinionStorage(15, 15,
                List.of(new TestItemStack(Material.DIAMOND, 12)));
        storage.reserveAll();
        storage.add(new TestItemStack(Material.EMERALD, 3));

        assertTrue(storage.settleReservation(List.of(12L)));

        assertContents(storage.snapshot(), Material.EMERALD, 3);
    }

    @Test
    void invalidSettlementDoesNotMutateReservation() {
        MinionStorage storage = new MinionStorage(15, 15,
                List.of(new TestItemStack(Material.REDSTONE, 10)));
        storage.reserveAll();
        List<ItemStack> before = storage.snapshot();

        assertFalse(storage.settleReservation(List.of(11L)));
        assertFalse(storage.settleReservation(List.of()));

        assertTrue(storage.hasReservation());
        assertEquals(before, storage.snapshot());
    }

    @Test
    void expectedFrozenPrefixMustMatchExactlyBeforeSettlement() {
        MinionStorage storage = new MinionStorage(15, 15,
                List.of(new TestItemStack(Material.REDSTONE, 10)));
        storage.reserveAll();

        assertFalse(storage.settleReservation(
                List.of(new TestItemStack(Material.REDSTONE, 9)), List.of(5L)));
        assertFalse(storage.settleReservation(
                List.of(new TestItemStack(Material.COAL, 10)), List.of(5L)));

        assertTrue(storage.hasReservation());
        assertContents(storage.reservedSnapshot(), Material.REDSTONE, 10);
    }

    @Test
    void stateSnapshotRollsBackACompletedSettlement() {
        MinionStorage storage = new MinionStorage(15, 15,
                List.of(new TestItemStack(Material.LAPIS_LAZULI, 40)));
        storage.reserveAll();
        MinionStorage.StateSnapshot snapshot = storage.stateSnapshot();
        assertTrue(storage.settleReservation(List.of(25L)));

        storage.restoreState(snapshot);

        assertTrue(storage.hasReservation());
        assertContents(storage.reservedSnapshot(), Material.LAPIS_LAZULI, 40);
    }

    private static void assertContents(List<ItemStack> actual, Object... expected) {
        assertEquals(expected.length / 2, actual.size());
        for (int index = 0; index < actual.size(); index++) {
            assertEquals(expected[index * 2], actual.get(index).getType());
            assertEquals(expected[index * 2 + 1], actual.get(index).getAmount());
        }
    }

}
