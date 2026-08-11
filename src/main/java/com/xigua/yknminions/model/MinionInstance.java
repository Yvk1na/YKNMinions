package com.xigua.yknminions.model;

import com.xigua.Main;
import com.xigua.yknminions.config.*;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.service.AutoCraftService;
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
    private final PluginConfig config;
    private final ItemResolver resolver;
    private final SpecialItemService specialItems;
    private final AutoCraftService autoCraft;
    private final UUID id;
    private final UUID owner;
    private final String typeId;
    private final Location location;
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
    private ArmorStand entity;
    private final Deque<LivingEntity> targetQueue = new ArrayDeque<>();
    private LivingEntity mobTarget;
    private boolean splitPending;
    private boolean removed;
    private long targetAttackSequence;
    private long lastTargetKillAt;
    private int animationTick = -1;

    public MinionInstance(Main plugin, PluginConfig config, ItemResolver resolver, SpecialItemService specialItems,
                          AutoCraftService autoCraft, UUID id, UUID owner, String typeId, int level,
                          Location location, List<Integer> generatedBlocks, List<Integer> farmingPlants,
                          List<Integer> farmingProduce, Map<Integer, Material> originalFarmGround,
                          List<ItemStack> storage,
                          ItemStack fuel, ItemStack upgradeOne,
                          ItemStack upgradeTwo, long fuelBurnUntil, int legacyFuelActionsRemaining,
                          String activeFuelId, long nextWorkAt) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.specialItems = specialItems;
        this.autoCraft = autoCraft;
        this.id = id;
        this.owner = owner;
        this.typeId = typeId;
        this.level = level;
        this.location = location.clone();
        this.storage = new MinionStorage(config.storageSlots(), config.storageSlots(level), storage);
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
            if (this.fuelBurnUntil <= now) {
                this.fuelBurnUntil = 0L;
                this.activeFuelId = null;
            }
        }
        this.nextWorkAt = nextWorkAt <= 0 ? System.currentTimeMillis() + 1000 : nextWorkAt;
    }

    public void tick(long now) {
        if ((entity == null || !entity.isValid()) && isChunkLoaded()) spawn();
        animate();
        MinionType type = type().orElse(null);
        if (type == null) return;

        if ((type.mobSettings() != null || type.miningSettings() != null || type.farmingSettings() != null)
                && !isChunkLoaded()) return;
        if (type.mobSettings() != null) {
            if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) autoCraft.compact(storage, null);
            resumeTargetCycle(type);
            if (targetCycleBusy()) return;
        }
        if (now < nextWorkAt) return;

        if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) autoCraft.compact(storage, null);
        if (type.farmingSettings() != null) prepareFarmingGround(type.farmingSettings());
        if (storage.isFull()) {
            FuelType active = activeFuelType(now);
            double multiplier = active == null ? 1.0 : active.speedMultiplier();
            nextWorkAt = now + Math.max(250L, Math.round(type.level(level).workIntervalSeconds() * 1000.0 / multiplier));
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
        double interval = type.level(level).workIntervalSeconds();
        double multiplier = activeFuel == null ? 1.0 : activeFuel.speedMultiplier();
        nextWorkAt = now + Math.max(250L, Math.round(interval * 1000.0 / multiplier));
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
        long attackDelay = type.mobSettings().turnDelayTicks();
        if (lastTargetKillAt > 0L) {
            long cooldownRemaining = TARGET_KILL_INTERVAL_MILLIS - (System.currentTimeMillis() - lastTargetKillAt);
            if (cooldownRemaining > 0L) attackDelay = Math.max(attackDelay, (cooldownRemaining + 49L) / 50L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> attackTarget(type, next, sequence),
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

    private void attackTarget(MinionType type, LivingEntity target, long sequence) {
        if (removed || sequence != targetAttackSequence || target != mobTarget
                || !target.isValid() || target.isDead()) return;
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
        lastTargetKillAt = System.currentTimeMillis();
        collectWorkOutput(type, type.mobSettings().dropPerKill());
        if (childSize > 0) {
            splitPending = true;
            int childCount = ThreadLocalRandom.current().nextInt(2, 5);
            Bukkit.getScheduler().runTask(plugin, () -> spawnSplitChildren(type, splitAt, childSize, childCount));
        } else {
            resumeTargetCycle(type);
        }
    }

    private void spawnSplitChildren(MinionType type, Location splitAt, int childSize, int childCount) {
        if (removed) return;
        double spread = Math.max(0.25, childSize * 0.25);
        for (int i = 0; i < childCount; i++) {
            double x = ((i % 2) - 0.5) * spread;
            double z = ((i / 2) - 0.5) * spread;
            EntityType childType = type.mobSettings().entityType();
            LivingEntity child = createTrackedTarget(splitAt.clone().add(x, 0.1, z), childType, childSize);
            if (child != null) targetQueue.addLast(child);
        }
        splitPending = false;
        resumeTargetCycle(type);
    }

    private void performMiningWork(MinionType type) {
        MiningSettings settings = type.miningSettings();
        if (settings == null || location.getWorld() == null) return;
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
        } else if (!placeable.isEmpty()) {
            int slot = placeable.get(ThreadLocalRandom.current().nextInt(placeable.size()));
            var block = miningBlock(slot);
            faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
            block.setType(settings.block(), false);
            generatedBlocks.add(slot);
        } else {
            return;
        }
        animationTick = 0;
        playWorkSound(type.workSound());
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

    private void performFarmingWork(MinionType type) {
        FarmingSettings settings = type.farmingSettings();
        if (settings == null || location.getWorld() == null) return;
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
        } else if (growthCount > 0) {
            int selected = ThreadLocalRandom.current().nextInt(growthCount);
            if (selected < growable.size()) {
                if (crop.isStem()) growStemProduce(crop, growable.get(selected));
                else growColumnPlant(crop, growable.get(selected));
            }
            else plantCrop(crop, plantable.get(selected - growable.size()));
        } else {
            return;
        }
        animationTick = 0;
        playWorkSound(type.workSound());
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
        if (removed || minionType == null || minionType.farmingSettings() == null
                || growingBlock.getWorld() != location.getWorld()) return false;
        FarmingSettings.Crop crop = minionType.farmingSettings().crop();
        if (newState.getType() != crop.plant()) return false;

        int slot = ownedPlantSlot(growingBlock, crop);
        if (slot < 0) return false;
        if (crop.isColumnPlant()) {
            if (ThreadLocalRandom.current().nextDouble() < FARM_GROWTH_BONUS_CHANCE) {
                plugin.getServer().getScheduler().runTask(plugin, () -> growBonusColumnStage(crop, slot));
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
        ItemStack drop = resolver.create(type.drop(), amountRange.roll());
        if (drop != null) {
            int left = storage.add(drop);
            if (hasUpgrade(SpecialItemService.AUTO_CRAFT)) {
                autoCraft.compact(storage, drop);
                if (left > 0) {
                    drop.setAmount(left);
                    storage.add(drop);
                }
            }
        }
        for (SpreadSettings spread : config.spreads()) {
            if (!hasUpgrade(spread.specialItemId())
                    || ThreadLocalRandom.current().nextDouble() >= spread.chance()) continue;
            ItemStack bonus = resolver.create(spread.reward(), Math.max(1, spread.amount().roll()));
            if (bonus != null) storage.add(bonus);
        }
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
        if (location.getWorld() == null) return;
        try {
            location.getWorld().playSound(location, Sound.valueOf(configured.toUpperCase()), SoundCategory.BLOCKS, 0.6f, 1.2f);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("未知的小人工作音效: " + configured);
        }
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
        if (!isChunkLoaded() || (entity != null && entity.isValid())) return;
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
        targetAttackSequence++;
        splitPending = false;
        if (mobTarget != null) {
            mobTarget.remove();
            mobTarget = null;
        }
        targetQueue.forEach(Entity::remove);
        targetQueue.clear();
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
        section.set("world", location.getWorld() == null ? null : location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("next-work-at", nextWorkAt);
        section.set("fuel-burn-until", fuelBurnUntil);
        section.set("fuel-actions-remaining", null);
        section.set("active-fuel", activeFuelId);
        section.set("fuel", fuel);
        section.set("upgrade-one", upgradeOne);
        section.set("upgrade-two", upgradeTwo);
        section.set("storage", storage.snapshot());
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
    public Location location() { return location.clone(); }
    public MinionStorage storage() { return storage; }
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

    public void removeGeneratedBlocks() {
        MinionType type = type().orElse(null);
        if (type != null && type.miningSettings() != null && location.getWorld() != null) {
            for (int slot : generatedBlocks) {
                var block = miningBlock(slot);
                if (block.getType() == type.miningSettings().block()) block.setType(Material.AIR, false);
            }
        }
        generatedBlocks.clear();
        if (type != null && type.farmingSettings() != null && location.getWorld() != null) {
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
        return location.getWorld() != null && location.getWorld().equals(world)
                && location.getBlockX() >> 4 == x && location.getBlockZ() >> 4 == z;
    }

    private boolean isChunkLoaded() {
        return location.getWorld() != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.clone();
    }
}
