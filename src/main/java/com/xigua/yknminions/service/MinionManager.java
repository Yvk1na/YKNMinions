package com.xigua.yknminions.service;

import com.xigua.Main;
import com.xigua.yknminions.config.*;
import com.xigua.yknminions.item.ItemResolver;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.item.SpecialItemService;
import com.xigua.yknminions.model.MinionInstance;
import com.xigua.yknminions.util.InventoryUtil;
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
import java.util.*;
import java.util.logging.Level;

public final class MinionManager {
    private final Main plugin;
    private final PluginConfig config;
    private final ItemResolver resolver;
    private final SpecialItemService specialItems;
    private final AutoCraftService autoCraft;
    private final Map<UUID, MinionInstance> minions = new LinkedHashMap<>();
    private final File dataFile;
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    public MinionManager(Main plugin, PluginConfig config, ItemResolver resolver,
                         SpecialItemService specialItems, AutoCraftService autoCraft) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
        this.specialItems = specialItems;
        this.autoCraft = autoCraft;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        cleanupLoadedEntities();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("minions");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection section = Objects.requireNonNull(root.getConfigurationSection(key));
                World world = Bukkit.getWorld(Objects.requireNonNull(section.getString("world")));
                if (world == null) {
                    plugin.getLogger().warning("跳过世界未加载的小人 " + key);
                    continue;
                }
                Location location = new Location(world, section.getDouble("x"), section.getDouble("y"),
                        section.getDouble("z"), (float) section.getDouble("yaw"), 0f);
                List<ItemStack> storage = section.getList("storage", List.of()).stream()
                        .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList();
                Map<Integer, Material> originalFarmGround = loadOriginalFarmGround(section);
                MinionInstance minion = new MinionInstance(plugin, config, resolver, specialItems, autoCraft,
                        UUID.fromString(key), UUID.fromString(Objects.requireNonNull(section.getString("owner"))),
                        section.getString("type", "slime"), section.getInt("level", 1), location,
                        section.getIntegerList("generated-blocks"), section.getIntegerList("farming-plants"),
                        section.getIntegerList("farming-produce"), originalFarmGround, storage,
                        section.getItemStack("fuel"), section.getItemStack("upgrade-one"), section.getItemStack("upgrade-two"),
                        section.getLong("fuel-burn-until", 0L), section.getInt("fuel-actions-remaining", 0),
                        section.getString("active-fuel"), section.getLong("next-work-at", 0));
                minions.put(minion.id(), minion);
                minion.spawn();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "无法加载小人 " + key, exception);
            }
        }
    }

    public void startTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            minions.values().forEach(minion -> minion.tick(now));
        }, config.tickPeriod(), config.tickPeriod());
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, config.saveIntervalTicks(), config.saveIntervalTicks());
    }

    public void restartTasks() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        startTasks();
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        save();
        minions.values().forEach(MinionInstance::removeEntity);
    }

    public MinionInstance create(Player owner, String typeId, int level, Location location) {
        if (config.minionType(typeId).isEmpty()) return null;
        float yaw = Math.round(owner.getLocation().getYaw() / 90f) * 90f + 180f;
        location.setYaw(yaw);
        MinionInstance minion = new MinionInstance(plugin, config, resolver, specialItems, autoCraft,
                UUID.randomUUID(), owner.getUniqueId(), typeId, Math.max(1, Math.min(config.maxLevel(), level)),
                location, List.of(), List.of(), List.of(), Map.of(), List.of(),
                null, null, null, 0L, 0, null, System.currentTimeMillis() + 1000);
        minions.put(minion.id(), minion);
        try {
            minion.spawn();
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
        return minions.values().stream().noneMatch(minion -> minion.location().getWorld().equals(location.getWorld())
                && minion.location().distanceSquared(location) < 2.25);
    }

    public Optional<MinionInstance> byEntity(ArmorStand stand) {
        String raw = stand.getPersistentDataContainer().get(plugin.key("minion_entity"), PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try { return Optional.ofNullable(minions.get(UUID.fromString(raw))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public Optional<MinionInstance> byTarget(LivingEntity target) {
        String raw = target.getPersistentDataContainer().get(plugin.key("minion_target"), PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try { return Optional.ofNullable(minions.get(UUID.fromString(raw))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public Optional<MinionInstance> byId(UUID id) { return Optional.ofNullable(minions.get(id)); }

    public void accelerateFarmingGrowth(Block growingBlock, BlockState newState) {
        for (MinionInstance minion : minions.values()) {
            if (minion.accelerateNaturalGrowth(growingBlock, newState)) return;
        }
    }

    public void onChunkLoad(Chunk chunk) {
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
                if (expected == null || !expected.isEntity(stand)) stand.remove();
            } catch (IllegalArgumentException ignored) { stand.remove(); }
        }
        minions.values().stream().filter(minion -> minion.isChunk(chunk.getX(), chunk.getZ(), chunk.getWorld()))
                .forEach(MinionInstance::spawn);
    }

    public void collect(Player player, MinionInstance minion) {
        minion.storage().takeAll().forEach(item -> InventoryUtil.giveOrDrop(player, item));
        player.sendMessage(config.message("collected"));
    }

    public void pickup(Player player, MinionInstance minion) {
        collect(player, minion);
        InventoryUtil.giveOrDrop(player, minion.fuel());
        InventoryUtil.giveOrDrop(player, minion.upgradeOne());
        InventoryUtil.giveOrDrop(player, minion.upgradeTwo());
        MinionType type = minion.type().orElse(null);
        if (type != null) InventoryUtil.giveOrDrop(player,
                specialItems.createMinionItem(type.id(), minion.level(), type.displayName()));
        minion.removeGeneratedBlocks();
        minion.removeEntity();
        minions.remove(minion.id());
        save();
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

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("minions");
        minions.forEach((id, minion) -> minion.save(root.createSection(id.toString())));
        try {
            yaml.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "保存小人数据失败", exception);
        }
    }

    private void cleanupLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (var target : world.getEntities()) {
                if (target.getPersistentDataContainer().has(plugin.key("minion_target"), PersistentDataType.STRING)) target.remove();
            }
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(plugin.key("minion_entity"), PersistentDataType.STRING)) stand.remove();
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
}
