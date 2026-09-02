package com.xigua.yknminions.service;

import com.xigua.Main;
import com.xigua.yknminions.config.*;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.integration.CollectionIntegration;
import com.xigua.yknminions.integration.SkillXpIntegration;
import com.xigua.yknminions.model.MinionInstance;
import com.xigua.yknminions.model.MinionLifecycleState;
import com.xigua.yknminions.model.MinionPosition;
import com.xigua.yknminions.model.OfflineStateCodec;
import com.xigua.yknminions.model.PreparedClaim;
import com.xigua.yknminions.model.PreparedClaimCodec;
import com.xigua.yknminions.model.PreparedClaimLine;
import com.xigua.yknminions.util.InventoryUtil;
import dev.chengzhi.skyblockcore.api.delivery.CapacityPolicy;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryAtomicity;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryPolicy;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryRequest;
import dev.chengzhi.skyblockcore.api.delivery.DetailedDeliveryResult;
import dev.chengzhi.skyblockcore.api.delivery.DetailedItemDeliveryApi;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryLineResult;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryStatus;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

public final class MinionManager {
    private final Main plugin;
    private final PluginConfig config;
    private final ItemResolver resolver;
    private final SpecialItemService specialItems;
    private final AutoCraftService autoCraft;
    private final DetailedItemDeliveryApi deliveryApi;
    private final SkillXpIntegration skillXpIntegration;
    private final CollectionIntegration collectionIntegration;
    private final Map<UUID, MinionInstance> minions = new LinkedHashMap<>();
    private final Set<UUID> activeMinions = new LinkedHashSet<>();
    private final Deque<UUID> catchUpQueue = new ArrayDeque<>();
    private final Set<UUID> queuedCatchUps = new HashSet<>();
    private final Map<UUID, Long> catchUpTargets = new HashMap<>();
    private final Set<UUID> dirtyCatchUps = new HashSet<>();
    private final Set<String> deliveriesInFlight = new HashSet<>();
    private final File dataFile;
    private final File backupDataFile;
    private final File temporaryDataFile;
    private boolean dataWritesBlocked;
    private boolean recoveredFromBackup;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private BukkitTask catchUpTask;
    private BukkitTask offlineScanTask;

    public MinionManager(Main plugin, PluginConfig config, ItemResolver resolver,
                         SpecialItemService specialItems, AutoCraftService autoCraft,
                         DetailedItemDeliveryApi deliveryApi,
                         SkillXpIntegration skillXpIntegration,
                         CollectionIntegration collectionIntegration) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.specialItems = specialItems;
        this.autoCraft = autoCraft;
        this.deliveryApi = deliveryApi;
        this.skillXpIntegration = skillXpIntegration;
        this.collectionIntegration = collectionIntegration;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.backupDataFile = new File(plugin.getDataFolder(), "data.yml.bak");
        this.temporaryDataFile = new File(plugin.getDataFolder(), "data.yml.tmp");
    }

    public void load() {
        YamlConfiguration yaml = loadDataConfiguration();
        if (yaml == null) return;
        ConfigurationSection root = yaml.getConfigurationSection("minions");
        if (root == null) return;
        cleanupLoadedEntities();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            loadMinionRecord(key, section);
        }
        long now = System.currentTimeMillis();
        minions.values().forEach(minion -> prepareLoadedMinion(minion, now));
        Bukkit.getScheduler().runTask(plugin, this::retryPreparedClaims);
    }

    private YamlConfiguration loadDataConfiguration() {
        if (!dataFile.exists()) return new YamlConfiguration();
        YamlConfiguration primary = YamlConfiguration.loadConfiguration(dataFile);
        if (primary.isConfigurationSection("minions")) return primary;

        if (backupDataFile.exists()) {
            YamlConfiguration backup = YamlConfiguration.loadConfiguration(backupDataFile);
            if (backup.isConfigurationSection("minions")) {
                recoveredFromBackup = true;
                plugin.getLogger().severe("data.yml 无法读取，已从 data.yml.bak 恢复小人记录。");
                return backup;
            }
        }
        dataWritesBlocked = true;
        plugin.getLogger().severe("data.yml 存在但缺少有效的 minions 节点。为防止清空小人数据，本次运行已禁止覆盖该文件。");
        return null;
    }

    private void loadMinionRecord(String key, ConfigurationSection section) {
        try {
            UUID minionId = UUID.fromString(key);
            if (minions.containsKey(minionId)) return;
            String worldName = Objects.requireNonNull(section.getString("world"), "world");
            String typeId = section.getString("type", "slime");
            if (config.minionType(typeId).isEmpty()) {
                throw new IllegalArgumentException("未知的小人类型: " + typeId);
            }
            MinionPosition position = new MinionPosition(worldName,
                    section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw"));
            List<ItemStack> storage = section.getList("storage", List.of()).stream()
                    .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList();
            Map<Integer, Material> originalFarmGround = loadOriginalFarmGround(section);
            PreparedClaimCodec.LoadedClaim loadedClaim = loadPreparedClaim(minionId, section);
            PreparedClaim preparedClaim = loadedClaim == null ? null : loadedClaim.claim();
            OfflineStateCodec.Loaded offline = OfflineStateCodec.load(section);
            MinionInstance minion = new MinionInstance(plugin, config, resolver, specialItems, autoCraft,
                    collectionIntegration,
                    minionId, UUID.fromString(Objects.requireNonNull(section.getString("owner"))),
                    typeId, section.getInt("level", 1), position,
                    section.getIntegerList("generated-blocks"), section.getIntegerList("farming-plants"),
                    section.getIntegerList("farming-produce"), originalFarmGround, storage, preparedClaim,
                    loadedClaim != null && loadedClaim.blocked(), loadPendingCollections(section),
                    section.getItemStack("fuel"), section.getItemStack("upgrade-one"), section.getItemStack("upgrade-two"),
                    section.getLong("fuel-burn-until", 0L), section.getInt("fuel-actions-remaining", 0),
                    section.getString("active-fuel"), section.getLong("next-work-at", 0),
                    offline.harvestNext(), offline.workable(),
                    offline.pendingMobSizes(), offline.pendingMobKillAt());
            minions.put(minion.id(), minion);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "无法加载小人 " + key + "；本次运行不会覆盖现有 data.yml。", exception);
            dataWritesBlocked = true;
        }
    }

    private void prepareLoadedMinion(MinionInstance minion, long targetAt) {
        World world = Bukkit.getWorld(minion.position().worldName());
        if (world != null && world.isChunkLoaded(minion.position().chunkX(), minion.position().chunkZ())) {
            minion.beginCatchUp(world);
        }
        queueCatchUp(minion, targetAt);
    }

    public void onWorldLoad(World world) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getWorld(world.getName()) != world) return;
            cleanupLoadedEntities(world);
            long now = System.currentTimeMillis();
            minions.values().stream()
                    .filter(minion -> minion.position().worldName().equals(world.getName()))
                    .filter(minion -> world.isChunkLoaded(
                            minion.position().chunkX(), minion.position().chunkZ()))
                    .forEach(minion -> bindAndQueue(minion, world, now));
        });
    }

    public void onWorldUnload(World world) {
        long now = System.currentTimeMillis();
        minions.values().stream()
                .filter(minion -> minion.position().worldName().equals(world.getName()))
                .forEach(minion -> suspendMinion(minion, now));
        save();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getWorld(world.getName()) != world) return;
            cleanupLoadedEntities(world);
            long retryAt = System.currentTimeMillis();
            minions.values().stream()
                    .filter(minion -> minion.position().worldName().equals(world.getName()))
                    .filter(minion -> world.isChunkLoaded(
                            minion.position().chunkX(), minion.position().chunkZ()))
                    .forEach(minion -> bindAndQueue(minion, world, retryAt));
        });
    }

    public void onChunkUnload(Chunk chunk) {
        long now = System.currentTimeMillis();
        List<MinionInstance> affected = minions.values().stream()
                .filter(minion -> minion.isChunk(chunk.getX(), chunk.getZ(), chunk.getWorld()))
                .toList();
        affected.forEach(minion -> suspendMinion(minion, now));
        if (!affected.isEmpty()) save();
        if (!affected.isEmpty()) scheduleLoadedChunkRefresh(chunk);
    }

    private void suspendMinion(MinionInstance minion, long now) {
        activeMinions.remove(minion.id());
        minion.suspend(now);
        queueCatchUp(minion, now);
    }

    private void bindAndQueue(MinionInstance minion, World world, long targetAt) {
        activeMinions.remove(minion.id());
        minion.beginCatchUp(world);
        queueCatchUp(minion, targetAt);
    }

    private void queueCatchUp(MinionInstance minion, long targetAt) {
        minion.markCatchingUp();
        catchUpTargets.merge(minion.id(), targetAt, Math::max);
        if (queuedCatchUps.add(minion.id())) catchUpQueue.addLast(minion.id());
    }

    public void onPlayerJoin(Player player) {
        boolean changed = minions.values().stream()
                .filter(minion -> minion.owner().equals(player.getUniqueId()))
                .filter(minion -> !minion.catchingUp())
                .map(MinionInstance::flushPendingCollections)
                .reduce(false, Boolean::logicalOr);
        if (changed && !save()) {
            plugin.getLogger().severe("EcoCollections 待结算进度已发放，但 data.yml 保存失败；"
                    + "为避免重复增加，本次运行不会自动重试，重启前请先修复磁盘写入。");
        }
    }

    public void startTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (UUID id : List.copyOf(activeMinions)) {
                MinionInstance minion = minions.get(id);
                if (minion == null || minion.lifecycleState() != MinionLifecycleState.ACTIVE) {
                    activeMinions.remove(id);
                    continue;
                }
                try {
                    minion.tick(now);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE,
                            "小人 " + id + " 工作异常，已暂停并转入安全补算队列。", failure);
                    suspendMinion(minion, now);
                }
            }
        }, config.tickPeriod(), config.tickPeriod());
        catchUpTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processCatchUps, 1L, 1L);
        offlineScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            minions.values().stream()
                    .filter(minion -> minion.lifecycleState() != MinionLifecycleState.ACTIVE)
                    .forEach(minion -> queueCatchUp(minion, now));
        }, config.offlineSettleIntervalTicks(), config.offlineSettleIntervalTicks());
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, config.saveIntervalTicks(), config.saveIntervalTicks());
    }

    private void processCatchUps() {
        if (catchUpQueue.isEmpty()) return;
        int remainingActions = config.offlineMaxActionsPerTick();
        long deadline = System.nanoTime() + config.offlineMaxMillisPerTick() * 1_000_000L;
        boolean changed = false;
        List<MinionInstance> completed = new ArrayList<>();

        while (remainingActions > 0 && !catchUpQueue.isEmpty() && System.nanoTime() < deadline) {
            UUID id = catchUpQueue.removeFirst();
            MinionInstance minion = minions.get(id);
            Long targetAt = catchUpTargets.get(id);
            if (minion == null || targetAt == null) {
                queuedCatchUps.remove(id);
                catchUpTargets.remove(id);
                continue;
            }
            int slice = Math.min(remainingActions, 256);
            MinionInstance.CatchUpResult result;
            try {
                result = minion.catchUp(targetAt, slice, config.offlineProductionEnabled(), deadline);
            } catch (RuntimeException failure) {
                queuedCatchUps.remove(id);
                catchUpTargets.remove(id);
                dirtyCatchUps.remove(id);
                activeMinions.remove(id);
                minion.suspend(System.currentTimeMillis());
                plugin.getLogger().log(Level.SEVERE,
                        "小人 " + id + " 离线收益补算失败；已暂停该小人并保留记录，稍后会单独重试。", failure);
                continue;
            }
            remainingActions -= Math.max(1, result.processedActions());
            if (result.changed()) dirtyCatchUps.add(id);
            if (!result.complete()) {
                catchUpQueue.addLast(id);
                continue;
            }

            queuedCatchUps.remove(id);
            catchUpTargets.remove(id);
            changed |= dirtyCatchUps.remove(id);
            if (minion.lifecycleState() == MinionLifecycleState.CATCHING_UP) {
                if (minion.activate()) activeMinions.add(id);
                else minion.suspend(System.currentTimeMillis());
            }
            completed.add(minion);
        }

        boolean pendingCollections = collectionIntegration.available()
                && completed.stream().anyMatch(MinionInstance::hasPendingCollections);
        if ((changed || pendingCollections) && !completed.isEmpty() && save()) {
            boolean flushed = completed.stream().map(MinionInstance::flushPendingCollections)
                    .reduce(false, Boolean::logicalOr);
            if (flushed && !save()) {
                plugin.getLogger().severe("EcoCollections 离线进度已提交，但清理待结算记录时保存失败；"
                        + "请在重启前修复磁盘写入，以免重复提交。");
            }
        }
    }

    public void restartTasks() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        if (catchUpTask != null) catchUpTask.cancel();
        if (offlineScanTask != null) offlineScanTask.cancel();
        startTasks();
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        if (catchUpTask != null) catchUpTask.cancel();
        if (offlineScanTask != null) offlineScanTask.cancel();
        long now = System.currentTimeMillis();
        minions.values().forEach(minion -> minion.suspend(now));
        activeMinions.clear();
        save();
    }

    public MinionInstance create(Player owner, String typeId, int level, Location location) {
        if (config.minionType(typeId).isEmpty()) return null;
        float yaw = Math.round(owner.getLocation().getYaw() / 90f) * 90f + 180f;
        location.setYaw(yaw);
        MinionPosition position = MinionPosition.from(location);
        MinionInstance minion = new MinionInstance(plugin, config, resolver, specialItems, autoCraft,
                collectionIntegration,
                UUID.randomUUID(), owner.getUniqueId(), typeId, Math.max(1, Math.min(config.maxLevel(), level)),
                position, List.of(), List.of(), List.of(), Map.of(), List.of(),
                null, false, Map.of(), null, null, null, 0L, 0, null,
                System.currentTimeMillis() + 1000, true, true, List.of(), 0L);
        minions.put(minion.id(), minion);
        try {
            minion.beginCatchUp(location.getWorld());
            if (!minion.activate()) throw new IllegalStateException("minion chunk is not loaded");
            activeMinions.add(minion.id());
            save();
            return minion;
        } catch (RuntimeException exception) {
            minions.remove(minion.id());
            minion.removeEntity();
            plugin.getLogger().log(Level.SEVERE, "创建小人失败，已清理未完成的实体", exception);
            return null;
        }
    }

    public boolean canPlace(Location location) {
        if (location.getWorld() == null || !location.getBlock().isPassable()
                || !location.clone().add(0, 1, 0).getBlock().isPassable()) return false;
        return minions.values().stream().noneMatch(minion -> minion.position().distanceSquared(location) < 2.25);
    }

    public Optional<MinionInstance> byEntity(ArmorStand stand) {
        String raw = stand.getPersistentDataContainer().get(plugin.key("minion_entity"), PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try {
            MinionInstance minion = minions.get(UUID.fromString(raw));
            return minion != null && minion.isEntity(stand) ? Optional.of(minion) : Optional.empty();
        }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public Optional<MinionInstance> byTarget(LivingEntity target) {
        String raw = target.getPersistentDataContainer().get(plugin.key("minion_target"), PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try { return Optional.ofNullable(minions.get(UUID.fromString(raw))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public Optional<MinionInstance> byId(UUID id) { return Optional.ofNullable(minions.get(id)); }

    public Collection<MinionInstance> allMinions() { return List.copyOf(minions.values()); }

    public void accelerateFarmingGrowth(Block growingBlock, BlockState newState) {
        for (UUID id : List.copyOf(activeMinions)) {
            MinionInstance minion = minions.get(id);
            if (minion == null) continue;
            if (minion.accelerateNaturalGrowth(growingBlock, newState)) return;
        }
    }

    public void onChunkLoad(Chunk chunk) {
        scheduleLoadedChunkRefresh(chunk);
    }

    private void scheduleLoadedChunkRefresh(Chunk chunk) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        Bukkit.getScheduler().runTask(plugin, () -> refreshLoadedChunk(
                chunk, world, worldName, chunkX, chunkZ));
    }

    private void refreshLoadedChunk(Chunk chunk, World world, String worldName,
                                    int chunkX, int chunkZ) {
        if (Bukkit.getWorld(worldName) != world || !world.isChunkLoaded(chunkX, chunkZ)) return;
        for (var entity : chunk.getEntities()) {
            if (entity.getPersistentDataContainer().has(plugin.key("minion_target"), PersistentDataType.STRING)) {
                entity.remove();
                continue;
            }
            if (!(entity instanceof ArmorStand stand)) continue;
            String raw = stand.getPersistentDataContainer().get(plugin.key("minion_entity"), PersistentDataType.STRING);
            if (raw == null) continue;
            try {
                MinionInstance expected = minions.get(UUID.fromString(raw));
                if (expected == null || expected.lifecycleState() != MinionLifecycleState.ACTIVE
                        || !expected.isEntity(stand)) stand.remove();
            } catch (IllegalArgumentException ignored) { stand.remove(); }
        }
        long now = System.currentTimeMillis();
        minions.values().stream()
                .filter(minion -> minion.isChunk(chunkX, chunkZ, world))
                .forEach(minion -> bindAndQueue(minion, world, now));
    }

    public void collect(Player player, MinionInstance minion) {
        if (minion.catchingUp()) {
            player.sendMessage(config.message("catching-up"));
            return;
        }
        PreparedClaim existing = minion.preparedClaim().orElse(null);
        if (existing != null) {
            if (minion.preparedClaimBlocked()) {
                player.sendMessage(config.message("delivery-failed"));
                return;
            }
            player.sendMessage(config.message("collection-pending"));
            deliverPreparedClaim(minion, existing, existing.recipientId());
            return;
        }
        PreparedClaim prepared;
        try {
            prepared = minion.prepareClaim(player.getUniqueId());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "无法创建小人仓库 PREPARED 领取", failure);
            player.sendMessage(config.message("delivery-prepare-failed"));
            return;
        }
        if (prepared == null) {
            player.sendMessage(config.message("collected"));
            return;
        }
        if (!save()) {
            if (!minion.releasePreparedClaim(prepared.operationId())) {
                plugin.getLogger().severe("PREPARED 保存失败后无法释放仓库 reservation："
                        + prepared.operationId());
            }
            player.sendMessage(config.message("delivery-prepare-failed"));
            return;
        }
        player.sendMessage(config.message("collecting"));
        deliverPreparedClaim(minion, prepared, player.getUniqueId());
    }

    public void pickup(Player player, MinionInstance minion) {
        if (minion.catchingUp()) {
            player.sendMessage(config.message("catching-up"));
            return;
        }
        if (minion.preparedClaim().isPresent() || !minion.storage().isEmpty()) {
            collect(player, minion);
            player.sendMessage(config.message("pickup-after-collection"));
            return;
        }
        InventoryUtil.giveOrDrop(player, minion.fuel());
        InventoryUtil.giveOrDrop(player, minion.upgradeOne());
        InventoryUtil.giveOrDrop(player, minion.upgradeTwo());
        MinionType type = minion.type().orElse(null);
        if (type != null) InventoryUtil.giveOrDrop(player,
                specialItems.createMinionItem(type.id(), minion.level(), type.displayName()));
        minion.removeGeneratedBlocks();
        minion.removeEntity();
        activeMinions.remove(minion.id());
        queuedCatchUps.remove(minion.id());
        catchUpTargets.remove(minion.id());
        dirtyCatchUps.remove(minion.id());
        catchUpQueue.remove(minion.id());
        minions.remove(minion.id());
        save();
    }

    private void retryPreparedClaims() {
        minions.values().stream().filter(minion -> !minion.preparedClaimBlocked())
                .forEach(minion -> minion.preparedClaim()
                        .ifPresent(claim -> deliverPreparedClaim(minion, claim, null)));
    }

    private void deliverPreparedClaim(MinionInstance minion, PreparedClaim claim, UUID notifyPlayerId) {
        if (!deliveriesInFlight.add(claim.operationId())) return;
        DeliveryRequest request;
        try {
            request = new DeliveryRequest(
                    claim.recipientId(),
                    "yknminions",
                    claim.operationId(),
                    claim.items(),
                    DeliveryPolicy.INVENTORY_THEN_STASH,
                    DeliveryAtomicity.ALLOW_PARTIAL,
                    CapacityPolicy.REJECT_TO_SOURCE,
                    Map.of("minion-id", minion.id().toString(), "claim-id", claim.id().toString()));
            deliveryApi.deliverDetailed(request).whenComplete((result, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin,
                        () -> finishPreparedDelivery(minion.id(), claim.operationId(), notifyPlayerId, result, error));
            });
        } catch (RuntimeException exception) {
            deliveriesInFlight.remove(claim.operationId());
            plugin.getLogger().log(Level.WARNING,
                    "无法提交小人仓库领取请求 " + claim.operationId() + "，物品继续保留并锁定。", exception);
            notifyDeliveryFailure(notifyPlayerId);
        }
    }

    private void finishPreparedDelivery(UUID minionId, String operationId, UUID notifyPlayerId,
                                         DetailedDeliveryResult result, Throwable error) {
        deliveriesInFlight.remove(operationId);
        MinionInstance minion = minions.get(minionId);
        if (minion == null || minion.preparedClaim()
                .map(PreparedClaim::operationId).filter(operationId::equals).isEmpty()) return;
        if (error != null || result == null) {
            plugin.getLogger().log(Level.WARNING,
                    "小人仓库领取请求失败 " + operationId + "，物品继续保留并锁定。", error);
            notifyDeliveryFailure(notifyPlayerId);
            return;
        }
        PreparedClaim claim = minion.preparedClaim().orElseThrow();
        if (!validDetailedResult(claim, result)) {
            minion.blockPreparedClaim(operationId);
            if (!save()) plugin.getLogger().severe("无法持久化 BLOCKED 领取状态：" + operationId);
            plugin.getLogger().severe("小人仓库领取明细与 PREPARED 记录不一致 " + operationId
                    + "；已锁定领取，需人工检查以防止物品复制。");
            notifyDeliveryFailure(notifyPlayerId);
            return;
        }
        if (result.status() == DeliveryStatus.COMPLETED
                || result.status() == DeliveryStatus.PARTIAL
                || result.status() == DeliveryStatus.REJECTED) {
            settleTerminalDelivery(minion, claim, result, notifyPlayerId);
            return;
        }
        if (result.status() == DeliveryStatus.CONFLICT) {
            minion.blockPreparedClaim(operationId);
            if (!save()) plugin.getLogger().severe("无法持久化 CONFLICT 领取状态：" + operationId);
            plugin.getLogger().severe("Skyblock-Core 报告 operationId 冲突：" + operationId
                    + "；领取已锁定，需人工检查。");
            notifyDeliveryFailure(notifyPlayerId);
            return;
        }
        plugin.getLogger().warning("小人仓库领取返回 " + result.status() + "：" + operationId
                + "，物品继续保留并锁定，稍后将用相同 operationId 重试。");
        notifyDeliveryFailure(notifyPlayerId);
    }

    static boolean validDetailedResult(PreparedClaim claim, DetailedDeliveryResult result) {
        if (!claim.operationId().equals(result.operationKey())
                || claim.lines().size() != result.lines().size()) return false;
        for (int index = 0; index < claim.lines().size(); index++) {
            PreparedClaimLine claimLine = claim.lines().get(index);
            DeliveryLineResult deliveryLine = result.lines().get(index);
            if (deliveryLine.requestIndex() != index
                    || deliveryLine.requestedAmount() != claimLine.item().getAmount()) return false;
        }
        long delivered = result.result().deliveredAmount();
        long undelivered = result.result().undeliveredAmount();
        return switch (result.status()) {
            case COMPLETED -> delivered == result.result().requestedAmount()
                    && undelivered == 0;
            case PARTIAL -> delivered > 0 && undelivered > 0;
            case REJECTED, FAILED, CONFLICT -> delivered == 0
                    && undelivered == result.result().requestedAmount();
        };
    }

    private void settleTerminalDelivery(MinionInstance minion, PreparedClaim claim,
                                        DetailedDeliveryResult result, UUID notifyPlayerId) {
        List<Long> delivered = result.lines().stream()
                .map(DeliveryLineResult::deliveredAmount).toList();
        MinionInstance.PreparedClaimSettlement settlement = minion.settlePreparedClaim(
                claim.operationId(), delivered);
        if (settlement == null) {
            if (!save()) plugin.getLogger().severe(
                    "无法持久化 reservation 不一致产生的 BLOCKED 状态：" + claim.operationId());
            plugin.getLogger().severe("无法按明细结算小人仓库领取 " + claim.operationId()
                    + "；物品保持锁定以防止重复发放。");
            notifyDeliveryFailure(notifyPlayerId);
            return;
        }
        if (!save()) {
            minion.rollbackPreparedClaimSettlement(settlement);
            plugin.getLogger().severe("小人仓库领取已由 Core 处理，但本地结算保存失败 "
                    + claim.operationId() + "；已回滚内存状态，稍后将用同一 operationId 恢复结算。");
            notifyDeliveryFailure(notifyPlayerId);
            return;
        }
        if (result.status() == DeliveryStatus.REJECTED) {
            logSettlement(claim, result, 0);
            notifyPlayer(notifyPlayerId, "delivery-rejected");
            return;
        }
        double awardedExperience = awardExperience(claim, result.lines());
        logSettlement(claim, result, awardedExperience);
        notifySuccessfulDelivery(notifyPlayerId, result, awardedExperience);
    }

    private void logSettlement(PreparedClaim claim, DetailedDeliveryResult result,
                               double awardedExperience) {
        StringJoiner lines = new StringJoiner(",", "[", "]");
        result.lines().forEach(line -> lines.add(line.requestIndex() + ":"
                + line.deliveredAmount() + "/" + line.requestedAmount()));
        plugin.getLogger().info("Minion 领取已结算 operation=" + claim.operationId()
                + " recipient=" + claim.recipientId() + " status=" + result.status()
                + " duplicate=" + result.duplicate() + " delivered-lines=" + lines
                + " xp-awarded=" + String.format(Locale.ROOT, "%.2f", awardedExperience));
    }

    private double awardExperience(PreparedClaim claim, List<DeliveryLineResult> deliveredLines) {
        Map<String, Double> experienceBySkill = experienceBySkill(claim, deliveredLines);
        double awarded = 0;
        for (Map.Entry<String, Double> entry : experienceBySkill.entrySet()) {
            SkillXpIntegration.AwardResult award = skillXpIntegration.award(
                    claim.recipientId(), entry.getKey(), entry.getValue(), claim.operationId());
            if (award == SkillXpIntegration.AwardResult.APPLIED) awarded += entry.getValue();
        }
        return awarded;
    }

    static Map<String, Double> experienceBySkill(
            PreparedClaim claim, List<DeliveryLineResult> deliveredLines) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(deliveredLines, "deliveredLines");
        if (claim.lines().size() != deliveredLines.size()) {
            throw new IllegalArgumentException("claim and delivery line counts differ");
        }
        Map<String, Double> experienceBySkill = new LinkedHashMap<>();
        for (int index = 0; index < claim.lines().size(); index++) {
            PreparedClaimLine line = claim.lines().get(index);
            if (line.skillProviderOptional().isEmpty()
                    || !"auraskills".equalsIgnoreCase(line.skillProvider())) continue;
            double experience = line.experience(deliveredLines.get(index).deliveredAmount());
            if (experience > 0) {
                experienceBySkill.merge(line.skillId(), experience, (left, right) -> {
                    double sum = left + right;
                    if (!Double.isFinite(sum)) {
                        throw new ArithmeticException("claim skill experience overflow");
                    }
                    return sum;
                });
            }
        }
        return Map.copyOf(experienceBySkill);
    }

    private void notifySuccessfulDelivery(UUID playerId, DetailedDeliveryResult result,
                                          double awardedExperience) {
        if (playerId == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        player.sendMessage(config.message("collected"));
        player.sendMessage(config.prefixed("§7领取明细：背包 §a" + result.result().inventoryAmount()
                + "§7，暂存箱 §b" + result.result().stashedAmount()
                + "§7，小人仓库保留 §e" + result.result().undeliveredAmount() + "§7。"));
        if (awardedExperience > 0) {
            player.sendMessage(config.prefixed("§7技能经验：§d+"
                    + String.format(Locale.ROOT, "%.2f", awardedExperience)));
        }
    }

    private void notifyDeliveryFailure(UUID playerId) {
        notifyPlayer(playerId, "delivery-failed");
    }

    private void notifyPlayer(UUID playerId, String messageKey) {
        if (playerId == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) player.sendMessage(config.message(messageKey));
    }

    public List<Requirement> totalRequirements(MinionInstance minion, int targetLevel) {
        MinionType type = minion.type().orElseThrow();
        Map<ItemSpec, Integer> totals = new LinkedHashMap<>();
        for (int level = minion.level() + 1; level <= targetLevel; level++) {
            for (Requirement requirement : type.level(level).upgradeMaterials()) {
                totals.merge(requirement.item(), requirement.amount(), Integer::sum);
            }
        }
        return totals.entrySet().stream().map(entry -> new Requirement(entry.getKey(), entry.getValue())).toList();
    }

    public boolean upgrade(Player player, MinionInstance minion, int targetLevel) {
        if (minion.catchingUp()) {
            player.sendMessage(config.message("catching-up"));
            return false;
        }
        if (targetLevel <= minion.level() || targetLevel > config.maxLevel()) return false;
        List<Requirement> requirements = totalRequirements(minion, targetLevel);
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (Requirement requirement : requirements) {
            int count = Arrays.stream(contents).filter(Objects::nonNull)
                    .filter(item -> resolver.matches(item, requirement.item())).mapToInt(ItemStack::getAmount).sum();
            if (count < requirement.amount()) {
                player.sendMessage(config.message("not-enough-materials"));
                return false;
            }
        }
        for (Requirement requirement : requirements) removeFromPlayer(contents, requirement);
        player.getInventory().setStorageContents(contents);
        minion.level(targetLevel);
        player.sendMessage(config.message("upgraded").replace("%level%", String.valueOf(targetLevel)));
        save();
        return true;
    }

    private void removeFromPlayer(ItemStack[] contents, Requirement requirement) {
        int remaining = requirement.amount();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !resolver.matches(item, requirement.item())) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() == 0) contents[i] = null;
        }
    }

    public void refreshModels() { minions.values().forEach(MinionInstance::refreshConfiguration); }

    public boolean save() {
        if (dataWritesBlocked) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("minions");
        minions.forEach((id, minion) -> minion.save(root.createSection(id.toString())));
        try {
            yaml.save(temporaryDataFile);
            if (dataFile.exists() && !recoveredFromBackup) {
                Files.copy(dataFile.toPath(), backupDataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporaryDataFile.toPath(), dataFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryDataFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            recoveredFromBackup = false;
            return true;
        } catch (IOException exception) {
            try { Files.deleteIfExists(temporaryDataFile.toPath()); }
            catch (IOException ignored) { }
            plugin.getLogger().log(Level.SEVERE, "保存小人数据失败", exception);
            return false;
        }
    }

    private void cleanupLoadedEntities() {
        Bukkit.getWorlds().forEach(this::cleanupLoadedEntities);
    }

    private void cleanupLoadedEntities(World world) {
        for (var target : world.getEntities()) {
            if (target.getPersistentDataContainer().has(plugin.key("minion_target"), PersistentDataType.STRING)) {
                target.remove();
            }
        }
        for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
            if (stand.getPersistentDataContainer().has(plugin.key("minion_entity"), PersistentDataType.STRING)) {
                stand.remove();
            }
        }
    }

    private Map<Integer, Material> loadOriginalFarmGround(ConfigurationSection section) {
        ConfigurationSection saved = section.getConfigurationSection("farming-original-ground");
        if (saved == null) return Map.of();
        Map<Integer, Material> result = new HashMap<>();
        for (String rawSlot : saved.getKeys(false)) {
            try {
                Material material = Material.matchMaterial(saved.getString(rawSlot, ""), false);
                if (material != null) result.put(Integer.parseInt(rawSlot), material);
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("忽略无效的农业工作区格子: " + rawSlot);
            }
        }
        return result;
    }

    private Map<String, Double> loadPendingCollections(ConfigurationSection minionSection) {
        ConfigurationSection section = minionSection.getConfigurationSection("pending-collections");
        if (section == null) return Map.of();
        Map<String, Double> result = new LinkedHashMap<>();
        for (String collectionId : section.getKeys(false)) {
            Object raw = section.get(collectionId);
            if (raw instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() > 0) {
                result.put(collectionId, number.doubleValue());
            } else {
                plugin.getLogger().warning("忽略无效的 EcoCollections 待结算数量："
                        + collectionId + "=" + raw);
            }
        }
        return result;
    }

    private PreparedClaimCodec.LoadedClaim loadPreparedClaim(
            UUID minionId, ConfigurationSection minionSection) {
        try {
            return PreparedClaimCodec.load(minionSection, minionId).orElse(null);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "小人 " + minionId + " 的 PREPARED 领取记录无效，已停止自动发放以防止物品复制。", exception);
            throw exception;
        }
    }
}
