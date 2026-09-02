package com.xigua.yknminions.model;

import com.xigua.yknminions.TestItemStack;
import com.xigua.yknminions.config.*;
import com.xigua.yknminions.integration.CollectionIntegration;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.item.MinionItemAccess;
import com.xigua.yknminions.item.MinionUpgradeAccess;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.service.StorageCompactor;
import com.xigua.yknminions.util.RandomRange;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MinionInstanceOfflineProductionTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final MinionItemAccess ITEMS = new MinionItemAccess() {
        @Override
        public ItemStack create(ItemSpec spec, int amount) {
            return new TestItemStack(Material.valueOf(spec.value().toUpperCase(Locale.ROOT)), amount);
        }

        @Override
        public boolean matches(ItemStack stack, ItemSpec spec) {
            return stack != null && stack.getType() == Material.valueOf(
                    spec.value().toUpperCase(Locale.ROOT));
        }
    };
    private static final CollectionIntegration NO_COLLECTIONS = new CollectionIntegration() {
        @Override public boolean available() { return false; }
        @Override public boolean validCollection(String collectionId) { return false; }
        @Override public ProgressResult add(UUID playerId, String collectionId, double amount) {
            return ProgressResult.SKIPPED;
        }
    };
    private static final StorageCompactor NO_COMPACTION = (storage, trigger) -> { };

    @Test
    void cursorAndTwoActionHarvestPersistWithoutDuplicateRestartYield() {
        MinionType type = twoActionType(10.0, null);
        MinionInstance original = instance(settings(type, List.of(), List.of(), 5), type,
                List.of(), null, null, 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of(), NO_COMPACTION, NO_COLLECTIONS);

        assertEquals(4, settleFully(original, 31_000L, 2));
        assertEquals(2, count(original, Material.COBBLESTONE));

        YamlConfiguration saved = save(original);
        assertEquals(41_000L, saved.getLong("next-work-at"));
        assertTrue(saved.getBoolean("offline-harvest-next"));

        OfflineStateCodec.Loaded offline = OfflineStateCodec.load(saved);
        MinionInstance restarted = instance(settings(type, List.of(), List.of(), 5), type,
                original.storage().snapshot(), null, null,
                saved.getLong("fuel-burn-until"), saved.getString("active-fuel"),
                saved.getLong("next-work-at"), offline.harvestNext(), offline.workable(),
                offline.pendingMobSizes(), offline.pendingMobKillAt(), Set.of(),
                NO_COMPACTION, NO_COLLECTIONS);

        assertEquals(0, settleFully(restarted, 31_000L, 100));
        assertEquals(2, count(restarted, Material.COBBLESTONE));
    }

    @Test
    void finiteFuelUsesActionTimestampAcrossExpiry() {
        FuelType fast = new FuelType("fast", new ItemSpec("coal"), 1.0, 60, false);
        MinionType type = directType("fuel", new ItemSpec("iron_ingot"), 1, 10.0, null);
        MinionInstance minion = instance(settings(type, List.of(fast), List.of(), 5), type,
                List.of(), null, null, 16_000L, "fast", 1_000L,
                true, true, List.of(), 0L, Set.of(), NO_COMPACTION, NO_COLLECTIONS);

        assertEquals(5, settleFully(minion, 30_000L, 100));
        assertEquals(5, count(minion, Material.IRON_INGOT));

        YamlConfiguration saved = save(minion);
        assertEquals(36_000L, saved.getLong("next-work-at"));
        assertEquals(0L, saved.getLong("fuel-burn-until"));
        assertNull(saved.getString("active-fuel"));
    }

    @Test
    void fullStorageSkipsHistoryAndDoesNotStartNextFuel() {
        FuelType fast = new FuelType("fast", new ItemSpec("coal"), 1.0, 60, false);
        MinionType type = twoActionType(10.0, null);
        MinionInstance minion = instance(settings(type, List.of(fast), List.of(), 1), type,
                List.of(new TestItemStack(Material.COBBLESTONE, 64)),
                new TestItemStack(Material.COAL, 2), null, 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of(), NO_COMPACTION, NO_COLLECTIONS);

        MinionInstance.CatchUpResult full = minion.catchUp(1_000_000L, 100, true);
        assertTrue(full.complete());
        assertEquals(0, full.processedActions());
        assertEquals(2, minion.fuel().getAmount());
        assertEquals(1_010_000L, save(minion).getLong("next-work-at"));

        minion.storage().takeAll();
        assertEquals(0, settleFully(minion, 1_000_000L, 100));
        assertTrue(minion.storage().isEmpty());

        assertEquals(1, settleFully(minion, 1_010_000L, 100));
        assertEquals(1, count(minion, Material.COBBLESTONE));
        assertEquals(1, minion.fuel().getAmount());
    }

    @Test
    void unusableAreaAdvancesInConstantTimeWithoutFuelConsumption() {
        FuelType fast = new FuelType("fast", new ItemSpec("coal"), 1.0, 60, false);
        MinionType type = twoActionType(10.0, null);
        long target = 365L * 24L * 60L * 60L * 1_000L;
        MinionInstance minion = instance(settings(type, List.of(fast), List.of(), 2), type,
                List.of(), new TestItemStack(Material.COAL, 2), null, 0L, null, 1_000L,
                true, false, List.of(), 0L, Set.of(), NO_COMPACTION, NO_COLLECTIONS);

        MinionInstance.CatchUpResult result = minion.catchUp(target, 1, true);

        assertTrue(result.complete());
        assertEquals(0, result.processedActions());
        assertEquals(2, minion.fuel().getAmount());
        assertEquals(target + 10_000L, save(minion).getLong("next-work-at"));
        assertTrue(minion.storage().isEmpty());
    }

    @Test
    void slimeCyclePersistsChildrenAndRespectsKillSpacing() {
        SlimeSettings slime = new SlimeSettings(Map.of(4, 100.0), new RandomRange(1, 1), 1);
        MinionType type = new MinionType("slime", "Slime", new ItemSpec("slime_ball"),
                new RandomRange(1, 1), "", null,
                new MobSettings(EntityType.SLIME, new RandomRange(1, 1), 1), slime,
                null, null, null, null,
                Map.of(1, new LevelSettings(1, 100.0, List.of())));
        MinionInstance minion = instance(settings(type, List.of(), List.of(), 5), type,
                List.of(), null, null, 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of(), NO_COMPACTION, NO_COLLECTIONS);

        MinionInstance.CatchUpResult firstSlice = minion.catchUp(1_050L, 2, true);
        assertFalse(firstSlice.complete());
        YamlConfiguration midCycle = save(minion);
        assertTrue(midCycle.getIntegerList("pending-mob-sizes").size() >= 2);
        assertTrue(midCycle.getIntegerList("pending-mob-sizes").size() <= 4);
        assertTrue(midCycle.getIntegerList("pending-mob-sizes").stream().allMatch(size -> size == 2));
        assertEquals(2_050L, midCycle.getLong("pending-mob-kill-at"));

        settleFully(minion, 100_000L, 100);
        int kills = count(minion, Material.SLIME_BALL);
        assertTrue(kills >= 7 && kills <= 21, "recursive split kill count=" + kills);
        assertFalse(save(minion).contains("pending-mob-sizes"));
        assertEquals(101_000L, save(minion).getLong("next-work-at"));
    }

    @Test
    void guaranteedSpreadUsesTheSameOfflineOutputPath() {
        SpreadSettings spread = new SpreadSettings("bonus", new ItemSpec("diamond"),
                1.0, new RandomRange(3, 3));
        MinionType type = directType("spread", new ItemSpec("cobblestone"), 1, 10.0, null);
        MinionInstance minion = instance(settings(type, List.of(), List.of(spread), 5), type,
                List.of(), null, new TestItemStack(Material.PAPER, 1), 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of("bonus"), NO_COMPACTION, NO_COLLECTIONS);

        assertEquals(1, settleFully(minion, 1_000L, 100));
        assertEquals(1, count(minion, Material.COBBLESTONE));
        assertEquals(3, count(minion, Material.DIAMOND));
    }

    @Test
    void autoCompressionRunsAfterOfflineOutput() {
        MinionType type = directType("compact", new ItemSpec("cobblestone"), 9, 10.0, null);
        StorageCompactor compressor = (storage, trigger) -> {
            int cobblestone = count(storage, Material.COBBLESTONE);
            if (cobblestone < 9) return;
            storage.takeAll();
            storage.add(new TestItemStack(Material.STONE, cobblestone / 9));
        };
        MinionInstance minion = instance(settings(type, List.of(), List.of(), 1), type,
                List.of(), null, new TestItemStack(Material.CRAFTING_TABLE, 1), 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of(SpecialItemService.AUTO_CRAFT),
                compressor, NO_COLLECTIONS);

        settleFully(minion, 1_000L, 100);

        assertEquals(0, count(minion, Material.COBBLESTONE));
        assertEquals(1, count(minion, Material.STONE));
        assertFalse(minion.storageFull());
    }

    @Test
    void offlineCollectionDeltaIsNotSubmittedUntilExplicitFlush() {
        AtomicInteger externalCalls = new AtomicInteger();
        CollectionIntegration integration = new CollectionIntegration() {
            @Override public boolean available() { return true; }
            @Override public boolean validCollection(String collectionId) { return true; }
            @Override public ProgressResult add(UUID playerId, String collectionId, double amount) {
                externalCalls.incrementAndGet();
                assertEquals("cobblestone", collectionId);
                assertEquals(2.0, amount);
                return ProgressResult.APPLIED;
            }
        };
        MinionType type = directType("collection", new ItemSpec("cobblestone"), 2, 10.0,
                new CollectionSettings("ecocollections", "cobblestone"));
        MinionInstance minion = instance(settings(type, List.of(), List.of(), 2), type,
                List.of(), null, null, 0L, null, 1_000L,
                true, true, List.of(), 0L, Set.of(), NO_COMPACTION, integration);

        settleFully(minion, 1_000L, 100);
        assertEquals(0, externalCalls.get());
        assertTrue(minion.hasPendingCollections());
        save(minion); // Mirrors the manager's durable-save barrier.

        assertTrue(minion.flushPendingCollections());
        assertEquals(1, externalCalls.get());
        assertFalse(minion.hasPendingCollections());
    }

    private static int settleFully(MinionInstance minion, long targetAt, int slice) {
        int total = 0;
        for (int attempt = 0; attempt < 100; attempt++) {
            MinionInstance.CatchUpResult result = minion.catchUp(targetAt, slice, true);
            total += result.processedActions();
            if (result.complete()) return total;
        }
        fail("catch-up did not complete");
        return total;
    }

    private static MinionInstance instance(MinionRuntimeSettings settings, MinionType type,
                                           List<ItemStack> storage, ItemStack fuel, ItemStack upgrade,
                                           long fuelBurnUntil, String activeFuelId, long nextWorkAt,
                                           boolean harvestNext, boolean workable,
                                           List<Integer> pendingMobSizes, long pendingMobKillAt,
                                           Set<String> enabledUpgrades, StorageCompactor compactor,
                                           CollectionIntegration collections) {
        MinionUpgradeAccess upgrades = (item, id) -> item != null && enabledUpgrades.contains(id);
        return new MinionInstance(null, settings, ITEMS, upgrades, compactor, collections,
                UUID.randomUUID(), OWNER, type.id(), 1,
                new MinionPosition("island_test_normal", 0.5, 80.0, 0.5, 0.0f),
                List.of(), List.of(), List.of(), Map.of(), storage,
                null, false, Map.of(), fuel, upgrade, null,
                fuelBurnUntil, 0, activeFuelId, nextWorkAt,
                harvestNext, workable, pendingMobSizes, pendingMobKillAt);
    }

    private static MinionRuntimeSettings settings(MinionType type, List<FuelType> fuels,
                                                   List<SpreadSettings> spreads, int slots) {
        return new MinionRuntimeSettings() {
            @Override public Optional<MinionType> minionType(String id) {
                return type.id().equals(id) ? Optional.of(type) : Optional.empty();
            }
            @Override public List<FuelType> fuels() { return fuels; }
            @Override public Optional<FuelType> fuel(String id) {
                if (id == null) return Optional.empty();
                return fuels.stream().filter(fuel -> fuel.id().equals(id)).findFirst();
            }
            @Override public int storageSlots() { return slots; }
            @Override public int storageSlots(int level) { return slots; }
            @Override public List<SpreadSettings> spreads() { return spreads; }
        };
    }

    private static MinionType twoActionType(double intervalSeconds, CollectionSettings collection) {
        return new MinionType("two_action", "Two Action", new ItemSpec("cobblestone"),
                new RandomRange(1, 1), "", null, null, null, null,
                new FarmingSettings(FarmingSettings.Crop.WHEAT), null, collection,
                Map.of(1, new LevelSettings(1, intervalSeconds, List.of())));
    }

    private static MinionType directType(String id, ItemSpec drop, int amount,
                                         double intervalSeconds, CollectionSettings collection) {
        return new MinionType(id, id, drop, new RandomRange(amount, amount), "", null,
                null, null, null, null, null, collection,
                Map.of(1, new LevelSettings(1, intervalSeconds, List.of())));
    }

    private static YamlConfiguration save(MinionInstance minion) {
        YamlConfiguration yaml = new YamlConfiguration();
        minion.save(yaml);
        return yaml;
    }

    private static int count(MinionInstance minion, Material material) {
        return count(minion.storage(), material);
    }

    private static int count(MinionStorage storage, Material material) {
        return storage.snapshot().stream().filter(item -> item.getType() == material)
                .mapToInt(ItemStack::getAmount).sum();
    }
}
