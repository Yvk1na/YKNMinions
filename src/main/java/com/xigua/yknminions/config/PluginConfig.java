package com.xigua.yknminions.config;

import com.xigua.Main;
import com.xigua.yknminions.item.ItemSpec;
import com.xigua.yknminions.util.RandomRange;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public final class PluginConfig implements MinionRuntimeSettings {
    private final Main plugin;
    private Map<String, MinionType> minionTypes = Map.of();
    private List<FuelType> fuels = List.of();
    private int maxLevel;
    private int storageSlots;
    private int initialStorageSlots;
    private int storageSlotsPerLevel;
    private long tickPeriod;
    private long saveIntervalTicks;
    private boolean offlineProductionEnabled;
    private long offlineSettleIntervalTicks;
    private int offlineMaxActionsPerTick;
    private long offlineMaxMillisPerTick;
    private List<SpreadSettings> spreads = List.of();
    private String prefix;

    public PluginConfig(Main plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        prefix = colorize(plugin.getConfig().getString("prefix",
                plugin.getConfig().getString("messages.prefix", "&6[YknMinions] &r")));
        maxLevel = Math.max(1, Math.min(64, plugin.getConfig().getInt("max-level", 11)));
        storageSlots = Math.max(1, Math.min(15, plugin.getConfig().getInt("storage-slots", 15)));
        initialStorageSlots = Math.max(1, Math.min(storageSlots,
                plugin.getConfig().getInt("initial-storage-slots", 5)));
        storageSlotsPerLevel = Math.max(0, Math.min(15,
                plugin.getConfig().getInt("storage-slots-per-level", 1)));
        tickPeriod = Math.max(1L, plugin.getConfig().getLong("tick-period", 2L));
        saveIntervalTicks = Math.max(20L, plugin.getConfig().getLong("save-interval-seconds", 60L) * 20L);
        offlineProductionEnabled = plugin.getConfig().getBoolean("offline-production.enabled", true);
        offlineSettleIntervalTicks = Math.max(20L,
                plugin.getConfig().getLong("offline-production.settle-interval-seconds", 60L) * 20L);
        offlineMaxActionsPerTick = Math.max(1, Math.min(100_000,
                plugin.getConfig().getInt("offline-production.max-actions-per-tick", 2000)));
        offlineMaxMillisPerTick = Math.max(1L, Math.min(25L,
                plugin.getConfig().getLong("offline-production.max-millis-per-tick", 4L)));
        spreads = loadSpreads();
        fuels = loadFuels();
        minionTypes = loadMinionTypes();
    }

    private List<FuelType> loadFuels() {
        LinkedHashMap<String, FuelDefaults> supported = new LinkedHashMap<>();
        supported.put("infinite_energy", new FuelDefaults(0.0, 0L, true));
        supported.put("small_fuel", new FuelDefaults(0.10, 3600L, false));
        supported.put("medium_fuel", new FuelDefaults(0.25, 10800L, false));
        supported.put("super_fuel", new FuelDefaults(0.50, 43200L, false));
        List<FuelType> result = new ArrayList<>();
        supported.forEach((id, defaults) -> {
            try {
                String path = "fuels." + id + ".";
                double efficiency = plugin.getConfig().getDouble(
                        path + "efficiency", defaults.efficiency());
                long burnTimeSeconds = defaults.infinite() ? 0L : plugin.getConfig().getLong(
                        path + "burn-time-seconds", defaults.burnTimeSeconds());
                result.add(new FuelType(id, new ItemSpec("yknminions:" + id),
                        efficiency, burnTimeSeconds, defaults.infinite()));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "无法加载燃料 " + id, exception);
            }
        });
        return List.copyOf(result);
    }

    private record FuelDefaults(double efficiency, long burnTimeSeconds, boolean infinite) {}

    private List<SpreadSettings> loadSpreads() {
        LinkedHashMap<String, String> rewards = new LinkedHashMap<>();
        rewards.put("diamond_spread", "minecraft:diamond");
        rewards.put("emerald_spread", "minecraft:emerald");
        rewards.put("iron_spread", "minecraft:iron_ingot");
        rewards.put("gold_spread", "minecraft:gold_ingot");
        rewards.put("lapis_spread", "minecraft:lapis_lazuli");
        List<SpreadSettings> result = new ArrayList<>();
        rewards.forEach((id, reward) -> {
            String path = id.replace('_', '-') + ".";
            result.add(new SpreadSettings(id, new ItemSpec(reward),
                    plugin.getConfig().getDouble(path + "chance", 0.10),
                    RandomRange.parse(plugin.getConfig().get(path + "amount"), 1)));
        });
        return List.copyOf(result);
    }

    private Map<String, MinionType> loadMinionTypes() {
        File file = new File(plugin.getDataFolder(), "minions.yml");
        mergeBundledMinionTypes(file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("types");
        if (root == null) return Map.of();
        Map<String, MinionType> result = new LinkedHashMap<>();
        for (String rawId : root.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            try {
                ModelSettings model = readModel(Objects.requireNonNull(section.getConfigurationSection("model")));
                Map<Integer, LevelSettings> levels = readLevels(Objects.requireNonNull(section.getConfigurationSection("levels")));
                RandomRange dropAmount = RandomRange.parse(section.get("drop-amount"), 1);
                SlimeSettings slimeSettings = readSlimeSettings(id,
                        section.getConfigurationSection("slime-settings"), dropAmount);
                result.put(id, new MinionType(id,
                        section.getString("display-name", id),
                        new ItemSpec(section.getString("drop", "minecraft:cobblestone")),
                        dropAmount,
                        section.getString("work-sound", "BLOCK_STONE_BREAK"),
                        model, readMobSettings(id, section.getConfigurationSection("mob-settings"),
                        dropAmount, slimeSettings), slimeSettings,
                        readMiningSettings(section.getConfigurationSection("mining-settings")),
                        readFarmingSettings(section.getConfigurationSection("farming-settings")),
                        readSkillSettings(id, section.getConfigurationSection("skill")),
                        readCollectionSettings(id, section.getConfigurationSection("collection")),
                        levels));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "无法加载小人类型 " + id, exception);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void mergeBundledMinionTypes(File file) {
        try (InputStream stream = plugin.getResource("minions.yml")) {
            if (stream == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection bundledTypes = bundled.getConfigurationSection("types");
            if (bundledTypes == null) return;
            YamlConfiguration installed = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            for (String id : bundledTypes.getKeys(false)) {
                String targetPath = "types." + id;
                if (installed.isConfigurationSection(targetPath)) {
                    changed |= copyMissingSection(bundled, installed,
                            targetPath + ".skill");
                    changed |= copyMissingSection(bundled, installed,
                            targetPath + ".collection");
                    continue;
                }
                ConfigurationSection source = bundledTypes.getConfigurationSection(id);
                if (source == null) continue;
                for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) {
                        installed.set(targetPath + "." + entry.getKey(), entry.getValue());
                    }
                }
                changed = true;
            }
            if (changed) {
                installed.save(file);
                plugin.getLogger().info("已向 minions.yml 补充新版本内置的小人类型或奖励集成默认值。");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法合并新版内置小人配置", exception);
        }
    }

    private static boolean copyMissingSection(YamlConfiguration source,
                                              YamlConfiguration target,
                                              String path) {
        if (target.contains(path) || !source.isConfigurationSection(path)) return false;
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) return false;
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                target.set(path + "." + entry.getKey(), entry.getValue());
            }
        }
        return true;
    }

    private ModelSettings readModel(ConfigurationSection section) {
        return new ModelSettings(
                specOrNull(section.getString("helmet")), specOrNull(section.getString("chestplate")),
                specOrNull(section.getString("leggings")), specOrNull(section.getString("boots")),
                specOrNull(section.getString("tool")), parseColor(section.getString("leather-color", "160,101,64")));
    }

    private SlimeSettings readSlimeSettings(String typeId, ConfigurationSection section, RandomRange fallbackDropAmount) {
        if (section == null && !"slime".equals(typeId)) return null;
        LinkedHashMap<Integer, Double> chances = new LinkedHashMap<>();
        ConfigurationSection chanceSection = section == null ? null : section.getConfigurationSection("size-chances");
        if (chanceSection != null) {
            for (String rawSize : chanceSection.getKeys(false)) {
                try {
                    chances.put(Integer.parseInt(rawSize), chanceSection.getDouble(rawSize));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("忽略无效的史莱姆大小: " + rawSize);
                }
            }
        }
        if (chances.isEmpty()) {
            chances.put(1, 50.0);
            chances.put(2, 35.0);
            chances.put(4, 15.0);
        }
        Object configuredDrop = section == null ? null : section.get("drop-per-kill");
        RandomRange dropPerKill = configuredDrop == null
                ? fallbackDropAmount
                : RandomRange.parse(configuredDrop, fallbackDropAmount.min());
        long turnDelayTicks = section == null ? 10L : section.getLong("turn-delay-ticks", 10L);
        return new SlimeSettings(chances, dropPerKill, turnDelayTicks);
    }

    private MobSettings readMobSettings(String typeId, ConfigurationSection section,
                                        RandomRange fallbackDropAmount, SlimeSettings slimeSettings) {
        if (section == null) {
            if (!"slime".equals(typeId) || slimeSettings == null) return null;
            return new MobSettings(EntityType.SLIME, slimeSettings.dropPerKill(), slimeSettings.turnDelayTicks());
        }
        String rawEntity = section.getString("entity", typeId).toUpperCase(Locale.ROOT);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(rawEntity);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的目标生物类型: " + rawEntity, exception);
        }
        RandomRange dropPerKill = RandomRange.parse(section.get("drop-per-kill"), fallbackDropAmount.min());
        long turnDelayTicks = section.getLong("turn-delay-ticks", 10L);
        return new MobSettings(entityType, dropPerKill, turnDelayTicks);
    }

    private MiningSettings readMiningSettings(ConfigurationSection section) {
        if (section == null) return null;
        Material block = Material.matchMaterial(section.getString("block", ""), false);
        if (block == null) throw new IllegalArgumentException("未知的采集方块: " + section.getString("block"));
        return new MiningSettings(block);
    }

    private FarmingSettings readFarmingSettings(ConfigurationSection section) {
        if (section == null) return null;
        String rawCrop = section.getString("crop", "").toUpperCase(Locale.ROOT);
        try {
            return new FarmingSettings(FarmingSettings.Crop.valueOf(rawCrop));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的农业作物类型: " + rawCrop, exception);
        }
    }

    private SkillSettings readSkillSettings(String typeId, ConfigurationSection section) {
        if (section == null) return null;
        try {
            String provider = section.getString("provider", "auraskills")
                    .trim().toLowerCase(Locale.ROOT);
            if (!"auraskills".equals(provider)) {
                throw new IllegalArgumentException("不支持的 Skill provider: " + provider);
            }
            String skillId = section.getString("id", section.getString("skill", ""));
            List<SkillRewardSettings> rewards = new ArrayList<>();
            Set<String> descriptors = new HashSet<>();
            for (Map<?, ?> entry : section.getMapList("rewards")) {
                Object rawItem = entry.get("item");
                if (rawItem == null) {
                    throw new IllegalArgumentException("Skill reward 缺少 item");
                }
                ItemSpec item = new ItemSpec(String.valueOf(rawItem));
                if (!descriptors.add(item.descriptor())) {
                    throw new IllegalArgumentException(
                            "重复的 Skill reward: " + item.descriptor());
                }
                Object rawXp = entry.containsKey("xp-per-base-unit")
                        ? entry.get("xp-per-base-unit")
                        : entry.containsKey("xp-per-item")
                        ? entry.get("xp-per-item") : entry.get("xp");
                if (!(rawXp instanceof Number xp)) {
                    throw new IllegalArgumentException(
                            "Skill reward 缺少 xp-per-base-unit: " + item.descriptor());
                }
                Object rawEquivalent = entry.containsKey("equivalent")
                        ? entry.get("equivalent") : 1.0;
                if (!(rawEquivalent instanceof Number equivalent)) {
                    throw new IllegalArgumentException(
                            "Skill reward equivalent 不是数字: " + item.descriptor());
                }
                rewards.add(new SkillRewardSettings(
                        item, xp.doubleValue(), equivalent.doubleValue()));
            }
            return new SkillSettings(provider, skillId, rewards);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "小人 " + typeId + " 的 Skill 配置无效，已跳过技能经验接入。",
                    exception);
            return null;
        }
    }

    private CollectionSettings readCollectionSettings(
            String typeId, ConfigurationSection section) {
        if (section == null) return null;
        try {
            String provider = section.getString("provider", "ecocollections")
                    .trim().toLowerCase(Locale.ROOT);
            if (!"ecocollections".equals(provider)) {
                throw new IllegalArgumentException(
                        "不支持的 Collection provider: " + provider);
            }
            return new CollectionSettings(provider,
                    Objects.requireNonNull(section.getString("id"), "collection id"));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "小人 " + typeId + " 的 Collection 配置无效，已跳过收集进度接入。",
                    exception);
            return null;
        }
    }

    private Map<Integer, LevelSettings> readLevels(ConfigurationSection section) {
        Map<Integer, LevelSettings> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            int level = Integer.parseInt(key);
            ConfigurationSection current = section.getConfigurationSection(key);
            if (current == null) continue;
            List<Requirement> requirements = new ArrayList<>();
            for (Map<?, ?> entry : current.getMapList("upgrade-materials")) {
                Object item = entry.get("item");
                Object amount = entry.get("amount");
                if (item == null || !(amount instanceof Number number) || number.intValue() <= 0) continue;
                requirements.add(new Requirement(new ItemSpec(String.valueOf(item)), number.intValue()));
            }
            result.put(level, new LevelSettings(level,
                    current.getDouble("work-interval-seconds", 10.0), requirements));
        }
        return result;
    }

    private static ItemSpec specOrNull(String value) {
        return value == null || value.isBlank() ? null : new ItemSpec(value);
    }

    private static Color parseColor(String raw) {
        try {
            String[] split = raw.split(",");
            return Color.fromRGB(Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim()), Integer.parseInt(split[2].trim()));
        } catch (RuntimeException ignored) {
            return Color.fromRGB(160, 101, 64);
        }
    }

    public String message(String key) {
        return prefixed(plugin.getConfig().getString("messages." + key, key));
    }

    public String prefixed(String message) { return prefix + colorize(message); }
    public String prefix() { return prefix; }

    private static String colorize(String value) {
        if (value == null) return "";
        return value.replaceAll("&([0-9A-FK-ORa-fk-or])", "§$1");
    }

    public Map<String, MinionType> minionTypes() { return minionTypes; }
    public Optional<MinionType> minionType(String id) { return Optional.ofNullable(minionTypes.get(id.toLowerCase(Locale.ROOT))); }
    public List<FuelType> fuels() { return fuels; }
    public Optional<FuelType> fuel(String id) {
        if (id == null) return Optional.empty();
        return fuels.stream().filter(fuel -> fuel.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }
    public int maxLevel() { return maxLevel; }
    public int storageSlots() { return storageSlots; }
    public int storageSlots(int level) {
        long unlocked = (long) initialStorageSlots + (long) Math.max(0, level - 1) * storageSlotsPerLevel;
        return (int) Math.min(storageSlots, unlocked);
    }
    public long tickPeriod() { return tickPeriod; }
    public long saveIntervalTicks() { return saveIntervalTicks; }
    public boolean offlineProductionEnabled() { return offlineProductionEnabled; }
    public long offlineSettleIntervalTicks() { return offlineSettleIntervalTicks; }
    public int offlineMaxActionsPerTick() { return offlineMaxActionsPerTick; }
    public long offlineMaxMillisPerTick() { return offlineMaxMillisPerTick; }
    public List<SpreadSettings> spreads() { return spreads; }
}
