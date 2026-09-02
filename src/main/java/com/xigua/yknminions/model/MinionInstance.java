package com.xigua.yknminions.model;

import com.xigua.Main;
import com.xigua.yknminions.config.*;
import com.xigua.yknminions.item.MinionItemAccess;
import com.xigua.yknminions.item.MinionUpgradeAccess;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.integration.CollectionIntegration;
import com.xigua.yknminions.integration.CollectionProgressTracker;
import com.xigua.yknminions.integration.ProductionCollectionAccounting;
import com.xigua.yknminions.service.StorageCompactor;
import com.xigua.yknminions.util.RandomRange;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class MinionInstance {
    private static final long TARGET_KILL_INTERVAL_MILLIS = 1000L;
    private static final double FARM_GROWTH_BONUS_CHANCE = 0.50;
    private static final int[][] WORK_OFFSETS = {
            // Keep the original 3x3 ring first so saved slot indexes remain compatible.
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0},            {1, 0},
            {-1, 1},  {0, 1},  {1, 1},
            // Outer ring expands the work area to 5x5 while leaving the center free.
            {-2, -2}, {-1, -2}, {0, -2}, {1, -2}, {2, -2},
            {-2, -1},                                  {2, -1},
            {-2, 0},                                    {2, 0},
            {-2, 1},                                    {2, 1},
            {-2, 2},  {-1, 2},  {0, 2},  {1, 2},  {2, 2}
    };
    private static final int CENTER_GROUND_SLOT = 24;
    private final Main plugin;
    private final MinionRuntimeSettings config;
    private final MinionItemAccess resolver;
    private final MinionUpgradeAccess specialItems;
    private final StorageCompactor autoCraft;
    private final CollectionProgressTracker collectionProgress;
    private final UUID id;
    private final UUID owner;
    private final String typeId;
    private final MinionPosition position;
    private Location location;
    private final MinionStorage storage;
    private final Set<Integer> generatedBlocks = new HashSet<>();
    private final Set<Integer> farmingPlants = new HashSet<>();
    private final Set<Integer> farmingProduce = new HashSet<>();
    private final Map<Integer, Material> originalFarmGround = new HashMap<>();
    private int level;
    private long nextWorkAt;
    private ItemStack fuel;
    private ItemStack upgradeOne;
    private ItemStack upgradeTwo;
    private long fuelBurnUntil;
    private String activeFuelId;
    private PreparedClaim preparedClaim;
    private boolean preparedClaimBlocked;
    private ArmorStand entity;
    private final Deque<LivingEntity> targetQueue = new ArrayDeque<>();
    private final Deque<Integer> pendingMobSizes = new ArrayDeque<>();
    private LivingEntity mobTarget;
    private boolean splitPending;
    private int pendingSplitChildSize;
    private int pendingSplitChildCount;
    private boolean removed;
    private long targetAttackSequence;
    private long scheduledAttackAt;
    private long lastTargetKillAt;
    private long pendingMobKillAt;
    private long lifecycleGeneration;
    private boolean offlineHarvestNext;
    private boolean offlineWorkable;
    private MinionLifecycleState lifecycleState = MinionLifecycleState.SUSPENDED;
    private int animationTick = -1;

    public MinionInstance(Main plugin, MinionRuntimeSettings config, MinionItemAccess resolver,
                          MinionUpgradeAccess specialItems, StorageCompactor autoCraft,
                          CollectionIntegration collectionIntegration,
                          UUID id, UUID owner, String typeId, int level,
                          MinionPosition position, List<Integer> generatedBlocks, List<Integer> farmingPlants,
                          List<Integer> farmingProduce, Map<Integer, Material> originalFarmGround,
                          List<ItemStack> storage, PreparedClaim preparedClaim,
                          boolean savedPreparedClaimBlocked,
                          Map<String, Double> pendingCollections,
                          ItemStack fuel, ItemStack upgradeOne,
                          ItemStack upgradeTwo, long fuelBurnUntil, int legacyFuelActionsRemaining,
                          String activeFuelId, long nextWorkAt,
                          boolean offlineHarvestNext, boolean offlineWorkable,
                          List<Integer> pendingMobSizes, long pendingMobKillAt) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.specialItems = specialItems;
        this.autoCraft = autoCraft;
        this.id = id;
        this.owner = owner;
        this.collectionProgress = new CollectionProgressTracker(
                owner, collectionIntegration, pendingCollections);
        this.typeId = typeId;
        this.level = level;
        this.position = position;
        this.storage = new MinionStorage(config.storageSlots(), config.storageSlots(level), storage);
        if (preparedClaim != null) {
            this.preparedClaim = preparedClaim;
            if (this.storage.restoreReservation(preparedClaim.items())) {
                this.preparedClaimBlocked = savedPreparedClaimBlocked;
            } else {
                this.storage.reserveAll();
                this.preparedClaimBlocked = true;
                plugin.getLogger().severe("小人 " + id + " 的待领取物品与仓库不一致，已停止自动重试以防止重复发放。");
            }
        }
        generatedBlocks.stream().filter(slot -> validWorkSlot(slot))
                .forEach(this.generatedBlocks::add);
        farmingPlants.stream().filter(this::validWorkSlot).forEach(this.farmingPlants::add);
        farmingProduce.stream().filter(this::validWorkSlot).forEach(this.farmingProduce::add);
        originalFarmGround.forEach((slot, material) -> {
            if (validFarmGroundSlot(slot) && material != null) this.originalFarmGround.put(slot, material);
        });
        this.fuel = cloneOrNull(fuel);
        this.upgradeOne = cloneOrNull(upgradeOne);
        this.upgradeTwo = cloneOrNull(upgradeTwo);
        this.activeFuelId = activeFuelId;
        long now = System.currentTimeMillis();
        this.fuelBurnUntil = Math.max(0L, fuelBurnUntil);
        FuelType configuredActiveFuel = config.fuel(activeFuelId).orElse(null);
        if (configuredActiveFuel == null) {
            this.fuelBurnUntil = 0L;
            this.activeFuelId = null;
        } else if (configuredActiveFuel.infinite()) {
            if (this.fuel != null && resolver.matches(this.fuel, configuredActiveFuel.item())) {
                this.fuelBurnUntil = Long.MAX_VALUE;
            } else {
                this.fuelBurnUntil = 0L;
                this.activeFuelId = null;
            }
        } else {
            if (this.fuelBurnUntil <= 0L && legacyFuelActionsRemaining > 0) {
                this.fuelBurnUntil = addBurnTime(now, configuredActiveFuel.burnTimeSeconds());
            }
        }
        this.nextWorkAt = nextWorkAt <= 0 ? System.currentTimeMillis() + 1000 : nextWorkAt;
        this.offlineHarvestNext = offlineHarvestNext;
        this.offlineWorkable = offlineWorkable;
        if (pendingMobSizes != null) {
            pendingMobSizes.stream().filter(size -> size != null && size >= 0 && size <= 127)
                    .forEach(this.pendingMobSizes::addLast);
        }
        this.pendingMobKillAt = Math.max(0L, pendingMobKillAt);
    }

    public void beginCatchUp(World world) {
        if (removed || world == null || !position.worldName().equals(world.getName())
                || Bukkit.getWorld(position.worldName()) != world) return;
        if (lifecycleState == MinionLifecycleState.CATCHING_UP
                && location != null && location.getWorld() == world) {
            spawn();
            return;
        }
        lifecycleGeneration++;
        targetAttackSequence++;
        location = position.bind(world);
        lifecycleState = MinionLifecycleState.CATCHING_UP;
        entity = null;
        mobTarget = null;
        targetQueue.clear();
        splitPending = false;
        scheduledAttackAt = 0L;
        spawn();
    }

    public boolean activate() {
        if (removed || lifecycleState != MinionLifecycleState.CATCHING_UP
                || !hasCurrentLoadedChunk()) return false;
        lifecycleState = MinionLifecycleState.ACTIVE;
        spawn();
        return true;
    }

    public void markCatchingUp() {
        if (!removed && lifecycleState == MinionLifecycleState.SUSPENDED) {
            lifecycleState = MinionLifecycleState.CATCHING_UP;
        }
    }

    public void suspend(long now) {
        if (removed) return;
        if (location != null && Bukkit.getWorld(position.worldName()) == location.getWorld()) {
            offlineWorkable = inspectOfflineWorkable();
            capturePendingMobRuntime(now);
        }
        lifecycleGeneration++;
        targetAttackSequence++;
        scheduledAttackAt = 0L;
        splitPending = false;
        pendingSplitChildSize = 0;
        pendingSplitChildCount = 0;
        entity = null;
        mobTarget = null;
        targetQueue.clear();
        location = null;
        lifecycleState = MinionLifecycleState.SUSPENDED;
    }

    private void capturePendingMobRuntime(long now) {
        if (mobTarget != null && mobTarget.isValid() && !mobTarget.isDead()) {
            pendingMobSizes.addFirst(mobSize(mobTarget));
            pendingMobKillAt = scheduledAttackAt > 0L ? Math.max(now, scheduledAttackAt) : now;
        }
        for (LivingEntity queued : targetQueue) {
            if (queued != null && queued.isValid() && !queued.isDead()) {
                pendingMobSizes.addLast(mobSize(queued));
            }
        }
        for (int index = 0; index < pendingSplitChildCount; index++) {
            pendingMobSizes.addLast(Math.max(0, pendingSplitChildSize));
        }
        if (!pendingMobSizes.isEmpty() && pendingMobKillAt <= 0L) pendingMobKillAt = now;
    }

    private static int mobSize(LivingEntity target) {
        return target instanceof Slime slime ? Math.max(1, slime.getSize()) : 0;
    }

    private boolean inspectOfflineWorkable() {
        MinionType type = type().orElse(null);
        if (type == null || location == null || !hasCurrentLoadedChunk()) return offlineWorkable;
        if (type.mobSettings() != null) return true;
        if (type.miningSettings() != null) {
            Material expected = type.miningSettings().block();
            for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
                Material actual = miningBlock(slot).getType();
                if (actual.isAir() || actual == expected) return true;
            }
            return false;
        }
        if (type.farmingSettings() != null) {
            FarmingSettings.Crop crop = type.farmingSettings().crop();
            Material produce = crop.produceBlock();
            for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
                Material actual = miningBlock(slot).getType();
                if (actual == crop.plant() || produce != null && actual == produce) return true;
                if (isFarmingPlantSlot(crop, slot) && actual.isAir()) return true;
            }
            return false;
        }
        return true;
    }

    public CatchUpResult catchUp(long targetAt, int maxActions, boolean productionEnabled) {
        return catchUp(targetAt, maxActions, productionEnabled, Long.MAX_VALUE);
    }

    public CatchUpResult catchUp(long targetAt, int maxActions, boolean productionEnabled,
                                 long deadlineNanos) {
        if (maxActions <= 0) return new CatchUpResult(0, false, false);
        MinionType type = type().orElse(null);
        if (type == null) {
            nextWorkAt = Math.max(nextWorkAt, OfflineProductionRules.safeAdd(targetAt, 1000L));
            pendingMobSizes.clear();
            pendingMobKillAt = 0L;
            return new CatchUpResult(0, true, true);
        }
        if (!productionEnabled) {
            skipUnavailablePeriod(type, targetAt);
            pendingMobSizes.clear();
            pendingMobKillAt = 0L;
            return new CatchUpResult(0, true, true);
        }

        int processed = 0;
        boolean changed = false;
        while (processed < maxActions && System.nanoTime() < deadlineNanos) {
            if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) autoCraft.compact(storage, null);
            if (storage.isFull()) {
                skipFullPeriod(type, targetAt);
                return new CatchUpResult(processed, true, true);
            }

            if (type.mobSettings() != null && !pendingMobSizes.isEmpty()) {
                long killAt = pendingMobKillAt <= 0L ? nextWorkAt : pendingMobKillAt;
                if (killAt > targetAt) return new CatchUpResult(processed, true, changed);
                completePendingMobKill(type, killAt, true);
                processed++;
                changed = true;
                continue;
            }

            if (nextWorkAt > targetAt) return new CatchUpResult(processed, true, changed);

            // A saved unusable work area cannot become usable while its chunk is absent.
            // Advance the cursor in O(1) instead of burning the global action budget on
            // empty simulated work. Do not consume a fresh fuel item for skipped time.
            if (!offlineWorkable) {
                skipUnavailablePeriod(type, targetAt);
                return new CatchUpResult(processed, true, true);
            }

            long actionAt = nextWorkAt;
            FuelType activeFuel = ensureFuel(actionAt);

            if (type.mobSettings() != null) {
                int size = type.slimeSettings() == null ? 0 : type.slimeSettings().rollSize();
                pendingMobSizes.addLast(size);
                pendingMobKillAt = OfflineProductionRules.safeAdd(actionAt,
                        Math.max(1L, type.mobSettings().turnDelayTicks()) * 50L);
            } else if (type.miningSettings() != null || type.farmingSettings() != null) {
                if (OfflineProductionRules.producesPrimary(offlineHarvestNext)) {
                    collectWorkOutput(type, type.dropAmount(), true);
                }
                offlineHarvestNext = OfflineProductionRules.nextHarvestPhase(offlineHarvestNext);
            } else {
                collectWorkOutput(type, type.dropAmount(), true);
            }

            nextWorkAt = OfflineProductionRules.safeAdd(actionAt, workIntervalMillis(type, activeFuel));
            processed++;
            changed = true;
        }
        return new CatchUpResult(processed, false, changed);
    }

    private void completePendingMobKill(MinionType type, long killAt, boolean deferredCollection) {
        Integer size = pendingMobSizes.pollFirst();
        if (size == null) {
            pendingMobKillAt = 0L;
            return;
        }
        collectWorkOutput(type, type.mobSettings().dropPerKill(), deferredCollection);
        OfflineProductionRules.appendSplitChildren(pendingMobSizes, size,
                ThreadLocalRandom.current().nextInt(2, 5));
        if (pendingMobSizes.isEmpty()) {
            pendingMobKillAt = 0L;
            nextWorkAt = Math.max(nextWorkAt, killAt);
        } else {
            pendingMobKillAt = OfflineProductionRules.safeAdd(killAt, TARGET_KILL_INTERVAL_MILLIS);
        }
    }

    private void skipFullPeriod(MinionType type, long targetAt) {
        FuelType active = activeFuelType(targetAt);
        if (active == null && fuelBurnUntil != Long.MAX_VALUE && fuelBurnUntil <= targetAt) {
            activeFuelId = null;
            fuelBurnUntil = 0L;
        }
        long interval = workIntervalMillis(type, active);
        nextWorkAt = Math.max(nextWorkAt, OfflineProductionRules.safeAdd(targetAt, interval));
        if (!pendingMobSizes.isEmpty()) pendingMobKillAt = nextWorkAt;
    }

    private void skipUnavailablePeriod(MinionType type, long targetAt) {
        FuelType active = activeFuelType(targetAt);
        if (active == null && fuelBurnUntil != Long.MAX_VALUE && fuelBurnUntil <= targetAt) {
            activeFuelId = null;
            fuelBurnUntil = 0L;
        }
        nextWorkAt = Math.max(nextWorkAt,
                OfflineProductionRules.safeAdd(targetAt, workIntervalMillis(type, active)));
    }

    private long workIntervalMillis(MinionType type, FuelType activeFuel) {
        double multiplier = activeFuel == null ? 1.0 : activeFuel.speedMultiplier();
        return OfflineProductionRules.intervalMillis(type.level(level).workIntervalSeconds(), multiplier);
    }

    public record CatchUpResult(int processedActions, boolean complete, boolean changed) { }

    public void tick(long now) {
        if (lifecycleState != MinionLifecycleState.ACTIVE || !hasCurrentLoadedChunk()) return;
        if ((entity == null || !entity.isValid()) && isChunkLoaded()) spawn();
        animate();
        MinionType type = type().orElse(null);
        if (type == null) return;

        if ((type.mobSettings() != null || type.miningSettings() != null || type.farmingSettings() != null)
                && !isChunkLoaded()) return;
        if (type.mobSettings() != null) {
            if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) autoCraft.compact(storage, null);
            if (!pendingMobSizes.isEmpty()) {
                if (!storage.isFull() && now >= Math.max(0L, pendingMobKillAt)) {
                    completePendingMobKill(type, now, false);
                }
                return;
            }
            resumeTargetCycle(type);
            if (targetCycleBusy()) return;
        }
        if (now < nextWorkAt) return;

        if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) autoCraft.compact(storage, null);
        if (type.farmingSettings() != null) prepareFarmingGround(type.farmingSettings());
        if (storage.isFull()) {
            FuelType active = activeFuelType(now);
            nextWorkAt = OfflineProductionRules.safeAdd(now, workIntervalMillis(type, active));
            return;
        }

        FuelType activeFuel = ensureFuel(now);
        if (type.mobSettings() != null) {
            spawnMobTarget(type);
        } else if (type.miningSettings() != null) {
            performMiningWork(type);
        } else if (type.farmingSettings() != null) {
            performFarmingWork(type);
        } else {
            collectWorkOutput(type, type.dropAmount());
            animationTick = 0;
            playWorkSound(type.workSound());
        }
        nextWorkAt = OfflineProductionRules.safeAdd(now, workIntervalMillis(type, activeFuel));
    }

    private void spawnMobTarget(MinionType type) {
        MobSettings settings = type.mobSettings();
        if (settings == null) return;
        int slimeSize = type.slimeSettings() == null ? 1 : type.slimeSettings().rollSize();
        LivingEntity spawned = createTrackedTarget(findTargetSpawnLocation(settings.entityType(), slimeSize),
                settings.entityType(), slimeSize);
        if (spawned != null) targetQueue.addLast(spawned);
        resumeTargetCycle(type);
    }

    private LivingEntity createTrackedTarget(Location spawnAt, EntityType entityType, int slimeSize) {
        if (removed || spawnAt.getWorld() == null) return null;
        Entity raw = spawnAt.getWorld().spawnEntity(spawnAt, entityType);
        if (!(raw instanceof LivingEntity spawned)) {
            raw.remove();
            return null;
        }
        if (spawned instanceof Slime slime) slime.setSize(Math.max(1, Math.min(127, slimeSize)));
        spawned.setAI(false);
        spawned.setCollidable(false);
        spawned.setInvulnerable(true);
        spawned.setRemoveWhenFarAway(false);
        spawned.setPersistent(false);
        spawned.getPersistentDataContainer().set(plugin.key("minion_target"), PersistentDataType.STRING, id.toString());
        return spawned;
    }

    private Location findTargetSpawnLocation(EntityType entityType, int slimeSize) {
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        int start = ThreadLocalRandom.current().nextInt(offsets.length);
        int radius = isSlimeType(entityType) ? slimeClearanceRadius(slimeSize) : 0;
        int distance = radius + 1;
        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[(start + i) % offsets.length];
            Location candidate = location.clone().add(offset[0] * distance, 0.1, offset[1] * distance);
            if (hasTargetClearance(candidate, entityType, slimeSize)) return candidate;
        }
        return location.clone().add(0, 0.1, 0);
    }

    private boolean hasTargetClearance(Location center, EntityType entityType, int slimeSize) {
        int radius = isSlimeType(entityType) ? slimeClearanceRadius(slimeSize) : 0;
        int height = isSlimeType(entityType) ? Math.max(2, (int) Math.ceil(slimeSize * 0.52)) : 2;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = 0; y < height; y++) {
                    if (!center.clone().add(x, y, z).getBlock().isPassable()) return false;
                }
            }
        }
        return true;
    }

    private int slimeClearanceRadius(int size) {
        return Math.max(0, (int) Math.ceil(size * 0.255 - 0.5));
    }

    private boolean isSlimeType(EntityType entityType) {
        return entityType == EntityType.SLIME || entityType == EntityType.MAGMA_CUBE;
    }

    private void resumeTargetCycle(MinionType type) {
        if (mobTarget != null && (!mobTarget.isValid() || mobTarget.isDead())) {
            mobTarget = null;
            targetAttackSequence++;
        }
        targetQueue.removeIf(target -> target == null || !target.isValid() || target.isDead());
        if (removed || mobTarget != null || splitPending || storage.isFull()) return;
        LivingEntity next = targetQueue.pollFirst();
        if (next == null) return;
        mobTarget = next;
        faceTarget(next);
        long sequence = ++targetAttackSequence;
        long generation = lifecycleGeneration;
        long attackDelay = type.mobSettings().turnDelayTicks();
        if (lastTargetKillAt > 0L) {
            long cooldownRemaining = TARGET_KILL_INTERVAL_MILLIS - (System.currentTimeMillis() - lastTargetKillAt);
            if (cooldownRemaining > 0L) attackDelay = Math.max(attackDelay, (cooldownRemaining + 49L) / 50L);
        }
        scheduledAttackAt = OfflineProductionRules.safeAdd(System.currentTimeMillis(), attackDelay * 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> attackTarget(type, next, sequence, generation),
                attackDelay);
    }

    private boolean targetCycleBusy() {
        return splitPending || mobTarget != null || !targetQueue.isEmpty();
    }

    private void faceTarget(LivingEntity target) {
        if (entity == null || !entity.isValid()) return;
        Vector direction = target.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (direction.lengthSquared() < 0.0001) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        entity.setRotation(yaw, 0.0f);
    }

    private void attackTarget(MinionType type, LivingEntity target, long sequence, long generation) {
        if (removed || lifecycleState != MinionLifecycleState.ACTIVE
                || generation != lifecycleGeneration || sequence != targetAttackSequence || target != mobTarget
                || !target.isValid() || target.isDead()) return;
        scheduledAttackAt = 0L;
        animationTick = 0;
        playWorkSound(type.workSound());
        target.setInvulnerable(false);
        if (entity != null && entity.isValid()) target.damage(1000.0, entity);
        if (target.isValid() && !target.isDead()) target.setHealth(0.0);
    }

    public void collectTargetKill(LivingEntity target) {
        MinionType type = type().orElse(null);
        if (target == null || type == null || type.mobSettings() == null || mobTarget == null
                || !mobTarget.getUniqueId().equals(target.getUniqueId())) return;
        int childSize = target instanceof Slime slime && type.slimeSettings() != null ? slime.getSize() / 2 : 0;
        Location splitAt = target.getLocation().clone();
        mobTarget = null;
        targetAttackSequence++;
        scheduledAttackAt = 0L;
        lastTargetKillAt = System.currentTimeMillis();
        collectWorkOutput(type, type.mobSettings().dropPerKill());
        if (childSize > 0) {
            splitPending = true;
            int childCount = ThreadLocalRandom.current().nextInt(2, 5);
            pendingSplitChildSize = childSize;
            pendingSplitChildCount = childCount;
            long generation = lifecycleGeneration;
            Bukkit.getScheduler().runTask(plugin,
                    () -> spawnSplitChildren(type, splitAt, childSize, childCount, generation));
        } else {
            resumeTargetCycle(type);
        }
    }

    private void spawnSplitChildren(MinionType type, Location splitAt, int childSize, int childCount,
                                    long generation) {
        if (removed || lifecycleState != MinionLifecycleState.ACTIVE
                || generation != lifecycleGeneration) return;
        double spread = Math.max(0.25, childSize * 0.25);
        for (int i = 0; i < childCount; i++) {
            double x = ((i % 2) - 0.5) * spread;
            double z = ((i / 2) - 0.5) * spread;
            EntityType childType = type.mobSettings().entityType();
            LivingEntity child = createTrackedTarget(splitAt.clone().add(x, 0.1, z), childType, childSize);
            if (child != null) targetQueue.addLast(child);
        }
        splitPending = false;
        pendingSplitChildSize = 0;
        pendingSplitChildCount = 0;
        resumeTargetCycle(type);
    }

    private boolean performMiningWork(MinionType type) {
        MiningSettings settings = type.miningSettings();
        if (settings == null || location == null || location.getWorld() == null) return false;
        generatedBlocks.removeIf(slot -> miningBlock(slot).getType() != settings.block());
        List<Integer> mineable = new ArrayList<>(generatedBlocks);
        List<Integer> placeable = new ArrayList<>();
        for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
            if (!generatedBlocks.contains(slot) && miningBlock(slot).getType().isAir()) placeable.add(slot);
        }

        boolean mine = !mineable.isEmpty() && (placeable.isEmpty() || ThreadLocalRandom.current().nextBoolean());
        if (mine) {
            int slot = mineable.get(ThreadLocalRandom.current().nextInt(mineable.size()));
            var block = miningBlock(slot);
            faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
            block.setType(Material.AIR, false);
            generatedBlocks.remove(slot);
            collectWorkOutput(type, type.dropAmount());
            offlineHarvestNext = false;
        } else if (!placeable.isEmpty()) {
            int slot = placeable.get(ThreadLocalRandom.current().nextInt(placeable.size()));
            var block = miningBlock(slot);
            faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
            block.setType(settings.block(), false);
            generatedBlocks.add(slot);
            offlineHarvestNext = true;
        } else {
            offlineWorkable = false;
            return false;
        }
        offlineWorkable = true;
        animationTick = 0;
        playWorkSound(type.workSound());
        return true;
    }

    private Block miningBlock(int slot) {
        int[] offset = WORK_OFFSETS[slot];
        return location.clone().add(offset[0], 0.0, offset[1]).getBlock();
    }

    private boolean validWorkSlot(int slot) {
        return slot >= 0 && slot < WORK_OFFSETS.length;
    }

    private boolean validFarmGroundSlot(int slot) {
        return validWorkSlot(slot) || slot == CENTER_GROUND_SLOT;
    }

    private void faceLocation(Location target) {
        if (entity == null || !entity.isValid()) return;
        Vector direction = target.toVector().subtract(entity.getLocation().toVector());
        if (direction.lengthSquared() < 0.0001) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        entity.setRotation(yaw, 0.0f);
    }

    private boolean performFarmingWork(MinionType type) {
        FarmingSettings settings = type.farmingSettings();
        if (settings == null || location == null || location.getWorld() == null) return false;
        FarmingSettings.Crop crop = settings.crop();
        farmingPlants.removeIf(slot -> miningBlock(slot).getType() != crop.plant());
        Material produceBlock = crop.produceBlock();
        if (produceBlock == null) farmingProduce.clear();
        else {
            farmingProduce.removeIf(slot -> miningBlock(slot).getType() != produceBlock);
            scanStemProduce(crop);
        }

        List<Integer> harvestPlants = farmingPlants.stream()
                .filter(slot -> isMaturePlant(crop, slot)).toList();
        List<Integer> harvestProduce = new ArrayList<>(farmingProduce);
        List<Integer> plantable = new ArrayList<>();
        for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
            if (isFarmingPlantSlot(crop, slot) && !farmingPlants.contains(slot) && canPlantCrop(crop, slot)) {
                plantable.add(slot);
            }
        }
        List<Integer> growable;
        if (crop.isStem()) {
            growable = farmingPlants.stream().filter(slot -> isAgeMax(miningBlock(slot)))
                    .filter(slot -> !availableProduceSlots(slot).isEmpty()).toList();
        } else if (crop.isColumnPlant()) {
            growable = farmingPlants.stream().filter(slot -> canGrowColumn(crop, slot)).toList();
        } else {
            growable = List.of();
        }

        int harvestCount = harvestPlants.size() + harvestProduce.size();
        int growthCount = plantable.size() + growable.size();
        boolean harvest = harvestCount > 0 && (growthCount == 0 || ThreadLocalRandom.current().nextBoolean());
        if (harvest) {
            int selected = ThreadLocalRandom.current().nextInt(harvestCount);
            if (selected < harvestProduce.size()) harvestStemProduce(type, harvestProduce.get(selected));
            else harvestPlant(type, crop, harvestPlants.get(selected - harvestProduce.size()));
            offlineHarvestNext = false;
        } else if (growthCount > 0) {
            int selected = ThreadLocalRandom.current().nextInt(growthCount);
            if (selected < growable.size()) {
                if (crop.isStem()) growStemProduce(crop, growable.get(selected));
                else growColumnPlant(crop, growable.get(selected));
            }
            else plantCrop(crop, plantable.get(selected - growable.size()));
            offlineHarvestNext = true;
        } else {
            offlineWorkable = !farmingPlants.isEmpty();
            return false;
        }
        offlineWorkable = true;
        animationTick = 0;
        playWorkSound(type.workSound());
        return true;
    }

    private void prepareFarmingGround(FarmingSettings settings) {
        FarmingSettings.Crop crop = settings.crop();
        for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
            Material expected = expectedFarmGround(crop, slot);
            if (expected == null) continue;
            prepareFarmGroundSlot(slot, expected);
        }
        Material center = expectedFarmGround(crop, CENTER_GROUND_SLOT);
        if (center != null) prepareFarmGroundSlot(CENTER_GROUND_SLOT, center);
    }

    private void prepareFarmGroundSlot(int slot, Material expected) {
        Block ground = farmingGroundBlock(slot);
        if (ground.getType() != expected) {
            originalFarmGround.putIfAbsent(slot, ground.getType());
            ground.setType(expected, false);
        }
        if (ground.getBlockData() instanceof Farmland farmland) {
            farmland.setMoisture(farmland.getMaximumMoisture());
            ground.setBlockData(farmland, false);
        }
    }

    private Material expectedFarmGround(FarmingSettings.Crop crop, int slot) {
        if (slot == CENTER_GROUND_SLOT) return crop.needsCenterWater() ? Material.WATER : null;
        if (crop == FarmingSettings.Crop.SUGAR_CANE) {
            return isFarmingPlantSlot(crop, slot) ? Material.SAND : Material.WATER;
        }
        if (crop.isStem()) return isFarmingPlantSlot(crop, slot) ? Material.FARMLAND : Material.DIRT;
        return isFarmingPlantSlot(crop, slot) ? crop.ground() : null;
    }

    private boolean isFarmingPlantSlot(FarmingSettings.Crop crop, int slot) {
        int[] offset = WORK_OFFSETS[slot];
        if (crop == FarmingSettings.Crop.CACTUS) return offset[0] % 2 == 0 && offset[1] % 2 == 0;
        if (crop == FarmingSettings.Crop.SUGAR_CANE || crop.isStem()) return offset[0] % 2 == 0;
        return true;
    }

    private boolean canPlantCrop(FarmingSettings.Crop crop, int slot) {
        Block plant = miningBlock(slot);
        if (!plant.getType().isAir() || farmingGroundBlock(slot).getType() != crop.ground()) return false;
        if (crop != FarmingSettings.Crop.CACTUS) return true;
        return plant.getRelative(1, 0, 0).getType().isAir()
                && plant.getRelative(-1, 0, 0).getType().isAir()
                && plant.getRelative(0, 0, 1).getType().isAir()
                && plant.getRelative(0, 0, -1).getType().isAir();
    }

    private void plantCrop(FarmingSettings.Crop crop, int slot) {
        Block plant = miningBlock(slot);
        faceLocation(plant.getLocation().add(0.5, 0.5, 0.5));
        plant.setType(crop.plant(), false);
        farmingPlants.add(slot);
    }

    public boolean accelerateNaturalGrowth(Block growingBlock, BlockState newState) {
        MinionType minionType = type().orElse(null);
        if (removed || lifecycleState != MinionLifecycleState.ACTIVE || location == null
                || minionType == null || minionType.farmingSettings() == null
                || growingBlock.getWorld() != location.getWorld()) return false;
        FarmingSettings.Crop crop = minionType.farmingSettings().crop();
        if (newState.getType() != crop.plant()) return false;

        int slot = ownedPlantSlot(growingBlock, crop);
        if (slot < 0) return false;
        if (crop.isColumnPlant()) {
            if (ThreadLocalRandom.current().nextDouble() < FARM_GROWTH_BONUS_CHANCE) {
                long generation = lifecycleGeneration;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (lifecycleState == MinionLifecycleState.ACTIVE
                            && generation == lifecycleGeneration) growBonusColumnStage(crop, slot);
                });
            }
            return true;
        }
        if (newState.getBlockData() instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge()
                && ThreadLocalRandom.current().nextDouble() < FARM_GROWTH_BONUS_CHANCE) {
            ageable.setAge(ageable.getAge() + 1);
            newState.setBlockData(ageable);
        }
        return true;
    }

    private int ownedPlantSlot(Block growingBlock, FarmingSettings.Crop crop) {
        for (int slot : farmingPlants) {
            Block base = miningBlock(slot);
            if (base.getType() != crop.plant() || base.getX() != growingBlock.getX()
                    || base.getZ() != growingBlock.getZ()) continue;
            if (crop.isColumnPlant()) {
                int height = growingBlock.getY() - base.getY();
                if (height >= 1 && height <= 2) return slot;
            } else if (base.getY() == growingBlock.getY()) {
                return slot;
            }
        }
        return -1;
    }

    private void growBonusColumnStage(FarmingSettings.Crop crop, int slot) {
        if (removed || !farmingPlants.contains(slot) || miningBlock(slot).getType() != crop.plant()
                || !canGrowColumn(crop, slot)) return;
        Block top = miningBlock(slot);
        while (top.getRelative(BlockFace.UP).getType() == crop.plant()) top = top.getRelative(BlockFace.UP);
        top.getRelative(BlockFace.UP).setType(crop.plant(), false);
    }

    private boolean isMaturePlant(FarmingSettings.Crop crop, int slot) {
        Block plant = miningBlock(slot);
        if (crop.isStem()) return false;
        if (crop.isColumnPlant()) return plant.getRelative(BlockFace.UP).getType() == crop.plant();
        if (crop == FarmingSettings.Crop.RED_MUSHROOM || crop == FarmingSettings.Crop.BROWN_MUSHROOM) return true;
        return isAgeMax(plant);
    }

    private boolean isAgeMax(Block block) {
        return block.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    private void harvestPlant(MinionType type, FarmingSettings.Crop crop, int slot) {
        Block plant = miningBlock(slot);
        faceLocation(plant.getLocation().add(0.5, 0.5, 0.5));
        if (crop.isColumnPlant()) {
            Block top = plant.getRelative(BlockFace.UP);
            while (top.getRelative(BlockFace.UP).getType() == crop.plant()) top = top.getRelative(BlockFace.UP);
            top.setType(Material.AIR, false);
        } else {
            plant.setType(Material.AIR, false);
            farmingPlants.remove(slot);
        }
        collectWorkOutput(type, type.dropAmount());
    }

    private boolean canGrowColumn(FarmingSettings.Crop crop, int slot) {
        Block top = miningBlock(slot);
        int height = 1;
        while (height < 3 && top.getRelative(BlockFace.UP).getType() == crop.plant()) {
            top = top.getRelative(BlockFace.UP);
            height++;
        }
        if (height >= 3 || !top.getRelative(BlockFace.UP).getType().isAir()) return false;
        if (crop != FarmingSettings.Crop.CACTUS) return true;
        Block next = top.getRelative(BlockFace.UP);
        return next.getRelative(1, 0, 0).getType().isAir()
                && next.getRelative(-1, 0, 0).getType().isAir()
                && next.getRelative(0, 0, 1).getType().isAir()
                && next.getRelative(0, 0, -1).getType().isAir();
    }

    private void growColumnPlant(FarmingSettings.Crop crop, int slot) {
        Block top = miningBlock(slot);
        while (top.getRelative(BlockFace.UP).getType() == crop.plant()) top = top.getRelative(BlockFace.UP);
        Block next = top.getRelative(BlockFace.UP);
        faceLocation(next.getLocation().add(0.5, 0.5, 0.5));
        next.setType(crop.plant(), false);
    }

    private void scanStemProduce(FarmingSettings.Crop crop) {
        Material produce = crop.produceBlock();
        if (produce == null) return;
        for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
            if (!isFarmingPlantSlot(crop, slot) && miningBlock(slot).getType() == produce
                    && hasAdjacentOwnedStem(slot)) farmingProduce.add(slot);
        }
    }

    private boolean hasAdjacentOwnedStem(int produceSlot) {
        int[] offset = WORK_OFFSETS[produceSlot];
        for (int direction : new int[]{-1, 1}) {
            int stemSlot = workSlotAt(offset[0] + direction, offset[1]);
            if (stemSlot >= 0 && farmingPlants.contains(stemSlot) && isAgeMax(miningBlock(stemSlot))) return true;
        }
        return false;
    }

    private List<Integer> availableProduceSlots(int stemSlot) {
        int[] offset = WORK_OFFSETS[stemSlot];
        List<Integer> result = new ArrayList<>();
        Material produceMaterial = type().map(MinionType::farmingSettings)
                .map(FarmingSettings::crop).map(FarmingSettings.Crop::produceBlock).orElse(null);
        if (produceMaterial == null) return result;
        for (int direction : new int[]{-1, 1}) {
            int adjacent = workSlotAt(offset[0] + direction, offset[1]);
            if (adjacent >= 0 && miningBlock(adjacent).getType() == produceMaterial) return result;
        }
        for (int direction : new int[]{-1, 1}) {
            int produceSlot = workSlotAt(offset[0] + direction, offset[1]);
            if (produceSlot >= 0 && !farmingProduce.contains(produceSlot)
                    && miningBlock(produceSlot).getType().isAir()) result.add(produceSlot);
        }
        return result;
    }

    private void growStemProduce(FarmingSettings.Crop crop, int stemSlot) {
        List<Integer> available = availableProduceSlots(stemSlot);
        if (available.isEmpty()) return;
        int produceSlot = available.get(ThreadLocalRandom.current().nextInt(available.size()));
        Block produce = miningBlock(produceSlot);
        faceLocation(produce.getLocation().add(0.5, 0.5, 0.5));
        produce.setType(crop.produceBlock(), false);
        farmingProduce.add(produceSlot);
    }

    private void harvestStemProduce(MinionType type, int slot) {
        Block produce = miningBlock(slot);
        faceLocation(produce.getLocation().add(0.5, 0.5, 0.5));
        produce.setType(Material.AIR, false);
        farmingProduce.remove(slot);
        collectWorkOutput(type, type.dropAmount());
    }

    private int workSlotAt(int x, int z) {
        for (int slot = 0; slot < WORK_OFFSETS.length; slot++) {
            if (WORK_OFFSETS[slot][0] == x && WORK_OFFSETS[slot][1] == z) return slot;
        }
        return -1;
    }

    private Block farmingGroundBlock(int slot) {
        if (slot == CENTER_GROUND_SLOT) return location.getBlock().getRelative(BlockFace.DOWN);
        return miningBlock(slot).getRelative(BlockFace.DOWN);
    }

    private void collectWorkOutput(MinionType type, RandomRange amountRange) {
        collectWorkOutput(type, amountRange, false);
    }

    private void collectWorkOutput(MinionType type, RandomRange amountRange,
                                   boolean deferredCollection) {
        ItemStack drop = resolver.create(type.drop(), amountRange.roll());
        if (drop != null) {
            int produced = drop.getAmount();
            int left = storage.add(drop);
            if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) {
                autoCraft.compact(storage, drop);
                if (left > 0) {
                    drop.setAmount(left);
                    left = storage.add(drop);
                }
            }
            recordCollection(type, produced, left, deferredCollection);
        }
        for (SpreadSettings spread : config.spreads()) {
            if (!hasUpgrade(spread.specialItemId())
                    || ThreadLocalRandom.current().nextDouble() >= spread.chance()) continue;
            ItemStack bonus = resolver.create(spread.reward(), Math.max(1, spread.amount().roll()));
            if (bonus != null) storage.add(bonus);
        }
    }

    private void recordCollection(MinionType type, int producedAmount, int finalLeftover,
                                  boolean deferredCollection) {
        CollectionSettings settings = type.collectionSettings();
        String collectionId = settings == null ? null : settings.id();
        int stored = ProductionCollectionAccounting.record(null, collectionId,
                producedAmount, finalLeftover);
        if (stored <= 0 || collectionId == null) return;
        if (deferredCollection) collectionProgress.recordDeferred(collectionId, stored);
        else collectionProgress.record(collectionId, stored);
    }

    public boolean flushPendingCollections() {
        return collectionProgress.flush();
    }

    public boolean hasPendingCollections() {
        return collectionProgress.hasPending();
    }

    private FuelType ensureFuel(long now) {
        FuelType active = activeFuelType(now);
        if (active != null) return active;
        fuelBurnUntil = 0L;
        activeFuelId = null;
        FuelType current = fuelTypeForStack();
        if (fuel == null || current == null) return null;
        if (current.infinite()) {
            fuelBurnUntil = Long.MAX_VALUE;
            activeFuelId = current.id();
            return current;
        }
        fuel.setAmount(fuel.getAmount() - 1);
        if (fuel.getAmount() <= 0) fuel = null;
        fuelBurnUntil = addBurnTime(now, current.burnTimeSeconds());
        activeFuelId = current.id();
        return current;
    }

    private FuelType fuelTypeForStack() {
        if (fuel == null) return null;
        return config.fuels().stream().filter(candidate -> resolver.matches(fuel, candidate.item())).findFirst().orElse(null);
    }

    private FuelType activeFuelType(long now) {
        FuelType active = config.fuel(activeFuelId).orElse(null);
        if (active == null) return null;
        if (active.infinite()) {
            if (fuel == null || !resolver.matches(fuel, active.item())) return null;
            fuelBurnUntil = Long.MAX_VALUE;
            return active;
        }
        return fuelBurnUntil <= now ? null : active;
    }

    private static long addBurnTime(long now, long seconds) {
        try {
            return Math.addExact(now, Math.multiplyExact(seconds, 1000L));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void playWorkSound(String configured) {
        if (location == null || location.getWorld() == null) return;
        NamespacedKey soundKey = soundKey(configured);
        Sound sound = soundKey == null ? null : Registry.SOUNDS.get(soundKey);
        if (sound == null) {
            plugin.getLogger().warning("未知的小人工作音效: " + configured);
            return;
        }
        location.getWorld().playSound(location, sound, SoundCategory.BLOCKS, 0.6f, 1.2f);
    }

    static NamespacedKey soundKey(String configured) {
        if (configured == null) return null;
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized.replace('_', '.');
        }
        return NamespacedKey.fromString(normalized);
    }

    private void animate() {
        if (entity == null || !entity.isValid() || animationTick < 0) return;
        double swing = -1.4 + Math.abs(5 - animationTick) * 0.24;
        entity.setRightArmPose(new EulerAngle(swing, 0.0, 0.0));
        animationTick++;
        if (animationTick > 10) {
            animationTick = -1;
            entity.setRightArmPose(EulerAngle.ZERO);
        }
    }

    public void spawn() {
        if (lifecycleState == MinionLifecycleState.SUSPENDED || location == null
                || !isChunkLoaded() || (entity != null && entity.isValid())) return;
        MinionType type = type().orElse(null);
        if (type == null) return;
        entity = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setSmall(true);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setCollidable(false);
            stand.setCanPickupItems(false);
            stand.setRemoveWhenFarAway(false);
            stand.setPersistent(true);
            stand.customName(LegacyComponentSerializer.legacySection().deserialize(type.displayName() + " §e" + level));
            stand.setCustomNameVisible(true);
            stand.getPersistentDataContainer().set(plugin.key("minion_entity"), PersistentDataType.STRING, id.toString());
        });
        updateModel();
    }

    public void updateModel() {
        if (entity == null || !entity.isValid()) return;
        MinionType type = type().orElse(null);
        if (type == null) return;
        entity.customName(LegacyComponentSerializer.legacySection().deserialize(type.displayName() + " §e" + level));
        EntityEquipment equipment = entity.getEquipment();
        equipment.clear();
        ModelSettings model = type.model();
        equipment.setHelmet(modelItem(model.helmet(), model));
        equipment.setChestplate(modelItem(model.chestplate(), model));
        equipment.setLeggings(modelItem(model.leggings(), model));
        equipment.setBoots(modelItem(model.boots(), model));
        equipment.setItemInMainHand(modelItem(model.tool(), model));
    }

    private ItemStack modelItem(com.xigua.yknminions.item.ItemSpec spec, ModelSettings model) {
        if (spec == null) return null;
        ItemStack item = resolver.create(spec);
        if (item != null && item.getItemMeta() instanceof LeatherArmorMeta leather) {
            leather.setColor(model.leatherColor());
            item.setItemMeta(leather);
        }
        return item;
    }

    public void removeEntity() {
        removed = true;
        lifecycleState = MinionLifecycleState.SUSPENDED;
        lifecycleGeneration++;
        targetAttackSequence++;
        splitPending = false;
        if (mobTarget != null) {
            mobTarget.remove();
            mobTarget = null;
        }
        targetQueue.forEach(Entity::remove);
        targetQueue.clear();
        pendingMobSizes.clear();
        pendingMobKillAt = 0L;
        if (entity != null) {
            entity.remove();
            entity = null;
        }
    }

    public boolean isEntity(ArmorStand stand) {
        return entity != null && entity.getUniqueId().equals(stand.getUniqueId());
    }

    public void save(ConfigurationSection section) {
        section.set("owner", owner.toString());
        section.set("type", typeId);
        section.set("level", level);
        section.set("world", position.worldName());
        section.set("x", position.x());
        section.set("y", position.y());
        section.set("z", position.z());
        section.set("yaw", position.yaw());
        section.set("next-work-at", nextWorkAt);
        OfflineStateCodec.save(section, offlineHarvestNext, offlineWorkable,
                List.copyOf(pendingMobSizes), pendingMobKillAt);
        section.set("fuel-burn-until", fuelBurnUntil);
        section.set("fuel-actions-remaining", null);
        section.set("active-fuel", activeFuelId);
        section.set("fuel", fuel);
        section.set("upgrade-one", upgradeOne);
        section.set("upgrade-two", upgradeTwo);
        section.set("storage", storage.snapshot());
        Map<String, Double> pendingCollections = collectionProgress.snapshot();
        section.set("pending-collections", pendingCollections.isEmpty()
                ? null : new java.util.TreeMap<>(pendingCollections));
        PreparedClaimCodec.save(section, preparedClaim, preparedClaimBlocked);
        section.set("generated-blocks", generatedBlocks.stream().sorted().toList());
        section.set("farming-plants", farmingPlants.stream().sorted().toList());
        section.set("farming-produce", farmingProduce.stream().sorted().toList());
        Map<String, String> savedGround = new HashMap<>();
        originalFarmGround.forEach((slot, material) -> savedGround.put(String.valueOf(slot), material.getKey().toString()));
        section.set("farming-original-ground", savedGround);
    }

    public Optional<MinionType> type() { return config.minionType(typeId); }
    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public String typeId() { return typeId; }
    public int level() { return level; }
    public void level(int level) {
        this.level = level;
        storage.unlockedSlots(config.storageSlots(level));
        updateModel();
    }
    public void refreshConfiguration() {
        storage.unlockedSlots(config.storageSlots(level));
        updateModel();
    }
    public MinionPosition position() { return position; }
    public MinionLifecycleState lifecycleState() { return lifecycleState; }
    public boolean catchingUp() { return lifecycleState == MinionLifecycleState.CATCHING_UP; }
    public MinionStorage storage() { return storage; }
    public Optional<PreparedClaim> preparedClaim() { return Optional.ofNullable(preparedClaim); }
    public boolean preparedClaimBlocked() { return preparedClaimBlocked; }
    public PreparedClaim prepareClaim(UUID recipientId) {
        if (preparedClaim != null) return preparedClaim;
        List<ItemStack> items = storage.reserveAll();
        if (items.isEmpty()) return null;
        try {
            UUID claimId = UUID.randomUUID();
            preparedClaimBlocked = false;
            SkillSettings skill = type().map(MinionType::skillSettings).orElse(null);
            List<PreparedClaimLine> lines = new ArrayList<>(items.size());
            for (ItemStack item : items) {
                SkillRewardSettings reward = skill == null
                        ? null : skill.reward(item, resolver).orElse(null);
                lines.add(reward == null
                        ? PreparedClaimLine.withoutSkill(item)
                        : new PreparedClaimLine(item, skill.provider(), skill.skillId(),
                        reward.xpPerBaseUnit(), reward.equivalent()));
            }
            preparedClaim = new PreparedClaim(claimId, recipientId,
                    "minion:" + id + ":claim:" + claimId, lines);
            return preparedClaim;
        } catch (RuntimeException failure) {
            storage.releaseReservation();
            throw failure;
        }
    }

    public PreparedClaimSettlement settlePreparedClaim(
            String operationId, List<Long> deliveredPerLine) {
        if (preparedClaimBlocked || preparedClaim == null
                || !preparedClaim.operationId().equals(operationId)) return null;
        PreparedClaimSettlement settlement = new PreparedClaimSettlement(
                preparedClaim, storage.stateSnapshot(), preparedClaimBlocked);
        if (!storage.settleReservation(preparedClaim.items(), deliveredPerLine)) {
            preparedClaimBlocked = true;
            return null;
        }
        preparedClaim = null;
        preparedClaimBlocked = false;
        return settlement;
    }

    public void rollbackPreparedClaimSettlement(PreparedClaimSettlement settlement) {
        if (settlement == null || preparedClaim != null) {
            throw new IllegalStateException("cannot roll back prepared claim settlement");
        }
        storage.restoreState(settlement.storageState());
        preparedClaim = settlement.claim();
        preparedClaimBlocked = settlement.blocked();
    }

    public boolean blockPreparedClaim(String operationId) {
        if (preparedClaim == null || !preparedClaim.operationId().equals(operationId)) return false;
        preparedClaimBlocked = true;
        return true;
    }
    public boolean commitPreparedClaim(String operationId) {
        if (preparedClaimBlocked || preparedClaim == null || !preparedClaim.operationId().equals(operationId)) return false;
        if (!storage.commitReservation()) return false;
        preparedClaim = null;
        preparedClaimBlocked = false;
        return true;
    }
    public boolean releasePreparedClaim(String operationId) {
        if (preparedClaimBlocked || preparedClaim == null || !preparedClaim.operationId().equals(operationId)) return false;
        if (!storage.releaseReservation()) return false;
        preparedClaim = null;
        preparedClaimBlocked = false;
        return true;
    }
    public ItemStack fuel() { return cloneOrNull(fuel); }
    public void fuel(ItemStack item) {
        fuel = cloneOrNull(item);
        FuelType active = config.fuel(activeFuelId).orElse(null);
        if (active != null && active.infinite()
                && (fuel == null || !resolver.matches(fuel, active.item()))) {
            activeFuelId = null;
            fuelBurnUntil = 0L;
        }
    }
    public ItemStack upgradeOne() { return cloneOrNull(upgradeOne); }
    public void upgradeOne(ItemStack item) { upgradeOne = cloneOrNull(item); }
    public ItemStack upgradeTwo() { return cloneOrNull(upgradeTwo); }
    public void upgradeTwo(ItemStack item) { upgradeTwo = cloneOrNull(item); }
    public long fuelBurnUntil() { return fuelBurnUntil; }
    public long fuelRemainingSeconds() {
        if (fuelBurnUntil == Long.MAX_VALUE) return Long.MAX_VALUE;
        long remainingMillis = Math.max(0L, fuelBurnUntil - System.currentTimeMillis());
        return remainingMillis / 1000L + (remainingMillis % 1000L == 0L ? 0L : 1L);
    }
    public boolean storageFull() { return storage.isFull(); }

    public record PreparedClaimSettlement(
            PreparedClaim claim,
            MinionStorage.StateSnapshot storageState,
            boolean blocked
    ) { }

    public void removeGeneratedBlocks() {
        MinionType type = type().orElse(null);
        if (type != null && type.miningSettings() != null && location != null && location.getWorld() != null) {
            for (int slot : generatedBlocks) {
                var block = miningBlock(slot);
                if (block.getType() == type.miningSettings().block()) block.setType(Material.AIR, false);
            }
        }
        generatedBlocks.clear();
        if (type != null && type.farmingSettings() != null && location != null && location.getWorld() != null) {
            FarmingSettings.Crop crop = type.farmingSettings().crop();
            for (int slot : farmingPlants) {
                Block plant = miningBlock(slot);
                if (plant.getType() == crop.plant()) plant.setType(Material.AIR, false);
                if (crop.isColumnPlant()) {
                    Block above = plant.getRelative(BlockFace.UP);
                    for (int height = 0; height < 2 && above.getType() == crop.plant(); height++) {
                        above.setType(Material.AIR, false);
                        above = above.getRelative(BlockFace.UP);
                    }
                }
            }
            Material produce = crop.produceBlock();
            if (produce != null) {
                for (int slot : farmingProduce) {
                    Block block = miningBlock(slot);
                    if (block.getType() == produce) block.setType(Material.AIR, false);
                }
            }
            originalFarmGround.forEach((slot, original) -> {
                Material expected = expectedFarmGround(crop, slot);
                Block ground = farmingGroundBlock(slot);
                if (expected != null && ground.getType() == expected) ground.setType(original, false);
            });
        }
        farmingPlants.clear();
        farmingProduce.clear();
        originalFarmGround.clear();
    }

    public boolean hasUpgrade(String id) {
        return specialItems.is(upgradeOne, id) || specialItems.is(upgradeTwo, id);
    }

    public boolean isChunk(int x, int z, World world) {
        return world != null && position.isChunk(world.getName(), x, z);
    }

    private boolean isChunkLoaded() {
        return hasCurrentLoadedChunk();
    }

    public boolean hasCurrentLoadedChunk() {
        if (location == null || location.getWorld() == null) return false;
        World registered = Bukkit.getWorld(position.worldName());
        return registered == location.getWorld()
                && registered.isChunkLoaded(position.chunkX(), position.chunkZ());
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0 ? null : item.clone();
    }
}
